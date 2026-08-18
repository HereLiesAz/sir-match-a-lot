package com.hereliesaz.sirmatchalot.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureLabelsTest {

    private fun labels() = GestureLabels(fadeDurationMillis = 500L)

    private fun slotOf(labels: GestureLabels, text: String): Int? =
        labels.visible().firstOrNull { it.text == text }?.slot

    @Test
    fun `clock slots are in the specified priority order`() {
        // 12:00 first, then 1:30, 10:30, 3:00, 9:00, 4:30, 7:30, and 6:00 last.
        assertEquals(
            listOf("12:00", "1:30", "10:30", "3:00", "9:00", "4:30", "7:30", "6:00"),
            GestureLabels.CLOCK_NAMES.toList(),
        )
        assertEquals(GestureLabels.CLOCK_NAMES.size, GestureLabels.CLOCK_SLOTS.size)
        // 6:00 is the least-favoured position and must be last.
        assertEquals("6:00", GestureLabels.CLOCK_NAMES.last())
        assertEquals(0.5f, GestureLabels.CLOCK_SLOTS.last(), 1e-6f)
        // 12:00 is favoured first and sits at the top.
        assertEquals(0f, GestureLabels.CLOCK_SLOTS.first(), 1e-6f)
    }

    @Test
    fun `the first gesture takes twelve o'clock`() {
        val labels = labels()
        labels.update(setOf("CROSSFADER"), 0L)
        assertEquals(0, slotOf(labels, "CROSSFADER"))
    }

    @Test
    fun `concurrent gestures fill slots in priority order`() {
        val labels = labels()
        labels.update(setOf("A"), 0L)
        labels.update(setOf("A", "B"), 16L)
        labels.update(setOf("A", "B", "C"), 32L)
        assertEquals(0, slotOf(labels, "A"))
        assertEquals(1, slotOf(labels, "B"))
        assertEquals(2, slotOf(labels, "C"))
    }

    @Test
    fun `a label appears immediately at full opacity`() {
        val labels = labels()
        labels.update(setOf("SCRATCH"), 0L)
        assertEquals(1f, labels.visible().single().alpha, 1e-6f)
    }

    @Test
    fun `dissolving starts as soon as the gesture ends`() {
        // The dissolve is anchored to the instant the gesture stopped, so alpha
        // is still 1.0 on that very frame and falls from the next one — it does
        // not wait for the finger to lift, which is the actual requirement.
        val labels = labels()
        labels.update(setOf("SCRATCH"), 0L)
        labels.update(emptySet(), 100L)
        labels.update(emptySet(), 200L)
        val label = labels.visible().single()
        assertTrue("should be fading, alpha was ${label.alpha}", label.alpha < 1f)
        assertEquals("100ms into a 500ms fade", 0.8f, label.alpha, 0.05f)
    }

    @Test
    fun `a label is gone once the dissolve completes`() {
        val labels = labels()
        labels.update(setOf("SCRATCH"), 0L)
        labels.update(emptySet(), 10L)
        labels.update(emptySet(), 600L)
        assertTrue("should have been released", labels.visible().none { it.text == "SCRATCH" })
    }

    @Test
    fun `a slot is not reusable until its previous label finishes dissolving`() {
        val labels = labels()
        labels.update(setOf("FIRST"), 0L)
        labels.update(emptySet(), 10L) // FIRST begins fading in slot 0

        // A new gesture arrives mid-dissolve and must not take slot 0.
        labels.update(setOf("SECOND"), 100L)
        assertEquals("slot 0 is still occupied by the fading label", 0, slotOf(labels, "FIRST"))
        assertTrue("SECOND must take a different slot", slotOf(labels, "SECOND") != 0)
    }

    @Test
    fun `a slot is reused once it is genuinely free`() {
        val labels = labels()
        labels.update(setOf("FIRST"), 0L)
        labels.update(emptySet(), 10L)
        labels.update(emptySet(), 700L) // fully dissolved, slot released
        labels.update(setOf("SECOND"), 720L)
        assertEquals(0, slotOf(labels, "SECOND"))
    }

    @Test
    fun `a gesture repeated mid-dissolve reclaims its own slot`() {
        val labels = labels()
        labels.update(setOf("SCRATCH"), 0L)
        labels.update(emptySet(), 10L)
        labels.update(emptySet(), 160L)
        val fadingAlpha = labels.visible().single().alpha
        assertTrue("should be mid-dissolve, alpha was $fadingAlpha", fadingAlpha < 1f)

        labels.update(setOf("SCRATCH"), 200L)
        val reclaimed = labels.visible().single { it.text == "SCRATCH" }
        assertEquals("must retake the same slot", 0, reclaimed.slot)
        assertEquals("and return to full opacity", 1f, reclaimed.alpha, 1e-6f)
    }

    @Test
    fun `labels float upward from the moment they appear`() {
        val labels = labels()
        labels.update(setOf("SCRATCH"), 0L)
        val initial = labels.visible().single().riseFraction
        labels.update(setOf("SCRATCH"), 16L)
        labels.update(setOf("SCRATCH"), 32L)
        assertTrue(
            "should have risen from $initial",
            labels.visible().single().riseFraction > initial,
        )
    }

    @Test
    fun `a still-active label keeps rising`() {
        val labels = labels()
        var now = 0L
        labels.update(setOf("HELD"), now)
        repeat(30) {
            now += 16L
            labels.update(setOf("HELD"), now)
        }
        val label = labels.visible().single()
        assertTrue("should still be shown while held", label.alpha == 1f)
        assertTrue("and should have risen", label.riseFraction > 0.2f)
    }

    @Test
    fun `is idle when nothing is showing`() {
        val labels = labels()
        assertTrue(labels.isIdle())
        labels.update(setOf("A"), 0L)
        assertTrue(!labels.isIdle())
        labels.update(emptySet(), 10L)
        labels.update(emptySet(), 700L)
        assertTrue("should be idle again so animation can stop", labels.isIdle())
    }

    @Test
    fun `more gestures than slots does not crash`() {
        val labels = labels()
        val many = (1..20).map { "G$it" }.toSet()
        labels.update(many, 0L)
        assertTrue("cannot exceed slot count", labels.visible().size <= GestureLabels.CLOCK_SLOTS.size)
    }

    @Test
    fun `every visible label has a valid slot`() {
        val labels = labels()
        labels.update(setOf("A", "B", "C", "D"), 0L)
        for (label in labels.visible()) {
            assertTrue(label.slot in GestureLabels.CLOCK_SLOTS.indices)
            assertNotNull(label.text)
            assertTrue(label.alpha in 0f..1f)
        }
    }

    @Test
    fun `reset clears everything`() {
        val labels = labels()
        labels.update(setOf("A", "B"), 0L)
        labels.reset()
        assertTrue(labels.isIdle())
        assertTrue(labels.visible().isEmpty())
    }
}
