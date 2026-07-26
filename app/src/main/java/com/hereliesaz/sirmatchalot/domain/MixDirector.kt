package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import kotlin.math.max
import kotlin.math.min

/** Which deck a command refers to. */
enum class MixDeck { A, B;

    val other: MixDeck get() = if (this == A) B else A

    /** Crossfader position at which this deck is heard alone. */
    val crossfadePosition: Float get() = if (this == A) 0f else 1f
}

/**
 * Something the director wants done.
 *
 * Commands rather than direct calls, so the decision logic is a pure function of
 * elapsed time and can be tested without an audio device, a decoder, or a
 * ViewModel. [MixPlanner] could always say what to play; nothing could say *when*.
 */
sealed interface MixCommand {

    /** Decode [track] onto [deck] and leave it silent, ready to start. */
    data class Preload(val track: Track, val deck: MixDeck) : MixCommand

    /**
     * Begin playing [deck], applying [alignment] so it enters in time and in key.
     *
     * Separate from [Preload] because decoding takes seconds and a transition
     * cannot wait for it — the load happens well before the entry.
     */
    data class Start(val track: Track, val deck: MixDeck, val alignment: BeatAlignment?) : MixCommand

    /** Move the crossfader to [position], 0 being Deck A alone and 1 Deck B alone. */
    data class Crossfade(val position: Float) : MixCommand

    /** Stop [deck] and free [track]; the transition away from it has finished. */
    data class Retire(val track: Track, val deck: MixDeck) : MixCommand

    /** Announce what is playing now, for the UI. */
    data class NowPlaying(val step: MixStep, val index: Int, val total: Int) : MixCommand

    /** The plan has run out. */
    data object Finished : MixCommand
}

/**
 * Plays a [MixPlan]: decides when each transition starts, how long it lasts, and
 * where the crossfader should be at every moment.
 *
 * [MixPlanner] builds a running order with a beat alignment per step, and until
 * now that was the whole feature — the plan was displayed and never performed.
 * This is the missing half.
 *
 * ## Why time is passed in rather than read
 *
 * The director advances on a delta supplied by the caller instead of reading a
 * deck's playhead. A deck timeline is *circular* — a single clip alone on a deck
 * loops, which is what makes one sample circle the whole platter — so its
 * playhead wraps and cannot express "forty seconds into a four-minute track".
 * Taking a delta also makes every test here run in an instant rather than in
 * real time.
 *
 * ## The shape of a transition
 *
 * Track *i* starts playing at S(i). Its transition to *i+1* begins one crossfade
 * length before its end, and that instant is exactly S(i+1) — the incoming track
 * starts at the moment the fade starts, so both are audible together for the
 * whole fade. That gives `S(i+1) = S(i) + duration(i) - crossfade(i)`, which is
 * why the clock resets to the crossfade length rather than to zero when a step
 * completes.
 *
 * Crossfade length is expressed in **bars**, not seconds, so it stays musically
 * the same length across tempos. It is capped at a third of the outgoing track so
 * a short clip cannot have a transition longer than itself.
 *
 * @param plan the running order to perform.
 * @param crossfadeBars how long a transition lasts, in bars of the outgoing track.
 * @param preloadLeadSeconds how far ahead of its entry the next track is decoded.
 */
class MixDirector(
    val plan: MixPlan,
    val crossfadeBars: Int = DEFAULT_CROSSFADE_BARS,
    val preloadLeadSeconds: Double = DEFAULT_PRELOAD_LEAD_SECONDS,
) {
    /** Index into [MixPlan.steps] of the track currently in the foreground. */
    var index: Int = 0
        private set

    /** Deck holding the current track. */
    var deck: MixDeck = MixDeck.A
        private set

    var finished: Boolean = false
        private set

    /** Seconds since the current track started playing. */
    private var elapsed = 0.0
    private var preloadIssued = false
    private var transitionStarted = false
    private var started = false

    /** The step now in the foreground, or null once the plan is done. */
    val currentStep: MixStep? get() = plan.steps.getOrNull(index)

    /** The step being mixed in, or null when not transitioning. */
    val nextStep: MixStep? get() = plan.steps.getOrNull(index + 1)

    /** 0..1 through the current transition, or 0 when not transitioning. */
    val transitionProgress: Double
        get() {
            if (!transitionStarted) return 0.0
            val fade = crossfadeSeconds(index)
            if (fade <= 0.0) return 0.0
            val into = elapsed - (durationOf(index) - fade)
            return (into / fade).coerceIn(0.0, 1.0)
        }

    /**
     * Begins the plan: loads and starts the opening track, and puts the
     * crossfader hard over to its deck.
     *
     * The opening track has no [MixStep.alignment] — there is nothing before it
     * to align to — so it plays at its own tempo and key, and everything after
     * aligns to what it establishes.
     */
    fun start(): List<MixCommand> {
        if (started) return emptyList()
        started = true
        val opening = plan.steps.firstOrNull()
        if (opening == null || durationOf(0) <= 0.0) {
            finished = true
            return listOf(MixCommand.Finished)
        }
        return listOf(
            MixCommand.Preload(opening.track, deck),
            MixCommand.Start(opening.track, deck, alignment = null),
            MixCommand.Crossfade(deck.crossfadePosition),
            MixCommand.NowPlaying(opening, 0, plan.steps.size),
        )
    }

    /**
     * Advances the mix by [deltaSeconds] and returns what to do.
     *
     * Returns an empty list for the long stretches when nothing needs to change,
     * so a caller can tick this every frame without generating work.
     */
    fun advance(deltaSeconds: Double): List<MixCommand> {
        if (finished || !started || deltaSeconds <= 0.0) return emptyList()

        elapsed += deltaSeconds
        val duration = durationOf(index)
        if (duration <= 0.0) {
            finished = true
            return listOf(MixCommand.Finished)
        }

        val next = nextStep
        if (next == null) {
            // Last track: run it out, then stop.
            if (elapsed >= duration) {
                finished = true
                return listOf(MixCommand.Finished)
            }
            return emptyList()
        }

        val commands = ArrayList<MixCommand>(2)
        val fade = crossfadeSeconds(index)
        val transitionAt = duration - fade

        if (!preloadIssued && elapsed >= transitionAt - preloadLeadSeconds) {
            preloadIssued = true
            commands.add(MixCommand.Preload(next.track, deck.other))
        }

        if (!transitionStarted && elapsed >= transitionAt) {
            transitionStarted = true
            // Preload may not have been issued yet on a track shorter than the
            // lead time; issue it now rather than starting a deck holding nothing.
            if (!commands.any { it is MixCommand.Preload }) {
                if (!preloadIssued) {
                    preloadIssued = true
                    commands.add(MixCommand.Preload(next.track, deck.other))
                }
            }
            commands.add(MixCommand.Start(next.track, deck.other, next.alignment))
        }

        if (transitionStarted) {
            val progress = transitionProgress
            val from = deck.crossfadePosition
            val to = deck.other.crossfadePosition
            commands.add(MixCommand.Crossfade((from + (to - from) * progress.toFloat())))

            if (elapsed >= duration) {
                val retiring = plan.steps[index]
                commands.add(MixCommand.Retire(retiring.track, deck))
                // The incoming track began playing when the fade began, so its
                // own clock is already `fade` seconds in.
                elapsed = fade
                index++
                deck = deck.other
                preloadIssued = false
                transitionStarted = false
                commands.add(MixCommand.NowPlaying(plan.steps[index], index, plan.steps.size))
            }
        }

        return commands
    }

    /** Length of the transition out of step [at], in seconds. */
    fun crossfadeSeconds(at: Int): Double {
        val step = plan.steps.getOrNull(at) ?: return 0.0
        val bpm = step.track.bpm ?: return 0.0
        if (bpm <= 0.0) return 0.0
        val bars = crossfadeBars * BEATS_PER_BAR * 60.0 / bpm
        // Never longer than a third of the track: a transition that overruns the
        // material it is leaving is not a transition.
        return min(bars, max(0.0, durationOf(at) / 3.0))
    }

    private fun durationOf(at: Int): Double {
        val millis = plan.steps.getOrNull(at)?.track?.durationMs ?: return 0.0
        return millis / 1000.0
    }

    companion object {
        /** Long enough to be a blend rather than a cut, short enough not to drag. */
        const val DEFAULT_CROSSFADE_BARS = 16

        /** Decoding and rate-converting a track takes seconds; start well before. */
        const val DEFAULT_PRELOAD_LEAD_SECONDS = 45.0

        const val BEATS_PER_BAR = 4
    }
}
