package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * Decides how one track hands over to the next.
 *
 * The Automatchic Mix used to answer this question the same way every time:
 * sixteen bars, equal power, starting one fade before the end, entering the next
 * track at zero. Correct, and completely without character — the user's word for
 * it was "robotic", and a fixed rule applied to every pair of songs is exactly
 * what that sounds like.
 *
 * The fix is not randomness. Choosing a trick at random is as arbitrary as
 * choosing the same one forever; it just fails differently. What a DJ actually
 * does is read the two records and pick the move the *material* offers — you can
 * only loop-roll out of a track that has a loop, only swap on a drop if there is
 * a drop to swap onto. So every style here is gated on a measured feature, and
 * the choice is made among the styles that qualify.
 *
 * Two sources of variety on top of that gate:
 *
 * - **the arc** ([SetArc.Phase]), so the same pair of tracks is handled patiently
 *   at the start of a set and boldly at its peak;
 * - **weighted sampling** with a penalty on the styles just used, so a set does
 *   not discover one trick and repeat it all night.
 *
 * Everything is a pure function of measured values and an injected [Random], so
 * a set is different every run and a test is not.
 */
class TransitionChoreographer {

    /**
     * How far into [from] the transition out of it should begin.
     *
     * The user asked for tracks to be cut at musical exits rather than played to
     * the end, so this is a choice among [TrackStructure.exits] rather than
     * arithmetic on the duration. Two things decide it: how good an exit is, and
     * how long the arc wants this track to be — a peak track that overstays is
     * as wrong as a patient opener cut off at ninety seconds.
     *
     * @param minimumSeconds never leave before this. A track cut shorter than
     *   this is a stab, not a track.
     */
    fun chooseExit(
        from: TrackStructure,
        phase: SetArc.Phase,
        random: Random,
        minimumSeconds: Double = MINIMUM_PLAY_SECONDS,
        entrySeconds: Double = 0.0,
    ): TrackStructure.Exit {
        val duration = from.durationSeconds
        val fallback = TrackStructure.Exit(duration, TrackStructure.Exit.Reason.TRACK_END, 0.2)
        if (duration <= 0.0) return fallback

        // On a track too short to cut, there is nothing to choose: play it.
        //
        // The floor is measured from where the track came in, not from frame
        // zero. `chooseEntry` may enter as much as half way through, and an exit
        // behind the playhead is not an exit: every stage of the handover is
        // already due, so the whole thing fires in the tick the script is
        // resolved and the track is skipped. Measured from the entry, "played
        // for at least a minimum" means what it says.
        val floor = (entrySeconds + minimumSeconds)
            .coerceAtMost(entrySeconds + (duration - entrySeconds) * SHORT_TRACK_FRACTION)
            .coerceAtMost(duration)
        val candidates = from.exits(after = floor)
        if (candidates.isEmpty()) return fallback

        val preferred = duration * phase.playFraction
        val weights = candidates.map { exit ->
            // Quality decides which exits are worth taking; distance from the
            // arc's preferred length decides which of those to take now. Both
            // matter — quality alone would cut every track at its first
            // breakdown, however early that is.
            val drift = abs(exit.atSeconds - preferred) / duration
            exit.quality * exp(-drift * LENGTH_SHARPNESS)
        }
        return pick(candidates, weights, random) ?: fallback
    }

    /**
     * Where [to] should start playing from.
     *
     * Entering every track at zero is the other half of a robotic set: it means
     * every song is heard from its intro, including the ones whose intro is a
     * minute of filter sweep laid there so a DJ could mix over it.
     */
    fun chooseEntry(to: TrackStructure, phase: SetArc.Phase, random: Random): Double {
        val entries = to.entries()
        if (entries.size <= 1) return 0.0
        // Opening a set from the top of the first interesting track is right;
        // doing it at the peak wastes the peak. So the weight on zero falls as
        // the set climbs.
        val weights = entries.map { if (it == 0.0) phase.topOfTrackWeight else 1.0 }
        return pick(entries, weights, random) ?: 0.0
    }

    /**
     * The whole handover: when to leave, where to come in, and what happens.
     *
     * @param match the harmonic assessment, which decides whether the two can
     *   sit on top of each other at all — a bass swap between tracks that clash
     *   is two basslines fighting, not a transition.
     * @param recentStyles the styles used most recently, penalised so a set does
     *   not repeat one trick.
     */
    fun choreograph(
        from: TrackStructure,
        to: TrackStructure,
        match: MixMatch?,
        phase: SetArc.Phase,
        random: Random,
        recentStyles: List<TransitionStyle> = emptyList(),
        taste: TransitionTaste? = null,
        energyDelta: Int = 0,
        /** Where [from] itself came in, so its exit is chosen after that. */
        fromEntrySeconds: Double = 0.0,
    ): TransitionScript {
        val exit = chooseExit(from, phase, random, entrySeconds = fromEntrySeconds)
        val entry = chooseEntry(to, phase, random)

        val options = eligible(from, to, match, phase, exit, entry)
        val bar = from.barSeconds.takeIf { it > 0.0 } ?: DEFAULT_BAR_SECONDS
        val weights = options.map { (style, weight) ->
            val penalty = if (style in recentStyles) REPEAT_PENALTY else 1.0
            // What has been learned adjusts the rules rather than replacing them.
            // An untrained model returns exactly 1.0, so a fresh install picks
            // styles precisely as it did before any of this existed.
            val learned = taste?.influence(
                TransitionFeatures.of(
                    style = style,
                    exit = exit.reason,
                    phase = phase,
                    matchScore = match?.overallScore,
                    energyDelta = energyDelta,
                    fadeBars = style.bars(phase),
                    entered = entry > 0.0,
                ),
            ) ?: 1.0
            weight * penalty * learned
        }
        val style = pick(options.map { it.first }, weights, random) ?: TransitionStyle.LONG_BLEND

        val fade = (style.bars(phase) * bar)
            // A transition longer than a third of what it is leaving is not a
            // transition. Kept from the original director, which was right.
            .coerceAtMost(exit.atSeconds / 3.0)
            .coerceAtLeast(bar)

        return TransitionScript(
            style = style,
            exitSeconds = exit.atSeconds,
            entrySeconds = entry,
            fadeSeconds = fade,
            curve = style.curve,
            events = events(style, from, exit, fade, bar),
            record = TransitionRecord(
                style = style,
                exit = exit.reason,
                phase = phase,
                matchScore = match?.overallScore,
                energyDelta = energyDelta,
                fadeBars = fade / bar,
                enteredMidTrack = entry > 0.0,
            ),
        )
    }

    /**
     * At most one mid-track moment, taken from the track's **own** material.
     *
     * This is the Bach part of the brief, and the reason it quotes itself rather
     * than the track before: a fugue answers its own subject. It is also the only
     * version that can work — the previous track has been retired and its audio
     * freed, while this track's buffer is by definition still loaded and playing.
     *
     * @param safeUntil the exit; nothing is scheduled at or after it, so a
     *   flourish can never collide with a transition.
     */
    fun flourishes(
        structure: TrackStructure,
        phase: SetArc.Phase,
        random: Random,
        safeUntil: Double,
    ): List<ScriptEvent> {
        if (!structure.isUsable) return emptyList()
        if (random.nextDouble() > phase.flourishChance) return emptyList()

        // Somewhere the track has taken itself down is where a quote is audible.
        val space = structure.points
            .filter { it.kind == PointOfInterest.Kind.BREAKDOWN }
            .map { structure.nearestBar(it.timeSeconds) }
            .filter { it > MINIMUM_FLOURISH_SECONDS }

        // Quote something from *earlier* in the track: material the listener has
        // already heard, coming back under a passage that has cleared for it.
        // The whole quote has to finish before the handover — a flourish still
        // playing when a loop roll begins would be stopped by it, and the two
        // would read as one botched effect rather than two deliberate ones.
        val usable = space.mapNotNull { at ->
            val subject = structure.bestLoopBefore(at) ?: return@mapNotNull null
            val ends = at + subject.durationSeconds * FLOURISH_REPEATS
            if (ends >= safeUntil - structure.barSeconds) null else at to subject
        }

        val (at, subject) = usable.randomOrNull(random) ?: return emptyList()

        return listOf(
            ScriptEvent(
                atSeconds = at,
                action = ScriptAction.Loop(
                    role = MixRole.OUTGOING,
                    loop = subject,
                    repeat = true,
                    gain = FLOURISH_GAIN,
                ),
            ),
            ScriptEvent(
                atSeconds = at + subject.durationSeconds * FLOURISH_REPEATS,
                action = ScriptAction.StopLoops,
            ),
        )
    }

    // --- Choosing a style ---

    /**
     * The styles this pair of tracks can actually support, with base weights.
     *
     * Every entry but the last is gated on something measured. A style whose
     * material is absent is not weighted down, it is not offered — a loop roll
     * with no loop would be four bars of silence.
     */
    private fun eligible(
        from: TrackStructure,
        to: TrackStructure,
        match: MixMatch?,
        phase: SetArc.Phase,
        exit: TrackStructure.Exit,
        entry: Double,
    ): List<Pair<TransitionStyle, Double>> {
        val options = ArrayList<Pair<TransitionStyle, Double>>()
        val score = match?.overallScore ?: 0
        val bothGridded = from.isUsable && to.isUsable

        // Always available, and the only thing available when nothing was
        // measured. Weighted so it stays the backbone of a set rather than a
        // last resort — most transitions in a good set are blends.
        options.add(TransitionStyle.LONG_BLEND to phase.blendWeight)

        if (bothGridded && score >= BASS_SWAP_MINIMUM_SCORE) {
            options.add(TransitionStyle.BASS_SWAP to 1.2)
        }

        if (exit.reason == TrackStructure.Exit.Reason.BREAKDOWN) {
            // The outgoing track has already made room. This is the easiest
            // transition in the world and it should be taken nearly always.
            options.add(TransitionStyle.BREAKDOWN_HANDOFF to 2.5)
        }

        // A drop the incoming track reaches shortly after its entry is the thing
        // a cut exists for.
        val incomingDrop = to.firstPoint(PointOfInterest.Kind.DROP, after = entry)
        val dropIsClose = incomingDrop != null &&
            incomingDrop.timeSeconds - entry <= to.barSeconds * DROP_REACH_BARS
        if (bothGridded && dropIsClose) {
            options.add(TransitionStyle.DROP_SWAP to 1.5 * phase.boldness)
        }

        if (from.bestLoopBefore(exit.atSeconds) != null && from.barSeconds > 0.0) {
            options.add(TransitionStyle.LOOP_ROLL to 1.0 * phase.boldness)
        }

        // Bold, loud, and unmistakable. Peak material only, and only into a drop
        // — a reverse into a quiet intro is a mistake, not a move.
        if (bothGridded && dropIsClose && phase == SetArc.Phase.PEAK) {
            options.add(TransitionStyle.REVERSE_SLAM to 0.9)
        }

        if (bothGridded && score >= CUT_MINIMUM_SCORE) {
            options.add(TransitionStyle.CUT to 0.5 * phase.boldness)
        }

        return options
    }

    /** What happens during the fade, beyond the fader moving. */
    private fun events(
        style: TransitionStyle,
        from: TrackStructure,
        exit: TrackStructure.Exit,
        fade: Double,
        bar: Double,
    ): List<ScriptEvent> = when (style) {
        // Deliberately nothing: this is the original transition, kept intact as
        // the thing everything else is a departure from.
        TransitionStyle.LONG_BLEND -> emptyList()

        TransitionStyle.BASS_SWAP -> listOf(
            // The incoming track arrives with no low end at all, so two
            // basslines never occupy the same moment.
            ScriptEvent(0.0, ScriptAction.Eq(MixRole.INCOMING, -18.0, 0.0)),
            ScriptEvent(fade * 0.5, ScriptAction.Eq(MixRole.OUTGOING, -9.0, 0.0)),
            ScriptEvent(fade * 0.5, ScriptAction.Eq(MixRole.INCOMING, -9.0, 0.0)),
            // and takes the low end over completely as the outgoing one leaves.
            ScriptEvent(fade * 0.75, ScriptAction.Eq(MixRole.OUTGOING, -18.0, 0.0)),
            ScriptEvent(fade * 0.75, ScriptAction.Eq(MixRole.INCOMING, 0.0, 0.0)),
        )

        TransitionStyle.BREAKDOWN_HANDOFF -> listOf(
            // Into a breakdown, so the incoming track can be full almost at
            // once; it is filling a hole rather than competing.
            ScriptEvent(0.0, ScriptAction.Eq(MixRole.INCOMING, -9.0, 0.0)),
            ScriptEvent(fade * 0.35, ScriptAction.Eq(MixRole.INCOMING, 0.0, 0.0)),
        )

        TransitionStyle.DROP_SWAP -> listOf(
            // Thin the outgoing track through the hold so the cut lands into
            // space rather than out of a full mix.
            ScriptEvent(fade * 0.5, ScriptAction.Eq(MixRole.OUTGOING, -12.0, 2.0)),
        )

        TransitionStyle.LOOP_ROLL -> {
            val subject = from.bestLoopBefore(exit.atSeconds)
            if (subject == null) {
                emptyList()
            } else {
                listOf(
                    ScriptEvent(
                        0.0,
                        ScriptAction.Loop(MixRole.OUTGOING, subject, repeat = true, gain = 0.8f),
                    ),
                    // The deck itself steps back and lets its own loop carry the
                    // exit, which is what makes this sound deliberate.
                    ScriptEvent(bar, ScriptAction.Gain(MixRole.OUTGOING, 0.45f)),
                    ScriptEvent(fade * 0.85, ScriptAction.StopLoops),
                    ScriptEvent(fade * 0.85, ScriptAction.Gain(MixRole.OUTGOING, 1f)),
                )
            }
        }

        TransitionStyle.REVERSE_SLAM -> listOf(
            ScriptEvent(0.0, ScriptAction.Rate(MixRole.OUTGOING, -1.0)),
            // Restored before the deck is retired, so nothing is ever left
            // playing backwards if the set is stopped mid-move.
            ScriptEvent(fade * 0.9, ScriptAction.Rate(MixRole.OUTGOING, 1.0)),
        )

        TransitionStyle.CUT -> emptyList()
    }

    // --- Weighted choice ---

    /** Samples [items] with [weights]; null only when there is nothing to pick. */
    private fun <T> pick(items: List<T>, weights: List<Double>, random: Random): T? {
        if (items.isEmpty()) return null
        val safe = weights.map { it.coerceAtLeast(0.0) }
        val total = safe.sum()
        if (total <= 0.0) return items.first()
        var target = random.nextDouble() * total
        for (index in items.indices) {
            target -= safe[index]
            if (target <= 0.0) return items[index]
        }
        return items.last()
    }

    private fun <T> List<T>.randomOrNull(random: Random): T? =
        if (isEmpty()) null else this[random.nextInt(size)]

    companion object {
        /** A track played for less than this was not played, it was sampled. */
        const val MINIMUM_PLAY_SECONDS = 75.0

        /** On a track too short for the floor, this fraction of it instead. */
        const val SHORT_TRACK_FRACTION = 0.5

        /** How sharply an exit far from the arc's preferred length is discounted. */
        const val LENGTH_SHARPNESS = 4.0

        /** Weight multiplier for a style used in the last couple of transitions. */
        const val REPEAT_PENALTY = 0.15

        /** Below this the two tracks cannot share a low end. */
        const val BASS_SWAP_MINIMUM_SCORE = 70

        /** A cut is exposed: both sides are heard alone, so the key must hold. */
        const val CUT_MINIMUM_SCORE = 80

        /** How soon after its entry the incoming drop must land to cut onto it. */
        const val DROP_REACH_BARS = 32

        /** Bars, when the outgoing track has no measured grid to count them in. */
        const val DEFAULT_BAR_SECONDS = 2.0

        /** No quoting in the first minute; there is nothing to quote yet. */
        const val MINIMUM_FLOURISH_SECONDS = 60.0

        /** Under the track, not over it. */
        const val FLOURISH_GAIN = 0.5f

        /** How many times a quoted loop comes round before it withdraws. */
        const val FLOURISH_REPEATS = 2.0

        /** How many past styles are penalised. */
        const val RECENT_MEMORY = 2
    }
}

/** How long a transition of this style runs, in bars, for a given phase. */
private fun TransitionStyle.bars(phase: SetArc.Phase): Double = when (this) {
    TransitionStyle.LONG_BLEND -> 16.0 * phase.lengthScale
    TransitionStyle.BASS_SWAP -> 16.0 * phase.lengthScale
    TransitionStyle.BREAKDOWN_HANDOFF -> 24.0 * phase.lengthScale
    TransitionStyle.DROP_SWAP -> 8.0
    TransitionStyle.LOOP_ROLL -> 8.0
    TransitionStyle.REVERSE_SLAM -> 2.0
    TransitionStyle.CUT -> 1.0
}

/** The fader shape this style implies. Cuts hold; blends travel. */
private val TransitionStyle.curve: FadeCurve
    get() = when (this) {
        TransitionStyle.LONG_BLEND -> FadeCurve.EQUAL_POWER
        TransitionStyle.BASS_SWAP -> FadeCurve.FAST_IN
        TransitionStyle.BREAKDOWN_HANDOFF -> FadeCurve.FAST_IN
        TransitionStyle.DROP_SWAP -> FadeCurve.HOLD_THEN_CUT
        TransitionStyle.LOOP_ROLL -> FadeCurve.SLOW_IN
        TransitionStyle.REVERSE_SLAM -> FadeCurve.HOLD_THEN_CUT
        TransitionStyle.CUT -> FadeCurve.HOLD_THEN_CUT
    }

// --- What each phase of the set wants ---

/** How much of a track this phase plays before looking for an exit. */
private val SetArc.Phase.playFraction: Double
    get() = when (this) {
        SetArc.Phase.OPENING -> 0.9
        SetArc.Phase.BUILD -> 0.75
        SetArc.Phase.PEAK -> 0.55
        SetArc.Phase.RELEASE -> 0.85
    }

/** Multiplier on transition length. The peak is quick; the edges are patient. */
private val SetArc.Phase.lengthScale: Double
    get() = when (this) {
        SetArc.Phase.OPENING -> 1.25
        SetArc.Phase.BUILD -> 1.0
        SetArc.Phase.PEAK -> 0.5
        SetArc.Phase.RELEASE -> 1.1
    }

/** How much the bold styles are favoured. */
private val SetArc.Phase.boldness: Double
    get() = when (this) {
        SetArc.Phase.OPENING -> 0.2
        SetArc.Phase.BUILD -> 1.0
        SetArc.Phase.PEAK -> 2.2
        SetArc.Phase.RELEASE -> 0.6
    }

/** How strongly the plain blend is favoured against everything else. */
private val SetArc.Phase.blendWeight: Double
    get() = when (this) {
        SetArc.Phase.OPENING -> 3.0
        SetArc.Phase.BUILD -> 1.6
        SetArc.Phase.PEAK -> 0.8
        SetArc.Phase.RELEASE -> 2.0
    }

/** How often a track is taken from the top rather than from a landmark. */
private val SetArc.Phase.topOfTrackWeight: Double
    get() = when (this) {
        SetArc.Phase.OPENING -> 3.0
        SetArc.Phase.BUILD -> 1.0
        SetArc.Phase.PEAK -> 0.4
        SetArc.Phase.RELEASE -> 1.5
    }

/** Chance of a mid-track self-quote. */
private val SetArc.Phase.flourishChance: Double
    get() = when (this) {
        SetArc.Phase.OPENING -> 0.1
        SetArc.Phase.BUILD -> 0.35
        SetArc.Phase.PEAK -> 0.5
        SetArc.Phase.RELEASE -> 0.3
    }
