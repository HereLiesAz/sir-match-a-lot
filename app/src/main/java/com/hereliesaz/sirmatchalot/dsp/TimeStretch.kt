package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * WSOLA (waveform-similarity overlap-add) time-scale modification.
 *
 * Changes duration without changing pitch, which is what "auto stretch" needs:
 * beat-matching two tracks by tempo alone, leaving their keys intact so
 * [com.hereliesaz.sirmatchalot.domain.HarmonicEngine] still describes reality.
 *
 * The synthesis hop is fixed at half a frame so that a periodic Hann window
 * satisfies the constant-overlap-add condition and the frames sum to unity
 * gain. The analysis hop is scaled by the tempo ratio, and for each output
 * frame the input frame is nudged within [seekWindow] to the position whose
 * waveform best continues the previous frame — that search is what suppresses
 * the phase-cancellation warble a naive OLA produces.
 *
 * @param frameSize analysis/synthesis frame length in samples; must be even.
 * @param seekWindow how far, in samples, the similarity search may move a frame.
 */
class TimeStretcher(
    private val frameSize: Int = 2048,
    private val seekWindow: Int = 256,
) {
    init {
        require(frameSize >= 64 && frameSize % 2 == 0) { "frameSize must be even and >= 64" }
        require(seekWindow >= 0) { "seekWindow must be non-negative" }
    }

    private val overlap = frameSize / 2
    private val window = Window.hann(frameSize)

    /**
     * Returns [input] time-scaled by [ratio] with pitch unchanged.
     *
     * `ratio > 1` compresses (plays faster / shorter output); `ratio < 1`
     * expands. A ratio of exactly 1 returns a copy.
     */
    fun stretch(input: FloatArray, ratio: Double): FloatArray {
        require(ratio > 0.0) { "ratio must be positive, was $ratio" }
        if (input.isEmpty()) return FloatArray(0)
        if (ratio == 1.0) return input.copyOf()

        val outputLength = (input.size / ratio).roundToInt().coerceAtLeast(1)
        if (input.size < frameSize) {
            // Too short to window meaningfully; fall back to a plain resample,
            // which shifts pitch but keeps the call total rather than silent.
            val out = FloatArray(outputLength)
            Resampler.render(input, 0.0, ratio, out)
            return out
        }

        val output = FloatArray(outputLength)
        val analysisHop = overlap * ratio

        var outPos = 0
        var nominal = 0.0
        // Where the previously emitted frame would naturally have continued.
        var continuation = -1

        while (outPos + frameSize <= outputLength) {
            val nominalStart = nominal.roundToInt()
            val start =
                if (continuation < 0) nominalStart
                else bestMatch(input, nominalStart, continuation)

            for (i in 0 until frameSize) {
                val s = start + i
                if (s < 0 || s >= input.size) continue
                output[outPos + i] += input[s] * window[i]
            }

            continuation = start + overlap
            outPos += overlap
            nominal += analysisHop
        }

        // Tail: fill whatever remains straight from the source so the output
        // does not end in an abrupt window ramp.
        if (outPos < outputLength && continuation >= 0) {
            var s = continuation
            for (i in outPos until outputLength) {
                output[i] += if (s in input.indices) input[s] else 0f
                s++
            }
        }

        return output
    }

    /**
     * Finds the frame start within `nominal ± seekWindow` whose leading
     * [overlap] samples best correlate with the [target] continuation, by
     * normalised cross-correlation.
     */
    private fun bestMatch(input: FloatArray, nominal: Int, target: Int): Int {
        if (seekWindow == 0) return nominal
        if (target + overlap > input.size) return nominal

        var best = nominal
        var bestScore = -Float.MAX_VALUE
        // Decimating the correlation by 4 costs little accuracy at these frame
        // sizes and keeps the search affordable.
        val step = 4

        for (delta in -seekWindow..seekWindow) {
            val candidate = nominal + delta
            if (candidate < 0 || candidate + overlap > input.size) continue

            var correlation = 0f
            var energy = 0f
            var i = 0
            while (i < overlap) {
                val a = input[candidate + i]
                correlation += a * input[target + i]
                energy += a * a
                i += step
            }
            val score = correlation / sqrt(energy + 1e-9f)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }
}

/**
 * Shifts pitch without changing duration, by resampling (which moves pitch and
 * duration together) and then time-stretching back to the original length.
 *
 * This is "auto pitch": harmonic correction toward a compatible Camelot key
 * without disturbing a beat-matched tempo.
 */
class PitchShifter(
    private val stretcher: TimeStretcher = TimeStretcher(),
) {
    /**
     * @param semitones positive shifts up, negative down.
     */
    fun shift(input: FloatArray, semitones: Double): FloatArray {
        if (input.isEmpty() || semitones == 0.0) return input.copyOf()
        val ratio = Math.pow(2.0, semitones / 12.0)
        // Expand to `length * ratio` at unchanged pitch, then read back at
        // `ratio` samples per output sample: the duration returns to the
        // original while the pitch ends up multiplied by `ratio`.
        val stretched = stretcher.stretch(input, 1.0 / ratio)
        val out = FloatArray(input.size)
        Resampler.render(stretched, 0.0, ratio, out)
        return out
    }
}
