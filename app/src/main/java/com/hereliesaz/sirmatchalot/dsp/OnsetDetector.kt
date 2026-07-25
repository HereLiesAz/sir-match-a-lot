package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.ln
import kotlin.math.max

/**
 * An onset-strength envelope: one value per STFT frame, large where the
 * spectrum is changing abruptly (a drum hit, a note attack).
 *
 * This is the substrate for tempo detection. Everything downstream —  BPM, beat
 * phase, the beat grid, loop boundaries — is measured from this signal rather
 * than guessed from a filename.
 *
 * @param values onset strength per frame, always non-negative.
 * @param frameRate frames per second.
 */
class OnsetEnvelope(
    val values: FloatArray,
    val frameRate: Double,
) {
    val size: Int get() = values.size

    /** Time in seconds of frame [index]. */
    fun timeOf(index: Int): Double = index / frameRate

    /** Frame index nearest to [seconds]. */
    fun frameOf(seconds: Double): Int = (seconds * frameRate).toInt()
}

/**
 * Computes onset strength by half-wave-rectified spectral flux on a
 * logarithmically compressed magnitude spectrum.
 *
 * Log compression matters: without it, flux is dominated by whichever frames are
 * merely loud, so a track with a heavy sustained bass reads as continuous onset
 * and the tempo estimate collapses. Compression makes the measure respond to
 * relative change instead.
 */
class OnsetDetector(
    private val stft: Stft = Stft(frameSize = 1024, hop = 512),
) {
    /**
     * @param samples mono PCM.
     * @param sampleRate sample rate of [samples].
     */
    fun detect(samples: FloatArray, sampleRate: Int): OnsetEnvelope {
        val frames = stft.frameCount(samples.size)
        val frameRate = stft.frameRate(sampleRate)
        if (frames <= 1) return OnsetEnvelope(FloatArray(0), frameRate)

        val bins = stft.bins
        val previous = FloatArray(bins)
        val flux = FloatArray(frames)

        // Ignore the lowest bins (rumble/DC) and everything above ~10 kHz,
        // where cymbal noise adds flux without marking beats.
        val lowBin = 1
        val highBin = bins.coerceAtMost(
            ((10_000.0 * stft.frameSize) / sampleRate).toInt().coerceAtLeast(lowBin + 1)
        )

        stft.forEachFrame(samples) { frame, magnitudes ->
            var sum = 0f
            for (k in lowBin until highBin) {
                val compressed = ln(1f + magnitudes[k])
                sum += max(0f, compressed - previous[k])
                previous[k] = compressed
            }
            flux[frame] = sum
        }

        return OnsetEnvelope(normalise(subtractMovingAverage(flux, frameRate)), frameRate)
    }

    /**
     * Subtracts a local mean so that a loud chorus does not outweigh a quiet
     * verse when the envelope is later correlated against itself.
     */
    private fun subtractMovingAverage(flux: FloatArray, frameRate: Double): FloatArray {
        // ~0.5 s of context either side.
        val radius = (frameRate * 0.5).toInt().coerceAtLeast(1)
        val out = FloatArray(flux.size)
        var runningSum = 0.0
        var windowStart = 0
        var windowEnd = -1

        for (i in flux.indices) {
            val lo = (i - radius).coerceAtLeast(0)
            val hi = (i + radius).coerceAtMost(flux.size - 1)
            while (windowEnd < hi) { windowEnd++; runningSum += flux[windowEnd] }
            while (windowStart < lo) { runningSum -= flux[windowStart]; windowStart++ }
            val mean = runningSum / (windowEnd - windowStart + 1)
            out[i] = max(0f, flux[i] - mean.toFloat())
        }
        return out
    }

    private fun normalise(values: FloatArray): FloatArray {
        var peak = 0f
        for (v in values) if (v > peak) peak = v
        if (peak <= 0f) return values
        for (i in values.indices) values[i] /= peak
        return values
    }
}
