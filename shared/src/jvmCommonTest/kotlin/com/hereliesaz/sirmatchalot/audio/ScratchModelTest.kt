package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchModelTest {

    private fun model() = ScratchModel(
        pixelsPerRateUnit = 400f,
        curve = 1.7f,
        maxRate = 3.0,
        easterEggReverseFrames = 44_100.0,
    )

    @Test
    fun `is inactive until a gesture begins`() {
        val scratch = model()
        assertTrue(!scratch.active)
        // Updates before begin are ignored.
        assertEquals(1.0, scratch.update(500f), 0.0)
    }

    @Test
    fun `begins from the deck's current rate`() {
        val scratch = model()
        scratch.begin(currentRate = 0.9)
        assertTrue(scratch.active)
        assertEquals(0.9, scratch.rate, 1e-9)
        assertEquals(0.9, scratch.update(0f), 1e-9)
    }

    @Test
    fun `pulling down slows then reverses`() {
        val scratch = model()
        scratch.begin()

        val gentle = scratch.update(100f)
        assertTrue("small pull should slow but not reverse: $gentle", gentle in 0.0..1.0)

        val harder = scratch.update(400f)
        assertTrue("larger pull should slow further: $harder", harder < gentle)

        val reversed = scratch.update(900f)
        assertTrue("a long pull should play backwards: $reversed", reversed < 0.0)
    }

    @Test
    fun `pushing up speeds past normal`() {
        val scratch = model()
        scratch.begin()
        assertTrue(scratch.update(-300f) > 1.0)
    }

    @Test
    fun `the response curve is gentler near normal speed than far from it`() {
        // A non-linear curve was specified so that fine control is available
        // around normal playback.
        val scratch = model()
        scratch.begin()

        val nearRate = 1.0 - scratch.update(40f)
        scratch.begin()
        val farStart = scratch.update(400f)
        scratch.begin()
        val farEnd = scratch.update(440f)
        val farRate = farStart - farEnd

        assertTrue(
            "the same 40 px should move rate less near normal ($nearRate) than far out ($farRate)",
            nearRate < farRate,
        )
    }

    @Test
    fun `rate is clamped in both directions`() {
        val scratch = model()
        scratch.begin()
        assertTrue(scratch.update(100_000f) >= -3.0)
        assertEquals(-3.0, scratch.update(100_000f), 1e-9)
        assertEquals(3.0, scratch.update(-100_000f), 1e-9)
    }

    @Test
    fun `direction is preserved through the curve`() {
        val scratch = model()
        scratch.begin()
        for (delta in listOf(10f, 50f, 200f)) {
            assertTrue("downward pull must reduce rate", scratch.update(delta) < 1.0)
        }
        for (delta in listOf(-10f, -50f, -200f)) {
            assertTrue("upward push must raise rate", scratch.update(delta) > 1.0)
        }
    }

    @Test
    fun `ending restores the resting rate`() {
        val scratch = model()
        scratch.begin(currentRate = 1.0)
        scratch.update(900f)
        assertEquals(1.0, scratch.end(), 1e-9)
        assertTrue(!scratch.active)
    }

    @Test
    fun `the easter egg fires once after enough reverse travel`() {
        val scratch = model()
        scratch.begin()
        scratch.update(900f)
        assertTrue("should be reversing", scratch.rate < 0)

        var fireCount = 0
        // Render well past the one-second-of-reverse threshold.
        repeat(400) {
            if (scratch.accountForRenderedFrames(512)) fireCount++
        }
        assertEquals("must trigger exactly once per gesture", 1, fireCount)
    }

    @Test
    fun `the easter egg does not fire while playing forward`() {
        val scratch = model()
        scratch.begin()
        scratch.update(-200f)
        repeat(500) {
            assertTrue(!scratch.accountForRenderedFrames(512))
        }
    }

    @Test
    fun `the easter egg does not fire on a brief reverse flick`() {
        val scratch = model()
        scratch.begin()
        scratch.update(900f)
        // A tenth of a second of reverse is nowhere near the threshold.
        repeat(8) {
            assertTrue(!scratch.accountForRenderedFrames(512))
        }
    }

    @Test
    fun `reverse progress is reported for the ramp-up`() {
        val scratch = model()
        scratch.begin()
        assertEquals(0f, scratch.reverseProgress(), 0f)

        scratch.update(900f)
        repeat(20) { scratch.accountForRenderedFrames(512) }
        val progress = scratch.reverseProgress()
        assertTrue("progress should have advanced: $progress", progress > 0f && progress <= 1f)
    }

    @Test
    fun `reversing forward again resets the accumulator`() {
        val scratch = model()
        scratch.begin()
        scratch.update(900f)
        repeat(20) { scratch.accountForRenderedFrames(512) }
        assertTrue(scratch.reverseProgress() > 0f)

        // Push back to forward playback.
        scratch.update(-100f)
        scratch.accountForRenderedFrames(512)
        assertEquals(0f, scratch.reverseProgress(), 0f)
    }

    @Test
    fun `a new gesture can trigger the egg again`() {
        val scratch = model()

        fun runUntilFired(): Boolean {
            scratch.begin()
            scratch.update(900f)
            var fired = false
            repeat(400) { if (scratch.accountForRenderedFrames(512)) fired = true }
            scratch.end()
            return fired
        }

        assertTrue(runUntilFired())
        assertTrue("a second gesture should be able to fire it again", runUntilFired())
    }

    @Test
    fun `reverse progress is reported all the way, not only at the threshold`() {
        // The accumulator existed and `reverseProgress` was tested, but
        // `AudioEngine.scratchReverseProgress` — its only route out of the audio
        // package — had no caller anywhere in the app. Holding a record
        // backwards reported nothing until the instant the easter egg fired.
        // It now reaches `PlatterState.reverseProgress` and is drawn as a trail,
        // so this asserts the shape that display depends on: monotonic, bounded,
        // and back to nothing when the gesture ends.
        val threshold = 44_100.0 * 6
        val model = ScratchModel(easterEggReverseFrames = threshold)
        model.begin(1.0)
        // Dragging away from the resting rate far enough to pass through zero:
        // `rate = restingRate - shaped`, so a positive displacement reverses.
        model.update(2_000f)
        assertTrue("a reverse gesture must give a negative rate", model.rate < 0.0)

        var previous = 0f
        repeat(20) {
            model.accountForRenderedFrames(4_410)
            val progress = model.reverseProgress()
            assertTrue("progress went backwards: $previous -> $progress", progress >= previous)
            assertTrue("progress left 0..1: $progress", progress in 0f..1f)
            previous = progress
        }
        assertTrue("nothing accumulated at all", previous > 0f)

        model.end()
        assertEquals(0f, model.reverseProgress(), 0f)
    }
}
