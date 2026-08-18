package com.hereliesaz.sirmatchalot.audio

import kotlin.math.abs
import kotlin.math.sign

/**
 * Translates the two-finger vertical gesture into a playback rate.
 *
 * The brief was a "smart turntable": pulling back slows the music, the pitch
 * falls with it, the rate reaches zero, and continuing past that plays
 * backwards — on a non-linear curve, so small movements near normal speed are
 * gentle and large ones bite. A real record cannot change tempo and pitch
 * independently; this deliberately behaves like the record, because during a
 * scratch the pitch artefact *is* the sound. Time-stretching is bypassed here
 * and only applies away from a scratch.
 *
 * The class also owns the reverse-distance accumulator behind the easter egg.
 * There is exactly one accumulator: the previous implementation had two,
 * incremented on different code paths with different thresholds, so the trigger
 * fired inconsistently and the speech-rate ramp only advanced on one of them.
 */
class ScratchModel(
    /** Gesture pixels that map to one unit of rate change. */
    private val pixelsPerRateUnit: Float = 400f,
    /** Curve exponent; above 1 softens small movements. */
    private val curve: Float = 1.7f,
    /** Rate is clamped to this magnitude in both directions. */
    private val maxRate: Double = 3.0,
    /**
     * The output rate `frames` in [accountForRenderedFrames] is counted in.
     * Defaulted for callers (and tests) that only care about the rate curve;
     * a real engine must pass its actual output rate, since [frames] is
     * counted in it. Only used to compute the default below — passing
     * [easterEggReverseFrames] directly overrides it.
     */
    sampleRate: Double = 44_100.0,
    /**
     * Reverse frames that must accumulate before the easter egg fires.
     * Defaults to six seconds at [sampleRate].
     *
     * This used to be hardcoded to `44_100.0 * 6` regardless of the engine's
     * actual output rate, so on the common 48 kHz device the "six second"
     * threshold fired at 5.51 s — 8.8% early — and every intermediate
     * progress readout over-reported by the same margin.
     */
    private val easterEggReverseFrames: Double = sampleRate * 6,
) {
    // Every field below is written by the gesture, on the UI thread, and read —
    // and in two cases read-modify-written — by the audio callback. `Deck` says
    // as much about its own controls and marks them volatile; this sits on the
    // same boundary and did not, so the audio thread had no guarantee of ever
    // seeing a scratch begin, and `accumulatedReverseFrames` is a `Double`,
    // which the JLS permits to be written as two 32-bit halves. A reader could
    // see a value from neither the before nor the after state.

    /** Rate to apply while the gesture is active. */
    @Volatile
    var rate: Double = 1.0
        private set

    /** True between [begin] and [end]. */
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    private var accumulatedReverseFrames = 0.0

    @Volatile
    private var easterEggFired = false

    /** Rate the deck should return to once the gesture ends. */
    @Volatile
    private var restingRate: Double = 1.0

    /**
     * Starts a scratch from the deck's current rate.
     *
     * @param currentRate the rate to return to when the gesture ends.
     */
    fun begin(currentRate: Double = 1.0) {
        active = true
        restingRate = currentRate
        rate = currentRate
        accumulatedReverseFrames = 0.0
        easterEggFired = false
    }

    /**
     * Updates the rate from the gesture's cumulative vertical displacement.
     *
     * @param totalDeltaY pixels from the gesture's start. Positive is downward,
     *   which pulls the record backwards.
     * @return the new rate.
     */
    fun update(totalDeltaY: Float): Double {
        if (!active) return rate
        val normalised = (totalDeltaY / pixelsPerRateUnit).toDouble()
        // Shape the magnitude, keep the direction: gentle near zero, steeper further out.
        val shaped = sign(normalised) * Math.pow(abs(normalised), curve.toDouble())
        rate = (restingRate - shaped).coerceIn(-maxRate, maxRate)
        return rate
    }

    /**
     * Accounts for [frames] frames of output rendered at the current rate.
     *
     * @return true exactly once per gesture, on the frame the reverse threshold
     *   is crossed — so the caller triggers the easter egg once rather than
     *   repeatedly while the finger is still moving.
     */
    fun accountForRenderedFrames(frames: Int): Boolean {
        if (!active || rate >= 0) {
            if (rate > 0) accumulatedReverseFrames = 0.0
            return false
        }
        accumulatedReverseFrames += abs(rate) * frames
        if (!easterEggFired && accumulatedReverseFrames >= easterEggReverseFrames) {
            easterEggFired = true
            return true
        }
        return false
    }

    /** Ends the gesture, restoring the resting rate. */
    fun end(): Double {
        active = false
        rate = restingRate
        accumulatedReverseFrames = 0.0
        easterEggFired = false
        return rate
    }

    /** How far into the reverse threshold the gesture has travelled, 0..1. */
    fun reverseProgress(): Float =
        (accumulatedReverseFrames / easterEggReverseFrames).coerceIn(0.0, 1.0).toFloat()
}
