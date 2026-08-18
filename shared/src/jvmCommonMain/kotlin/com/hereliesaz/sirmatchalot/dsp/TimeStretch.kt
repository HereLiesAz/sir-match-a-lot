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

        // The configured frame does not have to fit for this to still do a
        // genuine, pitch-preserving stretch — only *some* even frame at least
        // MIN_FRAME_SIZE long has to. Frame, overlap, window and search
        // window are all scaled down together to whatever the shorter of
        // input and output actually admits.
        //
        // This used to fall back to a plain `Resampler.render(input, 0.0,
        // ratio, out)` below that threshold, which changes pitch and duration
        // together rather than duration alone — exactly what a naive resample
        // does, and exactly what [PitchShifter.shift] does *again*
        // immediately afterward at the inverse rate to undo the duration
        // change. The two cancelled: a pitch shift on any buffer under one
        // configured frame (46-93 ms, depending on sample rate) came back
        // bit-for-bit unshifted, silently.
        val maxFit = minOf(input.size, outputLength)
        if (maxFit < MIN_FRAME_SIZE) {
            // A couple of milliseconds or less — too short for any windowed
            // overlap-add to mean anything at all. This is the one case left
            // that still just resamples, changing pitch and duration
            // together; there is no frame-based alternative to reach for.
            val out = FloatArray(outputLength)
            Resampler.render(input, 0.0, ratio, out)
            return out
        }
        val effectiveFrameSize = (maxFit / 2 * 2).coerceIn(MIN_FRAME_SIZE, frameSize)
        val effectiveOverlap = effectiveFrameSize / 2
        val effectiveWindow = if (effectiveFrameSize == frameSize) window else Window.hann(effectiveFrameSize)
        val effectiveSeekWindow = minOf(seekWindow, effectiveOverlap)

        val output = FloatArray(outputLength)
        val analysisHop = effectiveOverlap * ratio

        var outPos = 0
        var nominal = 0.0
        // Where the previously emitted frame would naturally have continued.
        var continuation = -1
        while (outPos + effectiveFrameSize <= outputLength) {
            val nominalStart = nominal.roundToInt()
            val start =
                if (continuation < 0) nominalStart
                else bestMatch(input, nominalStart, continuation, effectiveOverlap, effectiveSeekWindow)

            for (i in 0 until effectiveFrameSize) {
                val s = start + i
                if (s < 0 || s >= input.size) continue
                output[outPos + i] += input[s] * effectiveWindow[i]
            }

            continuation = start + effectiveOverlap
            outPos += effectiveOverlap
            nominal += analysisHop
        }

        // Head and tail: the first and last frames have no neighbour to overlap
        // with, so the window's own ramp is the whole gain there — a fade in and
        // a fade out that the constant-overlap-add condition never promised.
        //
        // Both are completed rather than replaced: each output sample in those
        // regions already holds `x * w`, so adding `x * (1 - w)` brings it to
        // exactly `x` and leaves the interior untouched.
        //
        // The tail used to add the raw source on top of the last frame's
        // ramp-down instead, which reaches `2x` — a +6 dB step in a single
        // sample, 23 ms before the end of every stretched or pitch-shifted
        // track, straight into the limiter.
        for (i in 0 until minOf(effectiveOverlap, outputLength)) {
            if (i < input.size) output[i] += input[i] * (1f - effectiveWindow[i])
        }
        if (outPos < outputLength && continuation >= 0) {
            var s = continuation
            for (i in outPos until outputLength) {
                val inLastFrame = effectiveOverlap + (i - outPos)
                val remaining = if (inLastFrame < effectiveFrameSize) 1f - effectiveWindow[inLastFrame] else 1f
                output[i] += (if (s in input.indices) input[s] else 0f) * remaining
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
    private fun bestMatch(input: FloatArray, nominal: Int, target: Int, overlap: Int, seekWindow: Int): Int {
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

    private companion object {
        const val MIN_FRAME_SIZE = 64
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
