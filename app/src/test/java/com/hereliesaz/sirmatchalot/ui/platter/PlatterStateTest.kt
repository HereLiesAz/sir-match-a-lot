package com.hereliesaz.sirmatchalot.ui.platter

import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatterStateTest {

    private fun peaks() = PeakEnvelope.compute(FloatArray(256) { 0.5f }, buckets = 32)

    private fun input(id: String, seconds: Double) =
        PlatterState.ClipLayoutInput(id = id, title = id, durationSeconds = seconds, peaks = peaks())

    @Test
    fun `a lone clip occupies the whole revolution`() {
        // "if the user adds a single sample to a deck and only plays that deck,
        // that sample is now looping, with only its waveform circumventing the
        // circle."
        val laid = PlatterState.layout(
            listOf(input("only", 12.0)),
            emptySet(),
            PlatterGeometry.Deck.A,
        )
        assertEquals(1, laid.size)
        assertEquals(0f, laid[0].startFraction, 1e-6f)
        assertEquals(1f, laid[0].spanFraction, 1e-6f)
    }

    @Test
    fun `clips divide the revolution in proportion to duration`() {
        val laid = PlatterState.layout(
            listOf(input("a", 60.0), input("b", 180.0)),
            emptySet(),
            PlatterGeometry.Deck.A,
        )
        assertEquals(0.25f, laid[0].spanFraction, 1e-5f)
        assertEquals(0.75f, laid[1].spanFraction, 1e-5f)
        assertEquals(0f, laid[0].startFraction, 1e-6f)
        assertEquals(0.25f, laid[1].startFraction, 1e-5f)
    }

    @Test
    fun `spans cover the revolution exactly once`() {
        val laid = PlatterState.layout(
            listOf(input("a", 30.0), input("b", 45.0), input("c", 15.0)),
            emptySet(),
            PlatterGeometry.Deck.A,
        )
        assertEquals(1f, laid.sumOf { it.spanFraction.toDouble() }.toFloat(), 1e-5f)
    }

    @Test
    fun `each clip gets a distinct palette index so colour identifies the song`() {
        val laid = PlatterState.layout(
            listOf(input("a", 10.0), input("b", 10.0), input("c", 10.0)),
            emptySet(),
            PlatterGeometry.Deck.A,
        )
        assertEquals(listOf(0, 1, 2), laid.map { it.paletteIndex })
    }

    @Test
    fun `selection is carried through`() {
        val laid = PlatterState.layout(
            listOf(input("a", 10.0), input("b", 10.0)),
            setOf("b"),
            PlatterGeometry.Deck.A,
        )
        assertTrue(!laid[0].selected)
        assertTrue(laid[1].selected)
    }

    @Test
    fun `an empty deck lays out to nothing`() {
        assertTrue(PlatterState.layout(emptyList(), emptySet(), PlatterGeometry.Deck.A).isEmpty())
    }

    @Test
    fun `zero total duration does not divide by zero`() {
        val laid = PlatterState.layout(
            listOf(input("a", 0.0), input("b", 0.0)),
            emptySet(),
            PlatterGeometry.Deck.A,
        )
        assertTrue(laid.isEmpty())
    }

    @Test
    fun `a tap finds the clip actually at that angle`() {
        // Tap-to-select must hit whatever waveform is there, not an index derived
        // from an even division — clips have unequal spans.
        val state = PlatterState(
            deckA = PlatterState.layout(
                listOf(input("short", 10.0), input("long", 90.0)),
                emptySet(),
                PlatterGeometry.Deck.A,
            ),
        )
        assertEquals("short", state.clipAt(PlatterGeometry.Deck.A, 0.05f)?.id)
        assertEquals("long", state.clipAt(PlatterGeometry.Deck.A, 0.5f)?.id)
        assertEquals("long", state.clipAt(PlatterGeometry.Deck.A, 0.99f)?.id)
    }

    @Test
    fun `a tap on an empty deck finds nothing`() {
        assertNull(PlatterState().clipAt(PlatterGeometry.Deck.B, 0.5f))
    }

    @Test
    fun `the two decks are addressed separately`() {
        val state = PlatterState(
            deckA = PlatterState.layout(listOf(input("outer", 10.0)), emptySet(), PlatterGeometry.Deck.A),
            deckB = PlatterState.layout(listOf(input("inner", 10.0)), emptySet(), PlatterGeometry.Deck.B),
        )
        assertEquals("outer", state.clipAt(PlatterGeometry.Deck.A, 0.3f)?.id)
        assertEquals("inner", state.clipAt(PlatterGeometry.Deck.B, 0.3f)?.id)
        assertEquals(1, state.clipsFor(PlatterGeometry.Deck.A).size)
        assertEquals(1, state.clipsFor(PlatterGeometry.Deck.B).size)
    }

    @Test
    fun `emptiness is reported`() {
        assertTrue(PlatterState().isEmpty)
        assertTrue(
            !PlatterState(
                deckA = PlatterState.layout(listOf(input("a", 5.0)), emptySet(), PlatterGeometry.Deck.A),
            ).isEmpty,
        )
    }

    @Test
    fun `a clip is present at every angle it spans`() {
        val state = PlatterState(
            deckA = PlatterState.layout(
                listOf(input("a", 25.0), input("b", 75.0)),
                emptySet(),
                PlatterGeometry.Deck.A,
            ),
        )
        var fraction = 0f
        while (fraction < 1f) {
            assertNotNull("nothing at $fraction", state.clipAt(PlatterGeometry.Deck.A, fraction))
            fraction += 0.02f
        }
    }
}
