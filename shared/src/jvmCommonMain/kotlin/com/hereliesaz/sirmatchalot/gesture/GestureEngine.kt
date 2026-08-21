package com.hereliesaz.sirmatchalot.gesture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A pointer position at one instant. */
data class Pointer(val id: Long, val x: Float, val y: Float)

/**
 * What a recognised gesture controls.
 *
 * The mapping is the one from the final revision of the requirements: all
 * single-finger gestures manipulate the audio clips, two fingers control the
 * mix, three fingers transform the platter itself.
 */
enum class GestureKind(val label: String) {
    CLIP_DRAG("CLIP"),
    CROSSFADE("CROSSFADER"),
    SCRATCH("SMART SCRATCH"),
    VOLUME("VOLUME"),
    BASS_BOOST("BASS BOOST"),
    PLATTER_SCALE("ZOOM PLATTER"),
    PLATTER_ROTATE("ROTATE PLATTER"),
}

/**
 * A gesture recognised this frame.
 *
 * @param delta change along this gesture's axis since the previous frame, in the
 *   axis's own units (pixels, radians, or pixels of span).
 * @param total change since the gesture began.
 */
data class RecognisedGesture(
    val kind: GestureKind,
    val delta: Float,
    val total: Float,
)

/**
 * Recognises multiple concurrent gestures from a pointer set.
 *
 * Three properties the previous implementation lacked, each asked for
 * explicitly:
 *
 * **Concurrency.** Two gestures may be active at once. The old code dispatched
 * on `when (pointers.size)`, so nothing across finger counts could combine.
 *
 * **No crosstalk.** Within the two-finger set the old code tested four
 * independent thresholds every frame, so a single sloppy drag fired crossfade,
 * scratch, volume and bass simultaneously. Here the axes are *arbitrated*: an
 * axis activates only if its own movement passes an enter threshold **and** is
 * within [DOMINANCE_RATIO] of the strongest axis. A deliberate two-axis gesture
 * still registers as two; incidental noise on the other axes does not.
 *
 * **Ending on conditions, not on lift.** A gesture ends the moment its defining
 * condition stops holding — its axis going quiet, or the finger count changing —
 * rather than when the finger comes up, because people run one gesture straight
 * into another. Each axis has separate enter and exit thresholds so it does not
 * chatter at the boundary.
 *
 * Gestures are global: nothing here consults where on screen the touch is. The
 * only position-dependent behaviour is selecting a waveform, which the platter
 * handles separately.
 *
 * Not thread-safe; driven from the UI thread's pointer loop.
 */
class GestureEngine {

    /** Per-axis accumulated state. */
    private class Axis(
        val kind: GestureKind,
        val enterThreshold: Float,
        val exitThreshold: Float,
    ) {
        var active = false
        var total = 0f
        var delta = 0f

        /**
         * Distance covered along this axis, regardless of direction.
         *
         * Separate from [total], which is *signed* and is what the gesture
         * reports. Signed sum is the wrong measure of "has this axis been
         * used": a scratch is back-and-forth, so its signed total hovers near
         * zero however hard it is being worked, while a slow one-directional
         * drift on another axis accumulates without pause. Deciding intent on
         * the signed total therefore made the axis you were actually moving
         * look idle next to the one you were not.
         *
         * Measured in [magnitude]'s units, which is not always [delta]'s: see
         * [observe].
         */
        var travelled = 0f

        /** Magnitude of recent movement, low-pass filtered to smooth jitter. */
        var activity = 0f

        fun reset() {
            active = false
            total = 0f
            delta = 0f
            travelled = 0f
            activity = 0f
        }

        /**
         * @param frameDelta the signed change this axis reports to callers —
         *   [delta] and [total] are always in this axis's own reporting unit
         *   (pixels for a drag, radians for a rotation), because that is what
         *   a caller like "turn this many radians into a volume change" needs.
         * @param magnitude how much of this axis's *physical* threshold budget
         *   this frame spent, used for [travelled] and [activity] — and so for
         *   [arbitrate]'s dominance comparison. Defaults to `abs(frameDelta)`,
         *   which is correct whenever a delta already lives in the same units
         *   as its enter/exit thresholds. A rotation does not: see
         *   [GestureEngine.update]'s two-finger case for why its magnitude is
         *   arc length, not radians.
         */
        fun observe(frameDelta: Float, magnitude: Float = abs(frameDelta)) {
            delta = frameDelta
            total += frameDelta
            travelled += magnitude
            activity += (magnitude - activity) * ACTIVITY_SMOOTHING
        }
    }

    private val oneFingerDrag = Axis(GestureKind.CLIP_DRAG, 6f, 0.15f)
    private val twoFingerHorizontal = Axis(GestureKind.CROSSFADE, 8f, 0.2f)
    private val twoFingerVertical = Axis(GestureKind.SCRATCH, 8f, 0.2f)

    // Rotation's threshold lives in pixels, like every other two/three-finger
    // axis, not radians — see the arc-length magnitude passed to observe() in
    // update() below for why. This used to be a bare 0.08f rad / 0.004f rad,
    // compared against translation's 8f px / 0.2f px through a shared
    // `activity / enterThreshold` ratio in arbitrate(). That silently declared
    // 1 radian of rotation equal to 100 px of centroid drift, with no physical
    // basis: a real two-finger "twist the wrist" rotation pivots at the wrist,
    // 1300-2000 px away in typical hand geometry, not at the fingers, so the
    // centroid drifts far more than 100 px per radian turned. The result was
    // that CROSSFADE/SCRATCH always out-competed VOLUME for any rotation with
    // a wrist-scale pivot radius — reported as "rotate never registers".
    private val twoFingerRotate = Axis(GestureKind.VOLUME, 4f, 0.1f)
    private val twoFingerSpan = Axis(GestureKind.BASS_BOOST, 14f, 0.3f)
    private val threeFingerScale = Axis(GestureKind.PLATTER_SCALE, 14f, 0.3f)
    private val threeFingerRotate = Axis(GestureKind.PLATTER_ROTATE, 4f, 0.1f)

    private val twoFingerAxes = listOf(
        twoFingerHorizontal, twoFingerVertical, twoFingerRotate, twoFingerSpan,
    )
    private val threeFingerAxes = listOf(
        threeFingerScale, threeFingerRotate,
    )
    private val allAxes = listOf(oneFingerDrag) + twoFingerAxes + threeFingerAxes

    private var previousCount = 0
    private var previousCentroidX = 0f
    private var previousCentroidY = 0f
    private var previousSpan = 0f
    private var previousAngle = 0f

    /** Gestures active as of the last [update]. */
    var active: List<RecognisedGesture> = emptyList()
        private set

    /**
     * Feeds the current [pointers] and returns the gestures active this frame.
     *
     * Pass an empty list when all fingers lift.
     */
    fun update(pointers: List<Pointer>): List<RecognisedGesture> {
        val count = pointers.size

        // A change in finger count ends every gesture: the conditions that
        // defined them no longer hold, whether or not any finger lifted.
        if (count != previousCount) {
            allAxes.forEach { it.reset() }
            previousCount = count
            if (count > 0) {
                previousCentroidX = pointers.sumOf { it.x.toDouble() }.toFloat() / count
                previousCentroidY = pointers.sumOf { it.y.toDouble() }.toFloat() / count
                previousSpan = spanOf(pointers, previousCentroidX, previousCentroidY)
                previousAngle = angleOf(pointers, previousCentroidX, previousCentroidY)
            }
            active = emptyList()
            return active
        }

        if (count == 0) {
            active = emptyList()
            return active
        }

        val centroidX = pointers.sumOf { it.x.toDouble() }.toFloat() / count
        val centroidY = pointers.sumOf { it.y.toDouble() }.toFloat() / count
        val span = spanOf(pointers, centroidX, centroidY)
        val angle = angleOf(pointers, centroidX, centroidY)

        val dx = centroidX - previousCentroidX
        val dy = centroidY - previousCentroidY
        val dSpan = span - previousSpan
        val dAngle = shortestAngleDelta(angle - previousAngle)

        previousCentroidX = centroidX
        previousCentroidY = centroidY
        previousSpan = span
        previousAngle = angle

        val candidates: List<Axis> = when (count) {
            1 -> {
                oneFingerDrag.observe(hypot(dx, dy) * signOf(dx, dy))
                listOf(oneFingerDrag)
            }

            2 -> {
                twoFingerHorizontal.observe(dx)
                twoFingerVertical.observe(dy)
                // Reported delta stays in radians — that is what the volume
                // mapping expects — but arbitration is decided on arc length:
                // how far each finger actually swept, in pixels, which is
                // `radius * angle` with the pointer-to-centroid span as the
                // radius. That is the true physical displacement rotation
                // causes at the fingers themselves, independent of how far the
                // whole hand's pivot (the wrist) happens to sit from the
                // centroid — the quantity translation's axes measure and the
                // one rotation's threshold used to be silently compared
                // against.
                twoFingerRotate.observe(dAngle, magnitude = abs(dAngle) * span)
                twoFingerSpan.observe(dSpan)
                twoFingerAxes
            }

            else -> {
                // Three or more fingers zoom and rotate the platter, so users can
                // work on part of a track precisely. Panning was removed: the
                // platter is a fixed circle centred on the screen, and being able
                // to shove it off-centre only ever made it harder to find.
                threeFingerScale.observe(dSpan)
                threeFingerRotate.observe(dAngle, magnitude = abs(dAngle) * span)
                threeFingerAxes
            }
        }

        arbitrate(candidates)

        active = candidates
            .filter { it.active }
            .map { RecognisedGesture(it.kind, it.delta, it.total) }
        return active
    }

    /**
     * Decides which candidate axes are active.
     *
     * An axis switches on when its own accumulated movement clears its enter
     * threshold and it is a meaningful share of the strongest axis. It switches
     * off when its recent activity falls below its exit threshold. Enter uses
     * total displacement, exit uses recent activity — so a gesture held still
     * mid-motion ends, and does not need the finger lifted.
     */
    private fun arbitrate(candidates: List<Axis>) {
        // Dominance is decided on what you are doing *now*, not on what you did
        // first.
        //
        // This compared lifetime *signed* displacement, and both halves of that
        // were wrong. Signed, so a scratch — which is back-and-forth by
        // definition — cancelled itself out to nearly zero and read as an idle
        // axis. Lifetime, so whichever axis moved first kept its lead for the
        // whole gesture, and its lead grew with any continuing drift: an axis
        // taken up later had to out-accumulate the entire history of the first
        // one to be heard.
        //
        // Together that is the reported feel exactly — the engine settling on
        // an interpretation early and then waiting, apparently for the rest of
        // a gesture nobody was making. Recent activity, unsigned, lets the axis
        // under the finger right now win, and lets a gesture change its mind
        // mid-stroke.
        //
        // Each axis is normalised by its own enter threshold so pixels and
        // radians are comparable.
        var strongest = 0f
        for (axis in candidates) {
            val rate = axis.activity / axis.enterThreshold
            if (rate > strongest) strongest = rate
        }

        for (axis in candidates) {
            if (axis.active) {
                if (axis.activity < axis.exitThreshold) {
                    axis.reset()
                }
            } else {
                // Distance covered, not net displacement: an axis worked hard in
                // both directions has been used, whatever it sums to.
                val clearsThreshold = axis.travelled >= axis.enterThreshold
                val rate = axis.activity / axis.enterThreshold
                val isSignificant = strongest <= 0f || rate >= strongest * DOMINANCE_RATIO
                if (clearsThreshold && isSignificant) {
                    axis.active = true
                }
            }
        }
    }

    /** Ends everything, e.g. when the pointer stream is cancelled. */
    fun reset() {
        allAxes.forEach { it.reset() }
        previousCount = 0
        active = emptyList()
    }

    /** Mean distance of pointers from their centroid; a rotation-invariant span. */
    private fun spanOf(pointers: List<Pointer>, cx: Float, cy: Float): Float {
        if (pointers.size < 2) return 0f
        var sum = 0f
        for (p in pointers) sum += hypot(p.x - cx, p.y - cy)
        return sum / pointers.size
    }

    /**
     * Orientation of the pointer set.
     *
     * Uses the first pointer relative to the centroid rather than an average of
     * per-pointer angles: averaging angles is discontinuous across the -PI/PI
     * boundary, which made the previous three-finger rotation jump.
     */
    private fun angleOf(pointers: List<Pointer>, cx: Float, cy: Float): Float {
        if (pointers.size < 2) return 0f
        val first = pointers.minByOrNull { it.id } ?: return 0f
        return atan2(first.y - cy, first.x - cx)
    }

    /** Signs a magnitude by its dominant direction, so drags accumulate coherently. */
    private fun signOf(dx: Float, dy: Float): Float =
        if (abs(dx) >= abs(dy)) {
            if (dx < 0) -1f else 1f
        } else {
            if (dy < 0) -1f else 1f
        }

    private fun shortestAngleDelta(delta: Float): Float {
        var d = delta
        val twoPi = (2.0 * Math.PI).toFloat()
        while (d > Math.PI) d -= twoPi
        while (d < -Math.PI) d += twoPi
        return d
    }

    companion object {
        /**
         * How strong an axis must be relative to the strongest one to count.
         *
         * At 0.25 a deliberate diagonal — say crossfading while scratching —
         * registers as both, while the incidental rotation and span drift that a
         * two-finger drag inevitably produces do not. (This said 0.35 while the
         * constant said 0.25: the tuning argument was being made about a number
         * that had been changed underneath it, and the next person to widen or
         * narrow arbitration would have "restored" 0.35 on its authority.)
         */
        const val DOMINANCE_RATIO = 0.25f

        private const val ACTIVITY_SMOOTHING = 0.4f
    }
}
