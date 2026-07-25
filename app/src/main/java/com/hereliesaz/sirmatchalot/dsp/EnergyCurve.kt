package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Perceived energy over time, in 0..1 — the signal behind the energy ring drawn
 * around the platter and behind "high energy" track selection.
 *
 * Energy is not just loudness: a quiet track with bright, busy high frequencies
 * reads as more energetic than a loud sustained pad. So this combines loudness
 * (RMS in dB, which matches perception far better than peak amplitude) with
 * spectral centroid (where the spectrum's centre of mass sits).
 *
 * @param values energy per window, 0..1.
 * @param windowSeconds duration each value covers.
 */
class EnergyCurve(
    val values: FloatArray,
    val windowSeconds: Double,
) {
    val size: Int get() = values.size

    /** Energy at [seconds], clamped to the ends. */
    fun at(seconds: Double): Float {
        if (values.isEmpty()) return 0f
        val index = (seconds / windowSeconds).toInt().coerceIn(0, values.size - 1)
        return values[index]
    }

    /** Mean energy, the single 1..10 figure a library row can show. */
    fun average(): Float = if (values.isEmpty()) 0f else values.average().toFloat()

    /** Mean energy mapped to the 1..10 scale used by the library UI. */
    fun energyLevel(): Int = (average() * 9f + 1f).toInt().coerceIn(1, 10)

    companion object {
        private const val SILENCE_DB = -60.0

        /**
         * @param windowSeconds analysis window; ~0.5 s tracks build-ups and
         *   drops without reacting to individual drum hits.
         */
        fun compute(
            samples: FloatArray,
            sampleRate: Int,
            windowSeconds: Double = 0.5,
        ): EnergyCurve {
            require(windowSeconds > 0) { "windowSeconds must be positive" }
            if (samples.isEmpty()) return EnergyCurve(FloatArray(0), windowSeconds)

            val stft = Stft(frameSize = 1024, hop = 512)
            val framesPerWindow = (windowSeconds * stft.frameRate(sampleRate))
                .toInt().coerceAtLeast(1)
            val totalFrames = stft.frameCount(samples.size)
            if (totalFrames == 0) return EnergyCurve(FloatArray(0), windowSeconds)

            val windowCount = (totalFrames + framesPerWindow - 1) / framesPerWindow
            val loudness = DoubleArray(windowCount)
            val brightness = DoubleArray(windowCount)
            val counts = IntArray(windowCount)

            val bins = stft.bins
            stft.forEachFrame(samples) { frame, magnitudes ->
                val window = frame / framesPerWindow
                var power = 0.0
                var weighted = 0.0
                var total = 0.0
                for (k in 1 until bins) {
                    val magnitude = magnitudes[k].toDouble()
                    power += magnitude * magnitude
                    weighted += magnitude * stft.binFrequency(k, sampleRate)
                    total += magnitude
                }
                loudness[window] += sqrt(power / bins)
                brightness[window] += if (total > 0.0) weighted / total else 0.0
                counts[window]++
            }

            // Nyquist is the ceiling for centroid, so normalise against it.
            val nyquist = sampleRate / 2.0
            val values = FloatArray(windowCount)
            for (i in 0 until windowCount) {
                val n = counts[i].coerceAtLeast(1)
                val rms = loudness[i] / n
                val db = if (rms > 0) 20.0 * log10(rms) else SILENCE_DB
                val loudnessTerm = ((db - SILENCE_DB) / -SILENCE_DB).coerceIn(0.0, 1.0)
                val centroid = (brightness[i] / n / nyquist).coerceIn(0.0, 1.0)
                // Loudness dominates; brightness modulates.
                values[i] = (loudnessTerm * 0.75 + centroid * 0.25).coerceIn(0.0, 1.0).toFloat()
            }
            return EnergyCurve(values, windowSeconds)
        }
    }
}
