package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A segment that loops cleanly.
 *
 * @param startSeconds where the loop begins — always on a bar line.
 * @param bars length in bars, so a loop is always musically whole.
 * @param score 0..1 self-similarity between this segment and the one following
 *   it. High means the material genuinely repeats, so looping it will not lurch.
 */
data class LoopCandidate(
    val startSeconds: Double,
    val bars: Int,
    val durationSeconds: Double,
    val score: Float,
) {
    val endSeconds: Double get() = startSeconds + durationSeconds
}

/**
 * Somewhere in the track worth marking.
 *
 * @param timeSeconds when, snapped to a bar line where a grid is available.
 * @param strength 0..1, how pronounced the event is.
 */
data class PointOfInterest(
    val timeSeconds: Double,
    val kind: Kind,
    val strength: Float,
) {
    enum class Kind(val label: String) {
        /** Energy returns hard after a lull — the moment the track kicks back in. */
        DROP("Drop"),
        /** Energy falls away and stays down. */
        BREAKDOWN("Breakdown"),
        /** Energy climbing steadily toward something. */
        BUILD("Build"),
        /** A local high point. */
        PEAK("Peak"),

        /** Something starts singing. See [VocalDetector]. */
        VOCAL("Vocal"),
    }
}

/**
 * Finds loopable segments and structural landmarks in a track.
 *
 * Both were asked for — "an automatic loop maker, which samples loops from the
 * active playlist's songs", and "tag other elements of possible interest in the
 * song" — and neither existed.
 *
 * Loops are found by **self-similarity**: a segment loops well if what follows it
 * closely resembles it, because that is precisely the transition a loop makes
 * when it wraps. Candidates are constrained to whole bars on bar lines, so a loop
 * is always musically whole rather than an arbitrary slice that happens to
 * correlate.
 *
 * Pure functions over decoded audio and a measured grid, so both are testable.
 */
class StructureFinder(
    /** Feature-frame length in seconds. 50 ms is fine enough for structure, cheap enough to scan. */
    private val featureSeconds: Double = 0.05,
) {

    /**
     * Finds segments that loop cleanly.
     *
     * @param mono decoded mono audio.
     * @param grid the measured beat grid; loops are aligned to its bars.
     * @param barCounts loop lengths to consider, in bars.
     * @param limit how many candidates to return.
     * @return candidates by descending score, none overlapping.
     */
    fun findLoops(
        mono: FloatArray,
        sampleRate: Int,
        grid: BeatGrid,
        barCounts: List<Int> = listOf(4, 8, 16),
        limit: Int = 8,
        minimumScore: Float = 0.5f,
    ): List<LoopCandidate> {
        if (mono.isEmpty() || sampleRate <= 0) return emptyList()
        val features = rmsFeatures(mono, sampleRate)
        if (features.size < 8) return emptyList()

        val featureRate = 1.0 / featureSeconds
        val durationSeconds = mono.size.toDouble() / sampleRate
        val barDuration = grid.barDuration
        if (barDuration <= 0.0) return emptyList()

        val candidates = ArrayList<LoopCandidate>()

        for (bars in barCounts) {
            val loopSeconds = barDuration * bars
            val loopFrames = (loopSeconds * featureRate).toInt()
            if (loopFrames < 4) continue

            // Walk bar lines, comparing each segment with the one after it.
            var barIndex = 0
            while (true) {
                val start = grid.beatTime(grid.downbeatOffset) + barIndex * barDuration
                barIndex++
                if (start < 0.0) continue
                // Both this segment and its successor must fit inside the track.
                if (start + loopSeconds * 2 > durationSeconds) break

                val startFrame = (start * featureRate).toInt()
                val score = similarity(features, startFrame, startFrame + loopFrames, loopFrames)
                if (score >= minimumScore) {
                    candidates.add(LoopCandidate(start, bars, loopSeconds, score))
                }
            }
        }

        // Prefer the strongest, and never return two loops covering the same music.
        val chosen = ArrayList<LoopCandidate>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= limit) break
            val overlaps = chosen.any { existing ->
                candidate.startSeconds < existing.endSeconds && existing.startSeconds < candidate.endSeconds
            }
            if (!overlaps) chosen.add(candidate)
        }
        return chosen.sortedBy { it.startSeconds }
    }

    /**
     * Tags structural landmarks from the measured energy.
     *
     * The detection itself lives in [StructureSegmenter], which is the seam a
     * learned model would slot into. This stays as the way the rest of the app
     * asks the question, so callers do not each have to know which segmenter is
     * current.
     *
     * @param grid optional; when present, points snap to bar lines, because a cue
     *   marker half a beat off is worse than useless.
     * @param bands per-band energy. Strongly preferred — with it, drops, builds
     *   and breakdowns are separate measurements rather than three guesses at the
     *   slope of one curve. Null falls back to the broadband detector, which is
     *   what a cached analysis from an earlier version can offer.
     */
    fun findPointsOfInterest(
        energy: EnergyCurve,
        grid: BeatGrid? = null,
        limit: Int = 12,
        bands: BandEnergyCurve? = null,
        segmenter: StructureSegmenter = SpectralSegmenter(),
    ): List<PointOfInterest> =
        segmenter.segment(StructureInput(energy = energy, bands = bands, grid = grid, limit = limit))

    /** Root-mean-square level per feature frame — cheap, and enough to see structure. */
    private fun rmsFeatures(mono: FloatArray, sampleRate: Int): FloatArray {
        val frameLength = max(1, (featureSeconds * sampleRate).toInt())
        val count = mono.size / frameLength
        if (count <= 0) return FloatArray(0)
        return FloatArray(count) { frame ->
            var sum = 0.0
            val from = frame * frameLength
            for (i in from until from + frameLength) sum += mono[i].toDouble() * mono[i]
            sqrt(sum / frameLength).toFloat()
        }
    }

    /**
     * Similarity between two equal-length runs of features, as a Pearson
     * correlation of their *variation* around their own means.
     *
     * Mean-centring is what makes this measure musical rather than merely
     * loudness-matching. Correlating the raw levels scores any two passages of
     * the same loudness near 1 — white noise against different white noise came
     * out above 0.95, so a steady track would appear to loop everywhere. After
     * centring, only material whose envelope genuinely rises and falls the same
     * way correlates, which is the property a loop actually needs.
     *
     * Returns 0 when either run is flat or silent, so silence never looks like a
     * perfect loop — which it otherwise would, being identical to the silence
     * after it.
     */
    private fun similarity(features: FloatArray, aStart: Int, bStart: Int, length: Int): Float {
        if (aStart < 0 || bStart < 0 || length <= 1) return 0f
        if (aStart + length > features.size || bStart + length > features.size) return 0f

        var aMean = 0.0
        var bMean = 0.0
        for (i in 0 until length) {
            aMean += features[aStart + i]
            bMean += features[bStart + i]
        }
        aMean /= length
        bMean /= length

        var covariance = 0.0
        var aVariance = 0.0
        var bVariance = 0.0
        for (i in 0 until length) {
            val a = features[aStart + i] - aMean
            val b = features[bStart + i] - bMean
            covariance += a * b
            aVariance += a * a
            bVariance += b * b
        }
        if (aVariance < 1e-12 || bVariance < 1e-12) return 0f
        return (covariance / sqrt(aVariance * bVariance)).toFloat().coerceIn(0f, 1f)
    }

    private fun smooth(values: FloatArray, radius: Int): FloatArray =
        FloatArray(values.size) { i ->
            var sum = 0f
            var count = 0
            for (j in max(0, i - radius)..min(values.size - 1, i + radius)) {
                sum += values[j]
                count++
            }
            sum / count
        }

    private fun strength(magnitude: Float, spread: Float): Float =
        (abs(magnitude) / (spread * 3f)).coerceIn(0f, 1f)
}
