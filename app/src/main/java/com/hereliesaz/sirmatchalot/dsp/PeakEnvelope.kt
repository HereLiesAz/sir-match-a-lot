package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A min/max peak envelope for drawing a waveform.
 *
 * Storing both extremes per bucket, rather than a single absolute maximum, is
 * what makes a drawn waveform look like a waveform: the asymmetry of real audio
 * is visible, and thin transients survive downsampling instead of averaging away
 * into the featureless band the previous implementation produced.
 *
 * @param minima per-bucket minimum sample, in -1..0.
 * @param maxima per-bucket maximum sample, in 0..1.
 */
class PeakEnvelope(
    val minima: FloatArray,
    val maxima: FloatArray,
) {
    init {
        require(minima.size == maxima.size) { "minima and maxima must be the same length" }
    }

    val size: Int get() = maxima.size

    /** Peak-to-peak height of bucket [index]. */
    fun heightAt(index: Int): Float {
        if (index < 0 || index >= size) return 0f
        return maxima[index] - minima[index]
    }

    /**
     * Peak-to-peak height at normalised position [position] in 0..1, linearly
     * interpolated — the platter needs a height at an arbitrary angle, not at a
     * bucket boundary.
     */
    fun heightAtFraction(position: Float): Float {
        if (size == 0) return 0f
        if (size == 1) return heightAt(0)
        val scaled = (position.coerceIn(0f, 1f) * (size - 1))
        val i = scaled.toInt()
        val t = scaled - i
        val a = heightAt(i)
        val b = heightAt(min(i + 1, size - 1))
        return a + (b - a) * t
    }

    /**
     * Re-buckets this envelope to [targetSize], preserving extremes.
     *
     * The platter redraws at whatever angular resolution the screen gives it, so
     * one stored high-resolution envelope is reduced on demand rather than
     * re-decoding the file.
     */
    fun resample(targetSize: Int): PeakEnvelope {
        require(targetSize > 0) { "targetSize must be positive" }
        if (targetSize == size) return this
        val newMin = FloatArray(targetSize)
        val newMax = FloatArray(targetSize)
        for (i in 0 until targetSize) {
            val start = (i.toLong() * size / targetSize).toInt()
            val end = (((i + 1).toLong() * size / targetSize).toInt()).coerceAtLeast(start + 1)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (j in start until min(end, size)) {
                lo = min(lo, minima[j])
                hi = max(hi, maxima[j])
            }
            newMin[i] = if (lo == Float.MAX_VALUE) 0f else lo
            newMax[i] = if (hi == -Float.MAX_VALUE) 0f else hi
        }
        return PeakEnvelope(newMin, newMax)
    }

    /** Flattens to a little-endian float pair stream for on-disk storage. */
    fun toByteArray(): ByteArray {
        val buffer = java.nio.ByteBuffer
            .allocate(size * 2 * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size) {
            buffer.putFloat(minima[i])
            buffer.putFloat(maxima[i])
        }
        return buffer.array()
    }

    companion object {
        /**
         * Reduces [samples] to [buckets] min/max pairs.
         *
         * @param buckets target resolution; 2048 is enough for a full-screen
         *   circular waveform and costs 16 KB per track.
         */
        fun compute(samples: FloatArray, buckets: Int = 2048): PeakEnvelope {
            require(buckets > 0) { "buckets must be positive" }
            if (samples.isEmpty()) return PeakEnvelope(FloatArray(buckets), FloatArray(buckets))

            val minima = FloatArray(buckets)
            val maxima = FloatArray(buckets)
            for (b in 0 until buckets) {
                val start = (b.toLong() * samples.size / buckets).toInt()
                val end = (((b + 1).toLong() * samples.size / buckets).toInt())
                    .coerceAtMost(samples.size)
                if (start >= end) {
                    // More buckets than samples: hold the nearest value.
                    val v = samples[start.coerceAtMost(samples.size - 1)]
                    minima[b] = min(0f, v)
                    maxima[b] = max(0f, v)
                    continue
                }
                var lo = 0f
                var hi = 0f
                for (i in start until end) {
                    val v = samples[i]
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                minima[b] = lo
                maxima[b] = hi
            }
            return PeakEnvelope(minima, maxima)
        }

        /** Reads back [toByteArray]. */
        fun fromByteArray(bytes: ByteArray): PeakEnvelope {
            val pairs = bytes.size / 8
            val buffer = java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val minima = FloatArray(pairs)
            val maxima = FloatArray(pairs)
            for (i in 0 until pairs) {
                minima[i] = buffer.float
                maxima[i] = buffer.float
            }
            return PeakEnvelope(minima, maxima)
        }

        /**
         * Finds the first and last sample indices whose local peak exceeds
         * [threshold] — the silence-trim bounds, computed from the same envelope
         * rather than by a second pass over the file.
         *
         * @return the inclusive range, or null if the whole buffer is silent.
         */
        fun contentBounds(
            samples: FloatArray,
            threshold: Float = 0.02f,
            windowSize: Int = 1024,
        ): IntRange? {
            if (samples.isEmpty()) return null
            var first = -1
            var last = -1
            var i = 0
            while (i < samples.size) {
                val end = min(i + windowSize, samples.size)
                var peak = 0f
                for (j in i until end) {
                    val v = abs(samples[j])
                    if (v > peak) peak = v
                }
                if (peak > threshold) {
                    if (first < 0) first = i
                    last = end - 1
                }
                i = end
            }
            return if (first < 0) null else first..last
        }
    }
}
