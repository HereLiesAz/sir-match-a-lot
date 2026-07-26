package com.hereliesaz.sirmatchalot.ui.platter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a mark lands on the circle.
 *
 * The drawing is not testable from the JVM, but the arithmetic that decides
 * *where* is, and that is the part that can be wrong in a way nobody notices —
 * a cue drawn a few degrees off still looks like a cue.
 */
class PlatterMarkerTest {

    private fun marker(fraction: Float, deck: PlatterGeometry.Deck = PlatterGeometry.Deck.A) =
        PlatterMarker(deck, fraction, PlatterMarker.Kind.CUE, "Cue 1")

    @Test
    fun `a cue at the top of the revolution sits at twelve o'clock`() {
        assertEquals(
            PlatterGeometry.TWELVE_OCLOCK,
            PlatterGeometry.screenAngleForFraction(marker(0f).fraction),
            1e-6f,
        )
    }

    @Test
    fun `a cue halfway through the revolution sits opposite it`() {
        val angle = PlatterGeometry.screenAngleForFraction(marker(0.5f).fraction)
        assertEquals(PlatterGeometry.TWELVE_OCLOCK + Math.PI.toFloat(), angle, 1e-6f)
    }

    @Test
    fun `a cue past the end of the revolution wraps rather than running off`() {
        // A cue at 130 s on a 120 s circle is at 10 s, not off the edge —
        // decks hold several clips and a cue survives the circle growing.
        val fraction = PlatterGeometry.fractionForTime(130.0, 120.0)
        assertEquals(10.0 / 120.0, fraction.toDouble(), 1e-6)
    }

    @Test
    fun `a negative time wraps forwards`() {
        val fraction = PlatterGeometry.fractionForTime(-10.0, 120.0)
        assertEquals(110.0 / 120.0, fraction.toDouble(), 1e-6)
        assertTrue("a fraction must stay on the circle", fraction in 0f..1f)
    }

    @Test
    fun `an empty deck has no revolution to place a mark on`() {
        assertEquals(0f, PlatterGeometry.fractionForTime(30.0, 0.0), 0f)
    }

    @Test
    fun `markers are filtered by the ring they belong to`() {
        val state = PlatterState(
            markers = listOf(
                marker(0.1f, PlatterGeometry.Deck.A),
                marker(0.2f, PlatterGeometry.Deck.B),
                marker(0.3f, PlatterGeometry.Deck.A),
            ),
        )

        assertEquals(2, state.markersFor(PlatterGeometry.Deck.A).size)
        assertEquals(1, state.markersFor(PlatterGeometry.Deck.B).size)
    }

    @Test
    fun `a platter with no cues set draws no marks`() {
        assertTrue(PlatterState().markers.isEmpty())
    }
}
