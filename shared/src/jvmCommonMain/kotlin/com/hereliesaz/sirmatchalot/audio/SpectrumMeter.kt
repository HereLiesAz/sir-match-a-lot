package com.hereliesaz.sirmatchalot.audio

import com.hereliesaz.sirmatchalot.dsp.Biquad
import com.hereliesaz.sirmatchalot.dsp.BiquadCoefficients
import kotlin.math.sqrt

/**
 * How much bass, mid and treble the output is carrying, right now.
 *
 * The background is supposed to be *"genuinely driven by the audio, not a
 * strobe"*, and a single output level cannot do that: one number makes
 * everything pulse together, which is exactly what a strobe looks like. Three
 * bands make a kick, a vocal and a hi-hat move different things, which is what
 * a room full of lights actually does.
 *
 * Filter-bank rather than FFT, deliberately. Three biquads per channel cost a
 * handful of multiplies per sample and no allocation, where an FFT on the audio
 * thread means a buffer, a window and a transform per block. Three bands is also
 * all a light show can use — nobody watching a wall can distinguish the 30th
 * spectral bin from the 31st.
 *
 * ## Attack and release
 *
 * Levels rise fast and fall slowly, which is what makes lights read as lights:
 * a kick should hit immediately and then bloom away. Symmetric smoothing gives
 * either a jittery flicker or a sluggish smear, and no setting of it gives both
 * a sharp hit and a smooth decay.
 *
 * Every method is allocation-free and called from the audio thread. Readers get
 * the values through volatile fields, so the UI never blocks the renderer.
 */
class SpectrumMeter(sampleRate: Int = 44_100) {

    /** Bass energy, 0..1, smoothed. */
    @Volatile
    var low: Float = 0f
        private set

    /** Mid energy, 0..1, smoothed. */
    @Volatile
    var mid: Float = 0f
        private set

    /** Treble energy, 0..1, smoothed. */
    @Volatile
    var high: Float = 0f
        private set

    // Mono-summed before filtering: a light show has no left and right, and it
    // halves the filtering work.
    private val lowPass = Biquad(BiquadCoefficients.lowPass(LOW_CROSSOVER_HZ, sampleRate, q = 0.7071))
    private val bandPass = Biquad(BiquadCoefficients.bandPass(MID_CENTRE_HZ, sampleRate, q = 0.8))
    private val highPass = Biquad(BiquadCoefficients.highPass(HIGH_CROSSOVER_HZ, sampleRate, q = 0.7071))

    /**
     * Measures [frames] of interleaved stereo from [buffer].
     *
     * Reads only; the audio is not modified, so this can sit anywhere in the
     * chain without changing what is heard.
     */
    fun measure(buffer: FloatArray, frames: Int) {
        if (frames <= 0) return

        var lowSum = 0.0
        var midSum = 0.0
        var highSum = 0.0

        for (i in 0 until frames) {
            val base = i * Deck.CHANNELS
            val mono = (buffer[base] + buffer[base + 1]) * 0.5f

            val l = lowPass.process(mono)
            val m = bandPass.process(mono)
            val h = highPass.process(mono)

            lowSum += l.toDouble() * l
            midSum += m.toDouble() * m
            highSum += h.toDouble() * h
        }

        low = follow(low, normalise(sqrt(lowSum / frames).toFloat(), LOW_REFERENCE))
        mid = follow(mid, normalise(sqrt(midSum / frames).toFloat(), MID_REFERENCE))
        high = follow(high, normalise(sqrt(highSum / frames).toFloat(), HIGH_REFERENCE))
    }

    /** Drops everything to dark. */
    fun reset() {
        low = 0f
        mid = 0f
        high = 0f
        lowPass.reset()
        bandPass.reset()
        highPass.reset()
    }

    /**
     * Fast up, slow down.
     *
     * The asymmetry is the whole point: a kick has to land on the frame it
     * happens, and then fade rather than snap off.
     */
    private fun follow(current: Float, target: Float): Float {
        val coefficient = if (target > current) ATTACK else RELEASE
        return current + (target - current) * coefficient
    }

    /**
     * Scales a band's RMS to 0..1 against a reference level.
     *
     * Per band, because bass in recorded music carries far more energy than
     * treble — a shared reference would leave the treble light permanently off
     * and the bass one permanently saturated.
     */
    private fun normalise(rms: Float, reference: Float): Float =
        (rms / reference).coerceIn(0f, 1f)

    companion object {
        /** Below this is the kick and the bassline. */
        const val LOW_CROSSOVER_HZ = 160.0

        /** Where voices and most melodic content sit. */
        const val MID_CENTRE_HZ = 1_200.0

        /** Above this is cymbals, air and sibilance. */
        const val HIGH_CROSSOVER_HZ = 5_000.0

        // Reference levels, from typical mastered material: bass is loud,
        // treble is not.
        const val LOW_REFERENCE = 0.35f
        const val MID_REFERENCE = 0.18f
        const val HIGH_REFERENCE = 0.06f

        /** Per-block coefficients, at roughly 90 blocks a second. */
        const val ATTACK = 0.55f
        const val RELEASE = 0.06f
    }
}
