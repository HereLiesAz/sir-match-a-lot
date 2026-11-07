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
        val coarsePhase = estimatePhase(x, refinedLag)

        // The period above is good to a fraction of a percent, which is seconds
        // of drift over a whole track. Fit a line through where the beats
        // actually fall to get it to a few hundredths of a BPM.
        val (period, phase) = refineGrid(x, refinedLag, coarsePhase)
        val bpm = envelope.frameRate * 60.0 / period

        return TempoEstimate(
            bpm = bpm,
            firstBeatSeconds = phase / envelope.frameRate,
            confidence = confidenceOf(scores, minLag, maxLag, bestScore),
        )
    }

    /**
     * Refines a (period, phase) estimate, both in frames, by least squares over the
     * beats themselves.
     *
     * For each expected beat the strongest onset within a window is located to a
     * fraction of a frame; a weighted line `position = phase + k * period` is fitted
     * through those, and the fit is repeated with a tighter window around the new
     * line. Weighting by onset strength makes the clear beats decide it and the
     * ones that missed barely count. Over hundreds of beats the quantisation of
     * each peak averages out, which is what turns a fraction of a percent into a
     * few hundredths of a BPM.
     *
     * Falls back to the input when there are too few beats to fit, or when the fit
     * moves the period by more than 2% — a refinement that large has found a
     * different pulse, not a better measurement of this one.
     */
    private fun refineGrid(envelope: FloatArray, period: Double, phase: Double): Pair<Double, Double> {
        var a = phase
        var b = period
        var half = b / 4.0
        // The coarse period is off by up to about half a percent, which over a few
        // hundred beats is more than a beat of drift — so a window around the
        // *coarse* line has lost the beat by the end of the track. Fit the first
        // few beats first, where the drift is still inside the window, then use
        // that better line to reach further, and so on until the whole track is in.
        var limit = INITIAL_BEATS
        var wholeTrackPasses = 0

        for (pass in 0 until MAX_REFINE_PASSES) {
            var sumW = 0.0
            var sumK = 0.0
            var sumP = 0.0
            var sumKK = 0.0
            var sumKP = 0.0
            var beats = 0

            var k = 0
            while (k < limit) {
                val expected = a + k * b
                if (expected >= envelope.size - 1) break
                val lo = Math.ceil(expected - half).toInt().coerceAtLeast(1)
                val hi = Math.floor(expected + half).toInt().coerceAtMost(envelope.size - 2)
                if (hi >= lo) {
                    var peak = lo
                    for (i in lo..hi) if (envelope[i] > envelope[peak]) peak = i
                    val centre = envelope[peak]
                    if (centre > 0f) {
                        val left = envelope[peak - 1]
                        val right = envelope[peak + 1]
                        val denominator = left - 2 * centre + right
                        val sub = if (abs(denominator) > 1e-12f) (0.5f * (left - right) / denominator).toDouble().coerceIn(-0.5, 0.5) else 0.0
                        val position = peak + sub
                        val w = centre.toDouble()
                        sumW += w
                        sumK += w * k
                        sumP += w * position
                        sumKK += w * k * k
                        sumKP += w * k * position
                        beats++
                    }
                }
                k++
            }

            if (beats < MIN_BEATS_TO_REFINE) break
            val denominator = sumW * sumKK - sumK * sumK
            if (abs(denominator) < 1e-9) break
            val newPeriod = (sumW * sumKP - sumK * sumP) / denominator
            val newPhase = (sumP - newPeriod * sumK) / sumW
            if (abs(newPeriod - period) > period * MAX_REFINE_SHIFT) return period to phase

            b = newPeriod
            a = newPhase

            val totalBeats = ((envelope.size - 1 - a) / b).toInt()
            if (limit >= totalBeats) {
                // Every beat is in. Two more passes with a tight window settle it.
                half = kotlin.math.max(1.0, b / 8.0)
                if (++wholeTrackPasses >= 2) break
            } else {
                limit = kotlin.math.min(totalBeats, (limit * BEAT_GROWTH).toInt())
            }
        }

        // A phase within the first beat, as the caller expects.
        var normalised = a % b
        if (normalised < 0) normalised += b
        return b to normalised
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
        // Only the first few dozen beats. The period is still half a percent off
        // at this point, and a pulse train spaced by it slides across a whole beat
        // over a few hundred, so summing the full track blurs the phase into
        // whichever offset the drift happens to favour.
        val limit = minOf(envelope.size.toDouble(), period * PHASE_BEATS)

        for (offset in 0 until candidates) {
            var sum = 0.0
            var position = offset.toDouble()
            while (position < limit) {
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

    /**
     * How far the winner stands above the field, in standard deviations of the
     * candidate scores — then squashed to 0..1.
     *
     * This used to be `(bestScore - mean) / (bestScore + mean)`, hard-clamped to
     * 0 whenever the mean score was not positive. Autocorrelation scores swing
     * negative at anti-phase lags as a matter of course, so for many genuinely
     * sharp, correct periodicities the mean of all candidate lags landed at or
     * below zero — and the winner's confidence was reported as exactly 0 no
     * matter how far it stood out. Verified against the detector's own click
     * track fixture: 90, 100, 110, 140 and 174 BPM all measured the correct
     * tempo with the old formula reporting confidence 0.0, while unperiodic
     * input (white noise, a sustained tone, a static chord) also reported 0.0 —
     * making the figure useless for telling a real tempo from noise, which is
     * the one job it exists to do.
     *
     * A z-score does not have that sign problem: it measures the winner's
     * distance from the field in units of the field's own spread, whichever way
     * the mean sits. `z / (z + Z_HALF)` maps that unbounded distance onto 0..1
     * with a smooth knee, rather than a raw sigmoid whose scale would need
     * separate tuning.
     */
    private fun confidenceOf(scores: DoubleArray, minLag: Int, maxLag: Int, bestScore: Double): Float {
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (lag in minLag..maxLag) {
            val s = scores[lag]
            sum += s
            sumSquares += s * s
            count++
        }
        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
        val stdDev = kotlin.math.sqrt(variance)
        if (stdDev <= 1e-9) return 0f
        val z = ((bestScore - mean) / stdDev).coerceAtLeast(0.0)
        return (z / (z + Z_HALF_CONFIDENCE)).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        /** z-score at which confidence reads 0.5 — see [confidenceOf]. */
        private const val Z_HALF_CONFIDENCE = 2.2

        /** Upper bound on least-squares passes over the beats. */
        private const val MAX_REFINE_PASSES = 16

        /** Beats the coarse phase is read from; see [estimatePhase]. */
        private const val PHASE_BEATS = 32.0

        /** Beats the first fit uses, few enough that the coarse period has not drifted off them. */
        private const val INITIAL_BEATS = 16

        /** How much further each fit reaches than the last. */
        private const val BEAT_GROWTH = 2.5

        /** Fewer beats than this is not enough to fit a line through. */
        private const val MIN_BEATS_TO_REFINE = 8

        /** A refinement that moves the period by more than this found another pulse. */
        private const val MAX_REFINE_SHIFT = 0.02
    }
}
