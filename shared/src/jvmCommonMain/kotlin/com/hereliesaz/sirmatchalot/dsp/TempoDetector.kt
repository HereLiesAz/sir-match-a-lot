package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A measured tempo.
 *
 * @param bpm beats per minute.
 * @param firstBeatSeconds time of the first beat, so a grid can be laid down
 *   with the right phase rather than assumed to start at zero.
 * @param confidence 0..1; how much the winning period stood out from the field.
 */
data class TempoEstimate(
    val bpm: Double,
    val firstBeatSeconds: Double,
    val confidence: Float,
)

/**
 * Estimates tempo and beat phase from an [OnsetEnvelope] by autocorrelation.
 *
 * Two details do most of the work:
 *
 * - **Harmonic reinforcement.** The score for a candidate period sums the
 *   autocorrelation at that period *and* its multiples, so a genuine beat period
 *   outscores its own subdivisions (which correlate well at one lag but not at
 *   2x and 3x).
 * - **A log-normal tempo prior** centred at 120 BPM. Autocorrelation cannot
 *   distinguish a tempo from double or half of it; this is the standard way to
 *   break that tie, and it is why a 170 BPM drum-and-bass track reports 170
 *   rather than 85.
 */
class TempoDetector(
    private val minBpm: Double = 60.0,
    private val maxBpm: Double = 200.0,
    private val priorCentreBpm: Double = 120.0,
    private val priorWidthOctaves: Double = 0.8,
) {
    init {
        require(minBpm > 0 && maxBpm > minBpm) { "invalid BPM range $minBpm..$maxBpm" }
    }

    fun detect(envelope: OnsetEnvelope): TempoEstimate? {
        val x = envelope.values
        if (x.size < 8) return null

        val minLag = (envelope.frameRate * 60.0 / maxBpm).toInt().coerceAtLeast(1)
        val maxLag = (envelope.frameRate * 60.0 / minBpm).toInt()
        if (maxLag <= minLag || maxLag >= x.size) return null

        // Mean-remove so the autocorrelation measures periodicity rather than
        // the envelope's DC level.
        val mean = x.average().toFloat()
        val centred = FloatArray(x.size) { x[it] - mean }

        val zeroLag = autocorrelation(centred, 0)
        if (zeroLag <= 0.0) return null

        val scores = DoubleArray(maxLag + 1)
        var best = minLag
        var bestScore = -Double.MAX_VALUE

        for (lag in minLag..maxLag) {
            var score = autocorrelation(centred, lag) / zeroLag
            var weight = 1.0
            for (multiple in 2..3) {
                val harmonicLag = lag * multiple
                if (harmonicLag >= x.size) break
                val harmonicWeight = 1.0 / multiple
                score += harmonicWeight * autocorrelation(centred, harmonicLag) / zeroLag
                weight += harmonicWeight
            }
            score /= weight

            val bpm = envelope.frameRate * 60.0 / lag
            score *= tempoPrior(bpm)

            scores[lag] = score
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }

        val refinedLag = parabolicRefine(scores, best, minLag, maxLag)
        val bpm = envelope.frameRate * 60.0 / refinedLag

        return TempoEstimate(
            bpm = bpm,
            firstBeatSeconds = estimatePhase(x, refinedLag) / envelope.frameRate,
            confidence = confidenceOf(scores, minLag, maxLag, bestScore),
        )
    }

    /** Sum of lagged products, normalised by overlap count so short lags are not favoured. */
    private fun autocorrelation(x: FloatArray, lag: Int): Double {
        val count = x.size - lag
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) sum += x[i].toDouble() * x[i + lag]
        return sum / count
    }

    /** Log-normal weight favouring tempi near [priorCentreBpm]. */
    private fun tempoPrior(bpm: Double): Double {
        val octaves = ln(bpm / priorCentreBpm) / ln(2.0)
        val z = octaves / priorWidthOctaves
        return exp(-0.5 * z * z)
    }

    /**
     * Sub-frame period refinement by fitting a parabola through the winning lag
     * and its neighbours — without this the BPM resolution at a 512-sample hop
     * is about 1.5 BPM at 130, which is too coarse to beat-match with.
     */
    private fun parabolicRefine(scores: DoubleArray, peak: Int, minLag: Int, maxLag: Int): Double {
        if (peak <= minLag || peak >= maxLag) return peak.toDouble()
        val left = scores[peak - 1]
        val centre = scores[peak]
        val right = scores[peak + 1]
        val denominator = left - 2 * centre + right
        if (abs(denominator) < 1e-12) return peak.toDouble()
        val delta = 0.5 * (left - right) / denominator
        return peak + delta.coerceIn(-0.5, 0.5)
    }

    /**
     * Finds the beat phase: the offset whose pulse train, spaced [period] frames
     * apart, collects the most onset strength.
     */
    private fun estimatePhase(envelope: FloatArray, period: Double): Double {
        val candidates = period.roundToInt().coerceAtLeast(1)
        var bestOffset = 0
        var bestSum = -Double.MAX_VALUE

        for (offset in 0 until candidates) {
            var sum = 0.0
            var position = offset.toDouble()
            while (position < envelope.size) {
                sum += envelope[position.toInt()]
                position += period
            }
            if (sum > bestSum) {
                bestSum = sum
                bestOffset = offset
            }
        }
        return bestOffset.toDouble()
    }

    /** How far the winner stood above the mean of all candidate scores. */
    private fun confidenceOf(scores: DoubleArray, minLag: Int, maxLag: Int, bestScore: Double): Float {
        var sum = 0.0
        var count = 0
        for (lag in minLag..maxLag) { sum += scores[lag]; count++ }
        if (count == 0) return 0f
        val mean = sum / count
        if (mean <= 0.0) return 0f
        return ((bestScore - mean) / (bestScore + mean)).coerceIn(0.0, 1.0).toFloat()
    }
}
