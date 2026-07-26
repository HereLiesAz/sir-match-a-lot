package com.hereliesaz.sirmatchalot.ui.platter

import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding what a finger on the platter is touching.
 *
 * Dragging a clip's placement, and dragging it off the circle to delete it, both
 * rest entirely on these two questions: is the finger on the ring at all, and if
 * so which clip is under it. Both are pure arithmetic, so both are tested here
 * rather than only on a device.
 */
class PlatterHitTestTest {

    private fun clip(id: String, start: Float, span: Float) = PlatterClip(
        id = id,
        title = id,
        startFraction = start,
        spanFraction = span,
        peaks = PeakEnvelope.compute(FloatArray(0)),
        energy = null,
        paletteIndex = 0,
        selected = false,
    )

    // --- The ring band ---

    @Test
    fun `the ring band brackets the base radius`() {
        val base = 100f
        assertTrue(PlatterGeometry.isOnRing(100f, base))
        assertTrue(PlatterGeometry.isOnRing(40f, base))
        assertTrue(PlatterGeometry.isOnRing(180f, base))
    }

    @Test
    fun `far outside and near the centre are off the ring`() {
        // There has to be a definite outside, or a clip could never be dragged
        // off to delete it: every point would belong to one deck or the other.
        val base = 100f
        assertFalse(PlatterGeometry.isOnRing(400f, base))
        assertFalse(PlatterGeometry.isOnRing(5f, base))
    }

    @Test
    fun `an empty platter has no ring`() {
        assertFalse(PlatterGeometry.isOnRing(50f, baseRadius = 0f))
    }

    // --- Which clip ---

    @Test
    fun `a clip is found within its span`() {
        val clips = listOf(clip("a", 0.0f, 0.25f), clip("b", 0.5f, 0.25f))
        assertEquals("a", PlatterGeometry.clipAt(clips, 0.1f)?.id)
        assertEquals("b", PlatterGeometry.clipAt(clips, 0.6f)?.id)
    }

    @Test
    fun `a gap between clips is nothing`() {
        val clips = listOf(clip("a", 0.0f, 0.25f), clip("b", 0.5f, 0.25f))
        assertNull(PlatterGeometry.clipAt(clips, 0.4f))
        assertNull(PlatterGeometry.clipAt(clips, 0.9f))
    }

    @Test
    fun `a span that runs past the top still covers both sides of it`() {
        // The timeline is a circle: a clip that starts at 0.9 and runs 0.2 does
        // not stop existing at twelve o'clock.
        val clips = listOf(clip("wrapped", 0.9f, 0.2f))
        assertEquals("wrapped", PlatterGeometry.clipAt(clips, 0.95f)?.id)
        assertEquals("wrapped", PlatterGeometry.clipAt(clips, 0.05f)?.id)
        assertNull(PlatterGeometry.clipAt(clips, 0.5f))
    }

    @Test
    fun `a clip covering the whole circle is found anywhere`() {
        // A single clip alone on a deck loops the whole revolution.
        val clips = listOf(clip("looped", 0f, 1f))
        for (f in listOf(0f, 0.3f, 0.75f, 0.99f)) {
            assertEquals("looped", PlatterGeometry.clipAt(clips, f)?.id)
        }
    }

    @Test
    fun `later clips win where they overlap, matching draw order`() {
        val clips = listOf(clip("under", 0.0f, 0.5f), clip("over", 0.2f, 0.2f))
        assertEquals("over", PlatterGeometry.clipAt(clips, 0.3f)?.id)
        assertEquals("under", PlatterGeometry.clipAt(clips, 0.1f)?.id)
    }

    @Test
    fun `a zero-width clip is never hit`() {
        assertNull(PlatterGeometry.clipAt(listOf(clip("empty", 0.5f, 0f)), 0.5f))
    }

    @Test
    fun `an empty deck yields nothing`() {
        assertNull(PlatterGeometry.clipAt(emptyList(), 0.5f))
    }

    @Test
    fun `fractions outside zero to one wrap rather than missing`() {
        val clips = listOf(clip("a", 0.0f, 0.25f))
        assertEquals("a", PlatterGeometry.clipAt(clips, 1.1f)?.id)
        assertEquals("a", PlatterGeometry.clipAt(clips, -0.9f)?.id)
    }
}
