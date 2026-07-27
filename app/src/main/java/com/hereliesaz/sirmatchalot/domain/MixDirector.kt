package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.LoopCandidate
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

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
     *
     * @param entrySeconds where in the track to come in. Zero — the default, and
     *   what every transition used to do — means from the top. A track whose
     *   first ninety seconds are a filter sweep laid there for a DJ to mix over
     *   should not be heard from its first frame every time.
     */
    data class Start(
        val track: Track,
        val deck: MixDeck,
        val alignment: BeatAlignment?,
        val entrySeconds: Double = 0.0,
    ) : MixCommand

    /** Move the crossfader to [position], 0 being Deck A alone and 1 Deck B alone. */
    data class Crossfade(val position: Float) : MixCommand

    /** Stop [deck] and free [track]; the transition away from it has finished. */
    data class Retire(val track: Track, val deck: MixDeck) : MixCommand

    /** Announce what is playing now, for the UI. */
    data class NowPlaying(val step: MixStep, val index: Int, val total: Int) : MixCommand

    /** Shelving EQ on [deck], in dB, as the deck itself applies it. */
    data class SetDeckEq(val deck: MixDeck, val bassDb: Double, val trebleDb: Double) : MixCommand

    data class SetDeckGain(val deck: MixDeck, val gain: Float) : MixCommand

    /** Playback rate for [deck]. Negative plays backwards; 1.0 is normal. */
    data class SetDeckRate(val deck: MixDeck, val rate: Double) : MixCommand

    /** Play [loop], taken from [trackId], on the mix sampler. */
    data class TriggerLoop(
        val trackId: String,
        val loop: LoopCandidate,
        val repeat: Boolean,
        val gain: Float,
    ) : MixCommand

    /** Silence everything the mix sampler is playing. */
    data object StopMixLoops : MixCommand

    /** What the transition now running is, so the UI can name it. */
    data class Performing(val style: TransitionStyle) : MixCommand

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
 * ## Performing rather than sequencing
 *
 * The paragraph above describes one transition, and for a long time it described
 * *every* transition — same length, same curve, same entry at frame zero, for
 * every pair of songs in the library. That is what made a planned set sound, in
 * the user's words, like the most robotic matchmaking ever.
 *
 * Given a [TrackStructureProvider], each handover is instead choreographed from
 * what the two tracks actually contain: where they drop, where they break down,
 * what loops cleanly, where the vocal comes in. See [TransitionChoreographer].
 *
 * The provider is **optional and the default is null**, and that is a deliberate
 * design property rather than a convenience. With no provider every branch below
 * takes exactly the path it always did, so the plain sixteen-bar blend remains
 * intact as the fallback for unanalysed material — and remains covered by the
 * tests that were written against it.
 *
 * @param plan the running order to perform.
 * @param crossfadeBars how long a transition lasts, in bars of the outgoing track.
 * @param preloadLeadSeconds how far ahead of its entry the next track is decoded.
 * @param structures where to find each track's shape, or null to blend blindly.
 * @param seed varies the choreography between runs. Fixed under test; in the app
 *   it comes from the clock, so running the same library twice is not the same
 *   set twice.
 */
class MixDirector(
    val plan: MixPlan,
    val crossfadeBars: Int = DEFAULT_CROSSFADE_BARS,
    val preloadLeadSeconds: Double = DEFAULT_PRELOAD_LEAD_SECONDS,
    private val structures: TrackStructureProvider? = null,
    private val choreographer: TransitionChoreographer = TransitionChoreographer(),
    seed: Long = 0L,
) {
    private val random = Random(seed)
    private val arc = SetArc.of(plan.steps)
    private val recentStyles = ArrayDeque<TransitionStyle>()

    /** The transition out of the current track, or null when blending blindly. */
    private var script: TransitionScript? = null
    private var scriptResolved = false
    private var eventCursor = 0

    /** Mid-track self-quotes for the current track, in track time. */
    private var flourishes: List<ScriptEvent> = emptyList()
    private var flourishCursor = 0

    /** How the transition now running is being performed, for the UI. */
    val currentStyle: TransitionStyle? get() = script?.style
    /** Index into [MixPlan.steps] of the track currently in the foreground. */
    var index: Int = 0
        private set

    /** Deck holding the current track. */
    var deck: MixDeck = MixDeck.A
        private set

    var finished: Boolean = false
        private set

    /**
     * Where the current track has got to, in its own playback time.
     *
     * Not "seconds since it started": a track may be entered part way in, and
     * then the two differ by exactly that entry. Every landmark the
     * choreographer works from is measured in track time, so this is the clock
     * that can be compared against them. With no structures in play the entry is
     * always zero and the two readings coincide, which is why this change is
     * invisible to the plain blend.
     */
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
            val fade = fadeSeconds()
            if (fade <= 0.0) return 0.0
            val into = elapsed - transitionAt()
            return (into / fade).coerceIn(0.0, 1.0)
        }

    /** How long the transition out of the current track lasts. */
    private fun fadeSeconds(): Double = script?.fadeSeconds ?: crossfadeSeconds(index)

    /**
     * Track time at which the transition out of the current track begins.
     *
     * With a script this is a chosen musical exit — a breakdown, the bar after a
     * drop, the seam at the end of a loop — which is why a structured set is
     * *shorter* than the sum of its tracks. Without one it is the end of the
     * track minus the fade, exactly as before.
     */
    private fun transitionAt(): Double =
        script?.exitSeconds ?: (durationOf(index) - crossfadeSeconds(index))

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

        resolveScript()
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
        val fade = fadeSeconds()
        val transitionAt = transitionAt()
        val leaveAt = transitionAt + fade

        // Anything the track does to itself before the handover begins.
        while (flourishCursor < flourishes.size && elapsed >= flourishes[flourishCursor].atSeconds) {
            commands.addAll(bind(flourishes[flourishCursor].action))
            flourishCursor++
        }

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
            script?.let { commands.add(MixCommand.Performing(it.style)) }
            commands.add(
                MixCommand.Start(
                    next.track,
                    deck.other,
                    next.alignment,
                    entrySeconds = script?.entrySeconds ?: 0.0,
                ),
            )
        }

        if (transitionStarted) {
            val running = script
            if (running != null) {
                // Events are written relative to the start of the fade, because
                // that is what they are about — "half way through the swap", not
                // "at 214 seconds".
                while (
                    eventCursor < running.events.size &&
                    elapsed >= transitionAt + running.events[eventCursor].atSeconds
                ) {
                    commands.addAll(bind(running.events[eventCursor].action))
                    eventCursor++
                }
            }

            val progress = transitionProgress
            val from = deck.crossfadePosition
            val to = deck.other.crossfadePosition
            val travel = running?.positionAt(progress) ?: progress.toFloat()
            commands.add(MixCommand.Crossfade((from + (to - from) * travel)))

            if (elapsed >= leaveAt) {
                val retiring = plan.steps[index]
                // Nothing a script did to a deck outlives the deck's turn. A set
                // stopped mid-slam must not leave a deck playing backwards, and
                // the deck about to be reused must not inherit a -18 dB shelf.
                if (running != null) {
                    commands.add(MixCommand.StopMixLoops)
                    commands.add(MixCommand.SetDeckRate(deck, 1.0))
                    commands.add(MixCommand.SetDeckEq(deck, 0.0, 0.0))
                    commands.add(MixCommand.SetDeckGain(deck, 1f))
                    recentStyles.addLast(running.style)
                    while (recentStyles.size > TransitionChoreographer.RECENT_MEMORY) {
                        recentStyles.removeFirst()
                    }
                }
                commands.add(MixCommand.Retire(retiring.track, deck))
                // The incoming track began playing when the fade began, so its
                // own clock is already `fade` seconds past wherever it came in.
                elapsed = (running?.entrySeconds ?: 0.0) + fade
                index++
                deck = deck.other
                preloadIssued = false
                transitionStarted = false
                scriptResolved = false
                script = null
                eventCursor = 0
                flourishes = emptyList()
                flourishCursor = 0
                commands.add(MixCommand.NowPlaying(plan.steps[index], index, plan.steps.size))
            }
        }

        return commands
    }

    /**
     * Works out how the current track leaves, once, on its first tick.
     *
     * Once rather than per frame because it draws on [random]: recomputing it
     * would pick a different style every fiftieth of a second, and the set would
     * be not varied but undecided.
     */
    private fun resolveScript() {
        if (scriptResolved) return
        scriptResolved = true

        val provider = structures ?: return
        val step = plan.steps.getOrNull(index) ?: return
        val from = provider.structureFor(step.track)?.takeIf { it.isUsable } ?: return
        val phase = arc.phaseAt(index)

        val next = nextStep
        if (next != null) {
            val to = provider.structureFor(next.track)
            if (to != null) {
                script = choreographer.choreograph(
                    from = from,
                    to = to,
                    match = next.transition,
                    phase = phase,
                    random = random,
                    recentStyles = recentStyles.toList(),
                )
            }
        }

        // The last track has no transition out of it but can still quote itself.
        val until = script?.exitSeconds ?: from.durationSeconds
        flourishes = choreographer
            .flourishes(from, phase, random, safeUntil = until)
            .sortedBy { it.atSeconds }
    }

    /** Turns a role-relative script action into a command against a real deck. */
    private fun bind(action: ScriptAction): List<MixCommand> {
        fun deckFor(role: MixRole) = if (role == MixRole.OUTGOING) deck else deck.other
        fun trackFor(role: MixRole) =
            if (role == MixRole.OUTGOING) currentStep?.track else nextStep?.track

        return when (action) {
            is ScriptAction.Eq ->
                listOf(MixCommand.SetDeckEq(deckFor(action.role), action.bassDb, action.trebleDb))

            is ScriptAction.Gain ->
                listOf(MixCommand.SetDeckGain(deckFor(action.role), action.gain))

            is ScriptAction.Rate ->
                listOf(MixCommand.SetDeckRate(deckFor(action.role), action.rate))

            is ScriptAction.Loop -> {
                val track = trackFor(action.role) ?: return emptyList()
                listOf(
                    MixCommand.TriggerLoop(track.id, action.loop, action.repeat, action.gain),
                )
            }

            ScriptAction.StopLoops -> listOf(MixCommand.StopMixLoops)
        }
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
