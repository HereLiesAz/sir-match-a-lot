package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Everything a segmenter is given about a track.
 *
 * @param bands per-band energy, where available. Null falls the segmenter back
 *   to [energy] alone, which is what an older cached analysis provides.
 * @param grid the measured beat grid; landmarks snap to its bars.
 */
data class StructureInput(
    val energy: EnergyCurve,
    val bands: BandEnergyCurve? = null,
    val grid: BeatGrid? = null,
    val limit: Int = 12,
)

/**
 * Finds the moments in a track worth naming.
 *
 * An interface because this is the seam where a learned model belongs. Beat and
 * downbeat tracking and structural segmentation are the two places in this app
 * where a small trained network genuinely beats signal processing, and the
 * choreographer's whole vocabulary — where to leave a track, where to join it —
 * is built on the answers. Getting a model in later should be a new
 * implementation of this, not surgery on everything that consumes it.
 *
 * Until then the implementations here are classical, and one of them is a good
 * deal better than the other.
 */
fun interface StructureSegmenter {
    fun segment(input: StructureInput): List<PointOfInterest>
}

/**
 * Classifies landmarks by **which part of the spectrum moved**.
 *
 * The previous detector — [BroadbandSegmenter], kept below — had one smoothed
 * loudness curve and an if/else chain over the direction of its slope. That
 * cannot distinguish the three events that matter most, because on a broadband
 * curve they look alike:
 *
 * | | broadband | low | high |
 * |---|---|---|---|
 * | drop | rises | **returns hard** | steady |
 * | build | rises | **stays out** | climbs |
 * | breakdown | barely moves on some tracks | **falls away** | carries on |
 *
 * A riser into a drop reads as one long rise to a broadband detector, so the
 * build and the drop it leads to were routinely collapsed into a single
 * mislabelled point — and the drop is precisely the thing the choreographer cuts
 * onto. Reading the bands separately makes them different measurements rather
 * than different guesses.
 */
class SpectralSegmenter : StructureSegmenter {

    override fun segment(input: StructureInput): List<PointOfInterest> {
        val bands = input.bands
        if (bands == null || bands.size < MINIMUM_WINDOWS) {
            return BroadbandSegmenter().segment(input)
        }

        val low = smooth(bands.low)
        val mid = smooth(bands.mid)
        val high = smooth(bands.high)
        val total = FloatArray(bands.size) { bands.total(it) }
        val lowSpread = spread(low)
        val highSpread = spread(high)
        val totalSpread = spread(total)
        if (lowSpread <= 1e-4f && totalSpread <= 1e-4f) return emptyList()

        val totalMean = total.average().toFloat()

        /**
         * Whether the bottom is out at [i], judged against what else is playing.
         *
         * Against the other bands rather than against the low band's own average,
         * for two reasons. A track that never has much bass has a low mean too,
         * so "below its own mean" says nothing about it; and a band that is
         * perfectly flat is below its own mean nowhere at all, which silently
         * made every build undetectable on exactly the material builds occur in.
         * Relative to the rest of the spectrum is also what the ear is doing.
         */
        fun bottomIsOut(i: Int) = low[i] < (mid[i] + high[i]) / 2f * PRESENT

        // A floor under both thresholds, so a band that barely moves cannot have
        // its noise promoted into structure by having a small spread.
        val lowMove = max(lowSpread * SENSITIVITY, MINIMUM_MOVE)
        val highMove = max(highSpread * BUILD_SENSITIVITY, MINIMUM_CLIMB)

        val found = ArrayList<PointOfInterest>()

        for (i in LOOK until bands.size - LOOK) {
            val time = bands.timeOf(i)
            val lowBefore = low[i - LOOK]
            val lowNow = low[i]
            val lowAfter = low[i + LOOK]
            val highBefore = high[i - LOOK]
            val highNow = high[i]

            val lowRise = lowNow - lowBefore
            val lowFall = lowBefore - lowNow

            when {
                // A drop: the low end was gone, it is back, and it stays back.
                // The "stays" clause is what separates a drop from a single hit.
                lowRise > lowMove && bottomIsOut(i - LOOK) && lowAfter >= lowNow - lowSpread * 0.5f ->
                    found.add(PointOfInterest(time, PointOfInterest.Kind.DROP, strength(lowRise, lowSpread)))

                // A breakdown: the low end leaves and something is still playing.
                // Requiring the top to survive is what stops an outro fade —
                // where everything leaves — from being called a breakdown.
                lowFall > lowMove && bottomIsOut(i) && highNow > highBefore * SURVIVES ->
                    found.add(PointOfInterest(time, PointOfInterest.Kind.BREAKDOWN, strength(lowFall, lowSpread)))

                // A build: the top climbs while the bottom is still out. This is
                // the case a broadband detector cannot see at all, because the
                // sum of a rising riser and an absent kick is roughly flat.
                highNow - highBefore > highMove && bottomIsOut(i) ->
                    found.add(
                        PointOfInterest(
                            time,
                            PointOfInterest.Kind.BUILD,
                            strength(highNow - highBefore, highSpread),
                        ),
                    )

                // A peak: everything is loud at once and this is a local maximum.
                total[i] > totalMean + totalSpread &&
                    total[i] >= total[i - LOOK] && total[i] >= total[i + LOOK] ->
                    found.add(
                        PointOfInterest(
                            time,
                            PointOfInterest.Kind.PEAK,
                            strength(total[i] - totalMean, totalSpread),
                        ),
                    )
            }
        }

        return finish(found, input.grid, input.limit)
    }

    companion object {
        /** Windows either side to compare against — a second at the default window. */
        const val LOOK = 2

        /** How large a low-end move has to be, as a fraction of that band's spread. */
        const val SENSITIVITY = 0.7f

        /** The same for the top, lower because a riser climbs gradually by design. */
        const val BUILD_SENSITIVITY = 0.3f

        /** Smallest low-end move that counts as an event at all. */
        const val MINIMUM_MOVE = 0.08f

        /** Smallest climb in the top that counts as a build. */
        const val MINIMUM_CLIMB = 0.05f

        /**
         * How loud the bottom may be, against the average of the other two bands,
         * and still count as out.
         */
        const val PRESENT = 0.8f

        /** How much of the top has to survive for a lull to count as a breakdown. */
        const val SURVIVES = 0.6f

        /** Below this there is not enough track to find structure in. */
        const val MINIMUM_WINDOWS = 8
    }
}

/**
 * The original detector: one smoothed loudness curve and the sign of its slope.
 *
 * Retained deliberately rather than deleted. Energy curves cached by earlier
 * versions have no band information, and re-deriving it means decoding the
 * track again — so a library analysed last month keeps working, at the fidelity
 * it was analysed at, instead of losing its landmarks until every file is read
 * back off disk.
 */
class BroadbandSegmenter : StructureSegmenter {

    override fun segment(input: StructureInput): List<PointOfInterest> {
        val values = input.energy.values
        if (values.size < 6) return emptyList()

        // Radius 1, not 2: with a +/-2 lookback as well, heavier smoothing flattens
        // a genuine step change below the threshold meant to catch it.
        val smoothed = smooth(values)
        val mean = smoothed.average().toFloat()
        val deviation = spread(smoothed)
        if (deviation <= 1e-4f) return emptyList()

        val found = ArrayList<PointOfInterest>()

        for (i in 2 until smoothed.size - 2) {
            val before = smoothed[i - 2]
            val here = smoothed[i]
            val after = smoothed[i + 2]
            val time = i * input.energy.windowSeconds

            val rise = here - before
            val fall = before - here
            // Slightly under one standard deviation: a real drop or breakdown is
            // large, but demanding a full deviation after smoothing misses it.
            val threshold = deviation * 0.8f

            when {
                rise > threshold && before < mean && after >= here - deviation * 0.5f ->
                    found.add(PointOfInterest(time, PointOfInterest.Kind.DROP, strength(rise, deviation)))

                fall > threshold && here < mean && after < mean ->
                    found.add(PointOfInterest(time, PointOfInterest.Kind.BREAKDOWN, strength(fall, deviation)))

                rise > deviation * 0.5f && after > here ->
                    found.add(PointOfInterest(time, PointOfInterest.Kind.BUILD, strength(rise, deviation)))

                here > mean + deviation && here >= before && here >= after ->
                    found.add(PointOfInterest(time, PointOfInterest.Kind.PEAK, strength(here - mean, deviation)))
            }
        }

        return finish(found, input.grid, input.limit)
    }
}

// --- Shared arithmetic ---

/**
 * Snaps to bars, collapses what landed on the same one, and keeps the strongest.
 *
 * A cue marker half a beat off is worse than useless, and two landmarks on one
 * bar line are one landmark reported twice.
 */
private fun finish(
    found: List<PointOfInterest>,
    grid: BeatGrid?,
    limit: Int,
): List<PointOfInterest> {
    val snapped = if (grid == null) found else found.map {
        it.copy(timeSeconds = grid.nearestBarTime(it.timeSeconds).coerceAtLeast(0.0))
    }
    return snapped
        .groupBy { it.timeSeconds to it.kind }
        .map { (_, group) -> group.maxBy { it.strength } }
        .sortedByDescending { it.strength }
        .take(limit)
        .sortedBy { it.timeSeconds }
}

private fun smooth(values: FloatArray, radius: Int = 1): FloatArray =
    FloatArray(values.size) { i ->
        var sum = 0f
        var count = 0
        for (j in max(0, i - radius)..min(values.size - 1, i + radius)) {
            sum += values[j]
            count++
        }
        sum / count
    }

private fun spread(values: FloatArray): Float {
    if (values.isEmpty()) return 0f
    val mean = values.average().toFloat()
    return sqrt(values.map { (it - mean) * (it - mean) }.average()).toFloat()
}

private fun strength(magnitude: Float, deviation: Float): Float =
    if (deviation <= 0f) 0f else (abs(magnitude) / (deviation * 3f)).coerceIn(0f, 1f)
