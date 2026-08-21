package com.hereliesaz.sirmatchalot.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    private fun one(x: Float, y: Float) = listOf(Pointer(1, x, y))

    private fun two(x1: Float, y1: Float, x2: Float, y2: Float) =
        listOf(Pointer(1, x1, y1), Pointer(2, x2, y2))

    private fun three(offsetX: Float, offsetY: Float, spread: Float = 100f, twist: Float = 0f) =
        listOf(
            Pointer(1, offsetX + spread, offsetY),
            Pointer(2, offsetX - spread / 2 + twist, offsetY + spread),
            Pointer(3, offsetX - spread / 2 - twist, offsetY - spread),
        )

    private fun kinds(engine: GestureEngine) = engine.active.map { it.kind }.toSet()

    /** Feeds a straight two-finger drag and returns the recognised kinds. */
    private fun dragTwoFingers(dx: Float, dy: Float, steps: Int = 12): Set<GestureKind> {
        val engine = GestureEngine()
        engine.update(two(0f, 0f, 100f, 0f))
        for (i in 1..steps) {
            engine.update(two(dx * i, dy * i, 100f + dx * i, dy * i))
        }
        return kinds(engine)
    }

    @Test
    fun `nothing is active before a gesture starts`() {
        val engine = GestureEngine()
        assertTrue(engine.update(emptyList()).isEmpty())
    }

    @Test
    fun `a small movement does not trigger anything`() {
        val engine = GestureEngine()
        engine.update(two(0f, 0f, 100f, 0f))
        engine.update(two(1f, 0f, 101f, 0f))
        assertTrue("jitter should not register: ${kinds(engine)}", engine.active.isEmpty())
    }

    @Test
    fun `one finger manipulates clips`() {
        val engine = GestureEngine()
        engine.update(one(0f, 0f))
        repeat(10) { engine.update(one(it * 4f, 0f)) }
        assertEquals(setOf(GestureKind.CLIP_DRAG), kinds(engine))
    }

    @Test
    fun `two fingers horizontal is the crossfader`() {
        assertEquals(setOf(GestureKind.CROSSFADE), dragTwoFingers(dx = 6f, dy = 0f))
    }

    @Test
    fun `two fingers vertical is the smart scratch`() {
        assertEquals(setOf(GestureKind.SCRATCH), dragTwoFingers(dx = 0f, dy = 6f))
    }

    @Test
    fun `a straight drag does not also trigger volume and bass`() {
        // The previous implementation tested four independent thresholds every
        // frame, so one sloppy two-finger drag fired CROSSFADER, SMART SCRATCH,
        // VOLUME and BASS BOOST at once.
        val recognised = dragTwoFingers(dx = 6f, dy = 0f)
        assertTrue("volume should not fire on a horizontal drag", GestureKind.VOLUME !in recognised)
        assertTrue("bass should not fire on a horizontal drag", GestureKind.BASS_BOOST !in recognised)
        assertTrue("scratch should not fire on a horizontal drag", GestureKind.SCRATCH !in recognised)
    }

    @Test
    fun `two axes can run at once when both are deliberate`() {
        // Explicitly required: "x,y gestures should be allowed, meaning it's
        // possible to perform two gestures at the same time".
        val recognised = dragTwoFingers(dx = 6f, dy = 6f)
        assertTrue("expected both axes, got $recognised", recognised.containsAll(
            listOf(GestureKind.CROSSFADE, GestureKind.SCRATCH),
        ))
    }

    @Test
    fun `two finger pinch is bass boost`() {
        val engine = GestureEngine()
        engine.update(two(-50f, 0f, 50f, 0f))
        for (i in 1..14) {
            val half = 50f + i * 4f
            engine.update(two(-half, 0f, half, 0f))
        }
        assertTrue("expected bass boost, got ${kinds(engine)}", GestureKind.BASS_BOOST in kinds(engine))
    }

    @Test
    fun `two finger rotate is volume`() {
        val engine = GestureEngine()
        val radius = 100f
        engine.update(two(radius, 0f, -radius, 0f))
        for (i in 1..14) {
            val angle = i * 0.03
            val x = (radius * Math.cos(angle)).toFloat()
            val y = (radius * Math.sin(angle)).toFloat()
            engine.update(two(x, y, -x, -y))
        }
        assertTrue("expected volume, got ${kinds(engine)}", GestureKind.VOLUME in kinds(engine))
    }

    @Test
    fun `two finger rotate is still volume when pivoted at the wrist, not the fingers`() {
        // The previous test rotates two fingers about their own midpoint —
        // the centroid never moves, so this could pass even if rotation lost
        // every real-world race against translation. A real "twist your
        // wrist" rotation pivots at the wrist, not between the fingers: the
        // whole hand (and so both fingers, together with their midpoint)
        // arcs around a point roughly a forearm's length away. That midpoint
        // drift looks exactly like a CROSSFADE/SCRATCH drag, and it used to
        // always win — VOLUME could not activate for any rotation pivoted
        // more than ~400 px from the finger midpoint, which a wrist pivot at
        // 1000+ px always is.
        val engine = GestureEngine()
        // A wrist-pivoted rotation's centroid drift scales linearly with this
        // pivot distance while the rotation signal itself does not, so past
        // some radius no magnitude-based comparison against translation can
        // avoid being either hair-trigger on ordinary drags or unable to ever
        // recognise a real hard-forearm twist. 700 px is comfortably past the
        // ~400 px radius at which the previous, radian-vs-pixel comparison
        // failed outright, and is what this fix is tuned to guarantee.
        val wristDistance = 700f
        val fingerHalfSpacing = 90f

        fun handAt(theta: Double): List<Pointer> {
            val cx = (wristDistance * Math.cos(theta)).toFloat()
            val cy = (wristDistance * Math.sin(theta)).toFloat()
            val ox = (fingerHalfSpacing * Math.cos(theta)).toFloat()
            val oy = (fingerHalfSpacing * Math.sin(theta)).toFloat()
            return listOf(Pointer(1, cx + ox, cy + oy), Pointer(2, cx - ox, cy - oy))
        }

        engine.update(handAt(0.0))
        for (i in 1..14) {
            engine.update(handAt(i * 0.03))
        }
        assertTrue(
            "expected volume from a wrist-pivoted rotation, got ${kinds(engine)}",
            GestureKind.VOLUME in kinds(engine),
        )
    }

    @Test
    fun `sliding three fingers together does nothing`() {
        // This used to pan the platter. Panning is gone: the platter is a fixed
        // circle centred on the screen, and being able to shove it off-centre
        // only ever made it harder to find again. Three fingers moving as one
        // change neither spread nor twist, so there is nothing left to
        // recognise — and in particular this must not fall through to a mix
        // control.
        val engine = GestureEngine()
        engine.update(three(0f, 0f))
        for (i in 1..12) engine.update(three(i * 4f, 0f))
        val recognised = kinds(engine)

        assertEquals("a pure three-finger slide should do nothing", emptySet<GestureKind>(), recognised)
    }

    @Test
    fun `three fingers are never mistaken for a mix control`() {
        val engine = GestureEngine()
        engine.update(three(0f, 0f))
        for (i in 1..12) engine.update(three(i * 4f, i * 2f, spread = 100f + i * 6f, twist = i * 3f))
        val recognised = kinds(engine)

        assertTrue("expected a platter transform, got $recognised", recognised.any {
            it in setOf(GestureKind.PLATTER_SCALE, GestureKind.PLATTER_ROTATE)
        })
        assertTrue(GestureKind.CROSSFADE !in recognised)
        assertTrue(GestureKind.SCRATCH !in recognised)
    }

    @Test
    fun `three finger spread zooms the platter`() {
        val engine = GestureEngine()
        engine.update(three(0f, 0f, spread = 60f))
        for (i in 1..14) engine.update(three(0f, 0f, spread = 60f + i * 6f))
        assertTrue("expected zoom, got ${kinds(engine)}", GestureKind.PLATTER_SCALE in kinds(engine))
    }

    @Test
    fun `a gesture ends when its axis goes quiet, not when the finger lifts`() {
        // Required explicitly: "You need to make the gesture end when the
        // conditions that define that gesture in the first place aren't being met."
        val engine = GestureEngine()
        engine.update(two(0f, 0f, 100f, 0f))
        for (i in 1..12) engine.update(two(i * 6f, 0f, 100f + i * 6f, 0f))
        assertTrue("should be crossfading", GestureKind.CROSSFADE in kinds(engine))

        // Fingers stay down, motion stops.
        repeat(20) { engine.update(two(72f, 0f, 172f, 0f)) }
        assertTrue("should have ended with fingers still down: ${kinds(engine)}", engine.active.isEmpty())
    }

    @Test
    fun `changing finger count ends the previous gesture`() {
        // Running one gesture straight into another must not leave the first active.
        val engine = GestureEngine()
        engine.update(two(0f, 0f, 100f, 0f))
        for (i in 1..12) engine.update(two(i * 6f, 0f, 100f + i * 6f, 0f))
        assertTrue(GestureKind.CROSSFADE in kinds(engine))

        engine.update(three(0f, 0f))
        assertTrue("adding a finger must end the two-finger gesture", engine.active.isEmpty())
    }

    @Test
    fun `lifting all fingers clears everything`() {
        val engine = GestureEngine()
        engine.update(one(0f, 0f))
        repeat(10) { engine.update(one(it * 4f, 0f)) }
        assertTrue(engine.active.isNotEmpty())
        engine.update(emptyList())
        assertTrue(engine.active.isEmpty())
    }

    @Test
    fun `a gesture can restart after ending`() {
        val engine = GestureEngine()
        fun sweep() {
            engine.update(two(0f, 0f, 100f, 0f))
            for (i in 1..12) engine.update(two(i * 6f, 0f, 100f + i * 6f, 0f))
        }
        sweep()
        assertTrue(GestureKind.CROSSFADE in kinds(engine))
        engine.update(emptyList())
        sweep()
        assertTrue("should recognise again", GestureKind.CROSSFADE in kinds(engine))
    }

    @Test
    fun `delta and total are reported`() {
        val engine = GestureEngine()
        engine.update(two(0f, 0f, 100f, 0f))
        for (i in 1..12) engine.update(two(i * 6f, 0f, 100f + i * 6f, 0f))
        val gesture = engine.active.first { it.kind == GestureKind.CROSSFADE }
        assertEquals("per-frame delta", 6f, gesture.delta, 1e-3f)
        assertEquals("accumulated total", 72f, gesture.total, 1e-2f)
    }

    @Test
    fun `direction is signed so a reversal is visible`() {
        val engine = GestureEngine()
        engine.update(two(0f, 0f, 100f, 0f))
        for (i in 1..12) engine.update(two(-i * 6f, 0f, 100f - i * 6f, 0f))
        val gesture = engine.active.first { it.kind == GestureKind.CROSSFADE }
        assertTrue("leftward drag should be negative: ${gesture.total}", gesture.total < 0f)
    }

    @Test
    fun `rotation does not jump across the angle wrap`() {
        // Averaging per-pointer angles is discontinuous at +/-PI, which made the
        // previous three-finger rotation lurch. Sweeping through the seam must
        // produce only small per-frame deltas.
        val engine = GestureEngine()
        val radius = 100f
        var previousDelta = 0f
        var maxDelta = 0f
        var angle = Math.PI - 0.3
        engine.update(two((radius * Math.cos(angle)).toFloat(), (radius * Math.sin(angle)).toFloat(), 0f, 0f))
        repeat(30) {
            angle += 0.02
            val x = (radius * Math.cos(angle)).toFloat()
            val y = (radius * Math.sin(angle)).toFloat()
            engine.update(two(x, y, 0f, 0f))
            engine.active.firstOrNull { it.kind == GestureKind.VOLUME }?.let {
                maxDelta = maxOf(maxDelta, kotlin.math.abs(it.delta))
                previousDelta = it.delta
            }
        }
        assertTrue("rotation jumped by $maxDelta across the seam", maxDelta < 0.5f)
    }

    @Test
    fun `reset clears state`() {
        val engine = GestureEngine()
        engine.update(one(0f, 0f))
        repeat(10) { engine.update(one(it * 4f, 0f)) }
        assertTrue(engine.active.isNotEmpty())
        engine.reset()
        assertTrue(engine.active.isEmpty())
    }

    @Test
    fun `gestures are position independent`() {
        // Required three times over: it must not matter where on screen the
        // gesture happens. The same drag far from the centre must recognise
        // identically.
        val near = dragTwoFingers(dx = 6f, dy = 0f)
        val engine = GestureEngine()
        engine.update(two(2000f, 3000f, 2100f, 3000f))
        for (i in 1..12) {
            engine.update(two(2000f + i * 6f, 3000f, 2100f + i * 6f, 3000f))
        }
        assertEquals(near, kinds(engine))
    }
}
