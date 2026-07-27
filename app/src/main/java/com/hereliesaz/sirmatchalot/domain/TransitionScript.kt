package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.LoopCandidate

/** Which side of a transition an action applies to. */
enum class MixRole { OUTGOING, INCOMING }

/**
 * One thing done during a transition, at a moment inside it.
 *
 * Expressed against **roles** rather than decks: a script is a description of a
 * transition, and which physical deck is leaving alternates every track. The
 * director binds roles to decks when it emits the commands, which is the only
 * place that knows.
 */
data class ScriptEvent(val atSeconds: Double, val action: ScriptAction)

/** Something a transition does beyond moving the crossfader. */
sealed interface ScriptAction {

    /** Shelving EQ in dB, as [com.hereliesaz.sirmatchalot.audio.Deck] applies it. */
    data class Eq(val role: MixRole, val bassDb: Double, val trebleDb: Double) : ScriptAction

    data class Gain(val role: MixRole, val gain: Float) : ScriptAction

    /** Playback rate. Negative plays backwards; 1.0 is normal. */
    data class Rate(val role: MixRole, val rate: Double) : ScriptAction

    /** Play [loop] from [role]'s track on the mix sampler. */
    data class Loop(
        val role: MixRole,
        val loop: LoopCandidate,
        val repeat: Boolean,
        val gain: Float = 0.7f,
    ) : ScriptAction

    /** Silence everything the mix sampler is playing. */
    data object StopLoops : ScriptAction
}

/**
 * How the crossfader travels across a transition.
 *
 * A crossfade is not one shape. Two tracks that share a groove want the
 * incoming one to arrive early and sit underneath; a track being cut into on a
 * drop wants the fader to not move at all until the instant it moves entirely.
 * Using one curve for every transition is a large part of why every transition
 * sounded the same.
 *
 * The value returned is the position between the outgoing deck and the incoming
 * one, 0 at the start and 1 at the end. The mixer applies its own equal-power
 * law on top, so these shapes describe *timing*, not gain.
 */
enum class FadeCurve {
    /** Straight through. What every transition used to be. */
    EQUAL_POWER,

    /** The incoming track arrives early and the outgoing lingers under it. */
    FAST_IN,

    /** The outgoing track holds and the incoming arrives late. */
    SLOW_IN,

    /** Nothing moves, then everything does — a cut, landed on the beat. */
    HOLD_THEN_CUT,
    ;

    /** Crossfader position for [progress] through the transition, both 0..1. */
    fun at(progress: Double): Float {
        val p = progress.coerceIn(0.0, 1.0)
        return when (this) {
            EQUAL_POWER -> p
            FAST_IN -> Math.pow(p, 0.5)
            SLOW_IN -> p * p
            HOLD_THEN_CUT ->
                if (p < HOLD_FRACTION) 0.0 else (p - HOLD_FRACTION) / (1.0 - HOLD_FRACTION)
        }.toFloat()
    }

    companion object {
        /** How much of a cut is spent holding still before it lands. */
        const val HOLD_FRACTION = 0.85
    }
}

/**
 * How one handover is performed.
 *
 * The transition itself, rather than a length of time. Two tracks, a moment to
 * leave the first, a moment to enter the second, a shape for the fader, and
 * whatever else happens while it runs.
 *
 * @param exitSeconds track time on the outgoing deck at which the fade begins.
 * @param entrySeconds where the incoming track starts playing from.
 * @param fadeSeconds how long the two overlap.
 * @param events actions during the fade, at times relative to its start.
 */
data class TransitionScript(
    val style: TransitionStyle,
    val exitSeconds: Double,
    val entrySeconds: Double,
    val fadeSeconds: Double,
    val curve: FadeCurve,
    val events: List<ScriptEvent> = emptyList(),
) {
    /** Track time on the outgoing deck when the transition is over. */
    val endSeconds: Double get() = exitSeconds + fadeSeconds

    /** Fader position at [progress] through the fade. */
    fun positionAt(progress: Double): Float = curve.at(progress)
}

/**
 * The move a DJ makes to get from one record to the next.
 *
 * Each of these is a thing the engine can already do and never did: shelving EQ
 * per deck, a signed playback rate, a sampler that can be triggered
 * programmatically, and a crossfader that can be moved on any curve. What was
 * missing was anything deciding *which* to use, so the answer was always the
 * first one.
 *
 * @param label what the screen says while it is happening. A varied set the UI
 *   narrates identically still reads as robotic.
 */
enum class TransitionStyle(val label: String) {
    /** Sixteen bars, straight through. The old behaviour, kept as the fallback. */
    LONG_BLEND("Long blend"),

    /** Roll the outgoing bass out and the incoming bass in; two grooves, one low end. */
    BASS_SWAP("Bass swap"),

    /** The outgoing track has already taken itself away. Walk into the space. */
    BREAKDOWN_HANDOFF("Breakdown handoff"),

    /** Hold both, then cut on the incoming downbeat so its drop lands alone. */
    DROP_SWAP("Drop swap"),

    /** Loop the outgoing track's last bars while the next one arrives underneath. */
    LOOP_ROLL("Loop roll"),

    /** Drive the outgoing deck backwards into the incoming downbeat. */
    REVERSE_SLAM("Reverse slam"),

    /** Straight out, straight in, on the bar. */
    CUT("Cut"),
}
