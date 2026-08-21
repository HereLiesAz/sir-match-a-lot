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

    /**
     * Serialised as the window length followed by the values, little-endian.
     *
     * The window length has to travel with the values: without it a curve read
     * back cannot answer [at], and a caller would have to guess the analyser's
     * configuration. The same mistake in [PeakEnvelope] is harmless only because
     * its buckets are a fraction of the track rather than a fixed duration.
     */
    fun toByteArray(): ByteArray {
        val buffer = java.nio.ByteBuffer
            .allocate(8 + values.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putDouble(windowSeconds)
        for (value in values) buffer.putFloat(value)
        return buffer.array()
    }

    companion object {
        private const val SILENCE_DB = -60.0

        /** Reads back [toByteArray]. Returns null for anything too short to be one. */
        fun fromByteArray(bytes: ByteArray): EnergyCurve? {
            if (bytes.size < 8) return null
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val windowSeconds = buffer.double
            if (windowSeconds <= 0.0 || !windowSeconds.isFinite()) return null
            val count = (bytes.size - 8) / 4
            return EnergyCurve(FloatArray(count) { buffer.float }, windowSeconds)
        }

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
                // Loudness is measured directly from the time-domain samples of
                // this frame, in dBFS relative to full scale (±1.0) — not from
                // the FFT magnitude sum, whose scale depends on frame size and
                // window function and has no fixed relationship to the sample
                // amplitude. Using the raw magnitude sum as if it were dBFS put
                // 0 dB at roughly -26 dBFS of real signal (a Hann-windowed
                // 1024-point sum inflates RMS by ~19.6x), so every real,
                // mastered track (-14..-6 dBFS) saturated the [0,1] loudness
                // term at the same value: every track came out "equally
                // energetic".
                val offset = frame * stft.hop
                var sumSquares = 0.0
                var sampleCount = 0
                for (i in 0 until stft.frameSize) {
                    val s = offset + i
                    if (s >= samples.size) break
                    val v = samples[s].toDouble()
                    sumSquares += v * v
                    sampleCount++
                }
                loudness[window] += if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0

                var weighted = 0.0
                var total = 0.0
                for (k in 1 until bins) {
                    val magnitude = magnitudes[k].toDouble()
                    weighted += magnitude * stft.binFrequency(k, sampleRate)
                    total += magnitude
                }
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
