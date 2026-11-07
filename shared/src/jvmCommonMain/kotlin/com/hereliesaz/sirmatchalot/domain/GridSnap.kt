package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import kotlin.math.abs

/**
 * A line on the master timeline that one clip can be snapped onto, or snapped
 * from: a beat (with its place in the bar) or a structural marker.
 *
 * @param barPosition 0 for a downbeat, 1 for the second beat, and so on. -1 for a
 *   marker, which has no place in the bar.
 * @param beatsPerBar the bar this beat belongs to, so a 3/4 clip is not mistaken
 *   for a 4/4 one.
 */
data class SnapPoint(
    val timeSeconds: Double,
    val kind: Kind,
    val barPosition: Int = -1,
    val beatsPerBar: Int = 4,
    val marker: PointOfInterest.Kind? = null,
) {
    enum class Kind { BEAT, MARKER }

    val isDownbeat: Boolean get() = kind == Kind.BEAT && barPosition == 0

    companion object {
        fun beat(timeSeconds: Double, barPosition: Int, beatsPerBar: Int = 4) =
            SnapPoint(timeSeconds, Kind.BEAT, barPosition, beatsPerBar)

        fun marker(timeSeconds: Double, kind: PointOfInterest.Kind) =
            SnapPoint(timeSeconds, Kind.MARKER, marker = kind)
    }
}

/**
 * How firmly a snap pulls.
 *
 * A snap that pulls the same from every line is not a guide, it is a rail: with
 * a beat every half second there is always one within reach, so nothing is ever
 * left where it was put. The tiers make the musically meaningful alignments
 * grab from further away and the merely-on-the-beat ones only when you are
 * already nearly there.
 *
 * @param weight multiplier on the pull radius. Also the divisor when candidates
 *   are compared, so a near weak snap does not beat a slightly further strong one.
 */
enum class SnapTier(val weight: Double) {
    /** Bar line on bar line, or a marker on a marker or a bar line. */
    STRONG(1.0),

    /** Half a bar out: beat four on beat two. Musical, but not the bar. */
    MEDIUM(0.5),

    /** On a beat, in no particular relation to the bar. */
    WEAK(0.25),
}

/**
 * The result of a snap.
 *
 * @param shiftSeconds how far to move the moving clip from where it is now.
 * @param tier how firm the line that caught it was, so the UI can say.
 * @param mover the point on the moving clip that landed.
 * @param target the point it landed on.
 */
data class SnapResult(
    val shiftSeconds: Double,
    val tier: SnapTier,
    val mover: SnapPoint,
    val target: SnapPoint,
)

/**
 * Snapping one clip's beat grid and markers onto another's.
 *
 * Replaces [BeatSnap.snapFrame], which rounded a clip's start to the nearest beat
 * of one reference grid: unconditionally, ignoring bars, never looking at the
 * other deck's beats, and moving the clip's frame zero rather than its first
 * beat. Everything here works on *points*, so what lands is the thing the user is
 * trying to land — a downbeat, a drop — wherever it sits in its file.
 *
 * Pure arithmetic, so the behaviour is pinned in tests rather than on a device.
 */
object GridSnap {

    /** Strong pulls reach this many beats; medium half that, weak a quarter. */
    const val DEFAULT_RADIUS_BEATS = 1.0

    /**
     * The firmness of landing [mover] on [target], or null when they have no
     * musical relation and should not attract each other at all.
     */
    fun tierOf(mover: SnapPoint, target: SnapPoint): SnapTier? {
        return when {
            mover.kind == SnapPoint.Kind.MARKER && target.kind == SnapPoint.Kind.MARKER ->
                if (mover.marker == target.marker) SnapTier.STRONG else null

            mover.kind == SnapPoint.Kind.MARKER || target.kind == SnapPoint.Kind.MARKER -> {
                val beat = if (mover.kind == SnapPoint.Kind.BEAT) mover else target
                if (beat.isDownbeat) SnapTier.STRONG else SnapTier.WEAK
            }

            else -> beatTier(mover, target)
        }
    }

    private fun beatTier(mover: SnapPoint, target: SnapPoint): SnapTier {
        // Downbeats meeting is the thing a bar-matched mix is made of, whatever
        // the two bars are made of.
        if (mover.isDownbeat && target.isDownbeat) return SnapTier.STRONG
        if (mover.beatsPerBar != target.beatsPerBar) return SnapTier.WEAK

        val bar = target.beatsPerBar
        val offset = ((target.barPosition - mover.barPosition) % bar + bar) % bar
        return when {
            offset == 0 -> SnapTier.STRONG
            bar % 2 == 0 && offset * 2 == bar -> SnapTier.MEDIUM
            else -> SnapTier.WEAK
        }
    }

    /**
     * The best snap for a moving clip, or null when nothing is close enough.
     *
     * Every [mover] point is compared with every [targets] point inside the
     * tier's radius. The winner is the one with the smallest *distance divided
     * by weight*: a downbeat 0.4 beat away outranks a plain beat 0.15 away, which
     * is the behaviour that makes bars feel like bars.
     *
     * @param radiusSeconds the strong-tier reach. Zero or negative disables
     *   snapping, which is what a caller holding a "free move" modifier passes.
     */
    fun snap(
        mover: List<SnapPoint>,
        targets: List<SnapPoint>,
        radiusSeconds: Double,
    ): SnapResult? {
        if (radiusSeconds <= 0.0 || mover.isEmpty() || targets.isEmpty()) return null

        val sorted = targets.sortedBy { it.timeSeconds }
        val times = DoubleArray(sorted.size) { sorted[it].timeSeconds }

        var best: SnapResult? = null
        var bestScore = Double.MAX_VALUE
        for (point in mover) {
            var i = lowerBound(times, point.timeSeconds - radiusSeconds)
            while (i < sorted.size && times[i] <= point.timeSeconds + radiusSeconds) {
                val target = sorted[i]
                val tier = tierOf(point, target)
                if (tier != null) {
                    val shift = target.timeSeconds - point.timeSeconds
                    val reach = radiusSeconds * tier.weight
                    if (abs(shift) <= reach) {
                        val score = abs(shift) / tier.weight
                        val better = score < bestScore ||
                            (score == bestScore && best != null && tier.weight > best.tier.weight)
                        if (better) {
                            bestScore = score
                            best = SnapResult(shift, tier, point, target)
                        }
                    }
                }
                i++
            }
        }
        return best
    }

    /** Index of the first element of [sorted] that is >= [value]. */
    private fun lowerBound(sorted: DoubleArray, value: Double): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
