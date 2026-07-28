package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy in three bands over time, rather than one number.
 *
 * [EnergyCurve] collapses a spectrum into a single figure, which is the right
 * shape for "how energetic is this track" and the wrong shape for "what is
 * happening here". The events a DJ cares about are defined by which part of the
 * spectrum moved:
 *
 * - a **drop** is the low end coming back. Not the track getting louder — a
 *   riser gets louder too, and it is the opposite kind of moment;
 * - a **breakdown** is the low end going away while the mids and highs carry on.
 *   Broadband energy barely moves for some of them, which is why they were being
 *   missed;
 * - a **build** is the highs climbing while the low end stays out.
 *
 * On one broadband curve all three look like "energy changed", and the old
 * detector had to guess between them from the direction and a hand-tuned
 * threshold. With bands they are simply different measurements.
 *
 * @param low up to [LOW_HZ] — kick and bass.
 * @param mid up to [MID_HZ] — most of the music.
 * @param high above that — hats, air, risers.
 */
class BandEnergyCurve(
    val low: FloatArray,
    val mid: FloatArray,
    val high: FloatArray,
    val windowSeconds: Double,
) {
    val size: Int get() = low.size

    val isEmpty: Boolean get() = size == 0

    /** Seconds at which window [index] begins. */
    fun timeOf(index: Int): Double = index * windowSeconds

    /** Broadband energy at [index], for the events that really are broadband. */
    fun total(index: Int): Float =
        if (index !in 0 until size) 0f else (low[index] + mid[index] + high[index]) / 3f

    companion object {
        const val LOW_HZ = 200.0
        const val MID_HZ = 2_000.0

        private const val SILENCE_DB = -60.0

        /**
         * Measures the three bands.
         *
         * Deliberately the same window and the same STFT settings as
         * [EnergyCurve.compute], so a landmark found here lands on the same
         * timeline as the energy ring drawn around the platter. Two analyses of
         * the same track that disagree about where second ninety is are worse
         * than one.
         */
        fun compute(
            samples: FloatArray,
            sampleRate: Int,
            windowSeconds: Double = 0.5,
        ): BandEnergyCurve {
            require(windowSeconds > 0) { "windowSeconds must be positive" }
            val empty = BandEnergyCurve(FloatArray(0), FloatArray(0), FloatArray(0), windowSeconds)
            if (samples.isEmpty() || sampleRate <= 0) return empty

            val stft = Stft(frameSize = 1024, hop = 512)
            val framesPerWindow = (windowSeconds * stft.frameRate(sampleRate))
                .toInt().coerceAtLeast(1)
            val totalFrames = stft.frameCount(samples.size)
            if (totalFrames == 0) return empty

            val windowCount = (totalFrames + framesPerWindow - 1) / framesPerWindow
            val lowPower = DoubleArray(windowCount)
            val midPower = DoubleArray(windowCount)
            val highPower = DoubleArray(windowCount)
            val counts = IntArray(windowCount)

            val bins = stft.bins
            // Bin boundaries resolved once rather than a frequency conversion per
            // bin per frame — this runs over every frame of a whole track.
            val lowEnd = binAtOrBelow(LOW_HZ, stft, sampleRate, bins)
            val midEnd = binAtOrBelow(MID_HZ, stft, sampleRate, bins)

            stft.forEachFrame(samples) { frame, magnitudes ->
                val window = (frame / framesPerWindow).coerceAtMost(windowCount - 1)
                var lowSum = 0.0
                var midSum = 0.0
                var highSum = 0.0
                // From 1: bin 0 is DC, which is offset rather than music.
                for (k in 1 until bins) {
                    val magnitude = magnitudes[k].toDouble()
                    val power = magnitude * magnitude
                    when {
                        k <= lowEnd -> lowSum += power
                        k <= midEnd -> midSum += power
                        else -> highSum += power
                    }
                }
                lowPower[window] += sqrt(lowSum / bins)
                midPower[window] += sqrt(midSum / bins)
                highPower[window] += sqrt(highSum / bins)
                counts[window]++
            }

            fun normalise(power: DoubleArray) = FloatArray(windowCount) { i ->
                val rms = power[i] / counts[i].coerceAtLeast(1)
                val db = if (rms > 0) 20.0 * log10(rms) else SILENCE_DB
                ((db - SILENCE_DB) / -SILENCE_DB).coerceIn(0.0, 1.0).toFloat()
            }

            return BandEnergyCurve(
                low = normalise(lowPower),
                mid = normalise(midPower),
                high = normalise(highPower),
                windowSeconds = windowSeconds,
            )
        }

        private fun binAtOrBelow(hz: Double, stft: Stft, sampleRate: Int, bins: Int): Int {
            for (k in 1 until bins) {
                if (stft.binFrequency(k, sampleRate) > hz) return k - 1
            }
            return bins - 1
        }
    }
}
