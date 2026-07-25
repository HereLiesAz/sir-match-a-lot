package com.hereliesaz.sirmatchalot.audio

import com.hereliesaz.sirmatchalot.dsp.Biquad
import com.hereliesaz.sirmatchalot.dsp.BiquadCoefficients
import kotlin.math.abs
import kotlin.math.pow

/**
 * The XY performance pad, as a real effect on the master bus.
 *
 * ## What this replaces
 *
 * The pad used to drive `SynthEngine` — a sine-and-noise generator on its own
 * `AudioTrack`, running *beside* the mixer rather than on it. Moving a finger
 * across it changed the pitch of a tone nobody wanted to hear and did nothing
 * whatsoever to the music. `SynthEngine` was deleted rather than left in place
 * pretending to be an effect, which left this requirement honestly unmet until
 * now.
 *
 * ## The mapping
 *
 * **X is a bipolar DJ filter**, not a plain lowpass. Centre is bypass; sweeping
 * left brings a lowpass down from the top of the band, sweeping right brings a
 * highpass up from the bottom. That is the filter every DJ mixer has, and it is
 * far more useful than a unipolar sweep because the neutral position is in the
 * middle where a finger naturally rests.
 *
 * Frequency is mapped **logarithmically**, because pitch perception is. A linear
 * map spends most of its travel between 10 kHz and 20 kHz, where almost nothing
 * audible happens, and crosses the entire musically interesting range in the
 * last few pixels.
 *
 * **Y is resonance.** Q rises from a flat 0.707 to 8, so the top of the pad
 * gives the whistling emphasis at the cutoff that makes a filter sweep sound
 * like a performance rather than a tone control.
 *
 * ## Why the gain compensation
 *
 * A resonant filter's peak gain is roughly Q at the cutoff, so a high-Q sweep
 * would otherwise slam the limiter and audibly duck the whole mix. The output is
 * scaled by `1/sqrt(Q)` — enough to keep the peak roughly in hand without fully
 * flattening the effect, which would defeat the point of the resonance.
 *
 * Parameters are smoothed per frame rather than per block, so a fast gesture
 * glides instead of stepping. Coefficients are recomputed only when the smoothed
 * frequency or Q has actually moved a meaningful amount, because computing a
 * biquad per sample would be both wasteful and, at these smoothing rates,
 * inaudible against computing it every few.
 *
 * Not thread-safe; owned by the render thread, with controls set from outside by
 * volatile writes.
 */
class MasterFilter(private val sampleRate: Int) {

    /**
     * Horizontal position, -1 to 1. Zero is bypass; negative sweeps a lowpass
     * down, positive sweeps a highpass up.
     */
    @Volatile
    var x: Float = 0f

    /** Vertical position, 0 to 1, mapping to resonance. */
    @Volatile
    var y: Float = 0f

    /**
     * Whether the effect is engaged at all.
     *
     * Separate from `x == 0` so lifting a finger releases the filter completely
     * rather than leaving it parked at whatever bypass approximates.
     */
    @Volatile
    var enabled: Boolean = false

    private val filters = Array(Deck.CHANNELS) { Biquad() }

    private var smoothedX = 0f
    private var smoothedQ = MIN_Q
    private var appliedFrequency = Double.NaN
    private var appliedQ = Double.NaN
    private var appliedHighPass = false
    private var gainCompensation = 1f

    /** True when the filter is actually altering the signal. */
    val active: Boolean
        get() = enabled && abs(smoothedX) > BYPASS_WIDTH

    /**
     * Filters [out] in place — interleaved stereo, [frames] frames.
     *
     * Allocates nothing. When the effect is disengaged and has finished gliding
     * back to centre, this returns having touched nothing, so the bypass path
     * costs a comparison rather than a filter pass.
     */
    fun process(out: FloatArray, frames: Int) {
        val targetX = if (enabled) x.coerceIn(-1f, 1f) else 0f
        val targetQ = MIN_Q + (MAX_Q - MIN_Q) * y.coerceIn(0f, 1f)

        // Glide even while disengaged, so releasing the pad opens the filter
        // smoothly instead of snapping it wide.
        val settled = abs(targetX - smoothedX) < 1e-4f && abs(targetQ - smoothedQ) < 1e-3f
        if (settled && abs(targetX) <= BYPASS_WIDTH) {
            smoothedX = targetX
            smoothedQ = targetQ
            resetIfNeeded()
            return
        }

        for (frame in 0 until frames) {
            smoothedX += (targetX - smoothedX) * SMOOTHING
            smoothedQ += (targetQ - smoothedQ) * SMOOTHING

            updateCoefficients()

            val base = frame * Deck.CHANNELS
            if (abs(smoothedX) <= BYPASS_WIDTH) {
                // Inside the dead zone: keep filter state warm but pass through,
                // so entering the zone does not click.
                for (channel in 0 until Deck.CHANNELS) {
                    filters[channel].process(out[base + channel])
                }
                continue
            }
            for (channel in 0 until Deck.CHANNELS) {
                val index = base + channel
                out[index] = filters[channel].process(out[index]) * gainCompensation
            }
        }
    }

    /** Releases the pad, letting the filter glide back to neutral. */
    fun release() {
        enabled = false
        x = 0f
        y = 0f
    }

    private fun resetIfNeeded() {
        if (appliedFrequency.isNaN()) return
        filters.forEach { it.reset() }
        appliedFrequency = Double.NaN
        appliedQ = Double.NaN
        gainCompensation = 1f
    }

    private fun updateCoefficients() {
        val highPass = smoothedX > 0f
        val depth = abs(smoothedX).coerceIn(0f, 1f).toDouble()
        val q = smoothedQ.toDouble()

        // Logarithmic sweep. A lowpass comes down from the top of the band; a
        // highpass climbs up from the bottom.
        val frequency = if (highPass) {
            HIGH_PASS_MIN * (HIGH_PASS_MAX / HIGH_PASS_MIN).pow(depth)
        } else {
            LOW_PASS_MAX * (LOW_PASS_MIN / LOW_PASS_MAX).pow(depth)
        }

        val frequencyMoved = appliedFrequency.isNaN() ||
            abs(frequency - appliedFrequency) > appliedFrequency * COEFFICIENT_TOLERANCE
        val qMoved = appliedQ.isNaN() || abs(q - appliedQ) > COEFFICIENT_TOLERANCE
        if (!frequencyMoved && !qMoved && highPass == appliedHighPass) return

        val nyquistLimited = frequency.coerceIn(20.0, sampleRate * 0.45)
        val coefficients = if (highPass) {
            BiquadCoefficients.highPass(nyquistLimited, sampleRate, q)
        } else {
            BiquadCoefficients.lowPass(nyquistLimited, sampleRate, q)
        }
        filters.forEach { it.coefficients = coefficients }
        appliedFrequency = frequency
        appliedQ = q
        appliedHighPass = highPass
        // Peak gain of a resonant biquad is about Q at the cutoff. Backing off by
        // its square root keeps a high-Q sweep from slamming the limiter without
        // flattening the resonance the gesture is for.
        gainCompensation = (1.0 / kotlin.math.sqrt(q.coerceAtLeast(1.0))).toFloat()
    }

    companion object {
        /** Lowest frequency the lowpass sweeps down to. */
        const val LOW_PASS_MIN = 80.0

        /** Effectively open — above the top of hearing. */
        const val LOW_PASS_MAX = 20_000.0

        const val HIGH_PASS_MIN = 30.0

        /** High enough that a full sweep leaves only air. */
        const val HIGH_PASS_MAX = 8_000.0

        const val MIN_Q = 0.7071f

        /** Resonant enough to whistle, short of self-oscillation. */
        const val MAX_Q = 8f

        /**
         * Half-width of the centre dead zone. Without one, a finger resting near
         * the middle would apply a filter at the very top or bottom of the band
         * that is inaudible but still costs a filter pass, and — worse — makes
         * "off" impossible to hit by hand.
         */
        const val BYPASS_WIDTH = 0.02f

        /** Per-frame one-pole coefficient, matching the mixer's gain smoothing. */
        const val SMOOTHING = 0.0005f

        /** Relative change in frequency or Q that justifies recomputing a biquad. */
        const val COEFFICIENT_TOLERANCE = 0.001
    }
}
