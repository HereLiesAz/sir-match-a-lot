package com.hereliesaz.sirmatchalot.ui.platter

import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the platter draws a clip.
 *
 * **Angle is time.** A clip is drawn at its own position on the deck's circular
 * timeline: its start divided by one revolution.
 *
 * The tests in this file used to assert the opposite — that clips divide the
 * revolution end to end in proportion to their durations — and the layout did
 * exactly that, never once reading a clip's start. Everything followed from it.
 * Dragging a clip changed its start frame, so the *audio* moved and the drawing
 * did not; the platter redrew the clip in precisely the place it had been. Beat
 * snapping worked and was invisible, because a clip that snapped a beat left was
 * still drawn where it always had been. Two clips stacked at the same instant
 * appeared side by side.
 *
 * So the old tests passed against a platter that could not show you what you had
 * just done. They are rewritten here against the model the engine has always
 * used, which is the one the app is documented as having.
 */
class PlatterStateTest {

    private fun peaks() = PeakEnvelope.compute(FloatArray(256) { 0.5f }, buckets = 32)

    private fun input(id: String, startSeconds: Double, seconds: Double) =
        PlatterState.ClipLayoutInput(
            id = id,
            title = id,
            startSeconds = startSeconds,
            durationSeconds = seconds,
            peaks = peaks(),
        )

    private fun layout(
        clips: List<PlatterState.ClipLayoutInput>,
        cycleSeconds: Double,
        selected: Set<String> = emptySet(),
    ) = PlatterState.layout(clips, selected, PlatterGeometry.Deck.A, cycleSeconds)

    // --- Position comes from the timeline ---

    @Test
    fun `a lone clip filling the revolution covers all of it`() {
        // "if the user adds a single sample to a deck and only plays that deck,
        // that sample is now looping, with only its waveform circumventing the
        // circle."
        val laid = layout(listOf(input("only", 0.0, 12.0)), cycleSeconds = 12.0)
        assertEquals(1, laid.size)
        assertEquals(0f, laid[0].startFraction, 1e-6f)
        assertEquals(1f, laid[0].spanFraction, 1e-6f)
    }

    @Test
    fun `a clip is drawn where it starts, not after the one before it`() {
        // The regression test for the whole file. Two clips a long way apart on
        // a two-minute revolution: under the old layout they sat flush against
        // each other at 0 and 0.25, whatever their actual starts.
        val laid = layout(
            listOf(input("first", 0.0, 30.0), input("later", 90.0, 30.0)),
            cycleSeconds = 120.0,
        )
        assertEquals(0f, laid[0].startFraction, 1e-5f)
        assertEquals(0.75f, laid[1].startFraction, 1e-5f)
    }

    @Test
    fun `moving a clip moves where it is drawn`() {
        // What "the waveforms don't move at all once they're placed" was.
        val before = layout(listOf(input("a", 0.0, 30.0)), cycleSeconds = 120.0)
        val after = layout(listOf(input("a", 60.0, 30.0)), cycleSeconds = 120.0)

        assertEquals(0f, before[0].startFraction, 1e-5f)
        assertEquals(0.5f, after[0].startFraction, 1e-5f)
    }

    @Test
    fun `a span is the clip's own length against the revolution`() {
        val laid = layout(
            listOf(input("quarter", 0.0, 30.0), input("half", 30.0, 60.0)),
            cycleSeconds = 120.0,
        )
        assertEquals(0.25f, laid[0].spanFraction, 1e-5f)
        assertEquals(0.5f, laid[1].spanFraction, 1e-5f)
    }

    @Test
    fun `clips need not cover the revolution`() {
        // The old model forced them to, by construction. A deck holding one
        // thirty-second clip on a two-minute circle has three quarters of the
        // circle empty, and should look like it.
        val laid = layout(listOf(input("a", 0.0, 30.0)), cycleSeconds = 120.0)
        assertEquals(0.25f, laid.sumOf { it.spanFraction.toDouble() }.toFloat(), 1e-5f)
    }

    @Test
    fun `two clips at the same instant are drawn at the same angle`() {
        // They overlap in sound; they have to overlap on the ring. Laying them
        // side by side was the drawing disagreeing with what is heard.
        val laid = layout(
            listOf(input("a", 40.0, 20.0), input("b", 40.0, 20.0)),
            cycleSeconds = 120.0,
        )
        assertEquals(laid[0].startFraction, laid[1].startFraction, 1e-6f)
    }

    @Test
    fun `a start past the revolution wraps onto the circle`() {
        val laid = layout(listOf(input("a", 150.0, 10.0)), cycleSeconds = 120.0)
        assertEquals(0.25f, laid[0].startFraction, 1e-5f)
    }

    @Test
    fun `a clip longer than the revolution covers it once`() {
        val laid = layout(listOf(input("a", 0.0, 300.0)), cycleSeconds = 120.0)
        assertEquals(1f, laid[0].spanFraction, 1e-6f)
    }

    @Test
    fun `no revolution falls back to the total duration rather than dividing by zero`() {
        val laid = layout(
            listOf(input("a", 0.0, 30.0), input("b", 30.0, 30.0)),
            cycleSeconds = 0.0,
        )
        assertEquals(2, laid.size)
        assertEquals(0f, laid[0].startFraction, 1e-5f)
        assertEquals(0.5f, laid[1].startFraction, 1e-5f)
    }

    // --- Identity and selection ---

    @Test
    fun `each clip gets a distinct palette index so colour identifies the song`() {
        val laid = layout(
            listOf(input("a", 0.0, 10.0), input("b", 10.0, 10.0), input("c", 20.0, 10.0)),
            cycleSeconds = 30.0,
        )
        assertEquals(listOf(0, 1, 2), laid.map { it.paletteIndex })
    }

    @Test
    fun `selection is carried through`() {
        val laid = layout(
            listOf(input("a", 0.0, 10.0), input("b", 10.0, 10.0)),
            cycleSeconds = 20.0,
            selected = setOf("b"),
        )
        assertTrue(!laid[0].selected)
        assertTrue(laid[1].selected)
    }

    @Test
    fun `an empty deck lays out to nothing`() {
        assertTrue(layout(emptyList(), cycleSeconds = 60.0).isEmpty())
    }

    @Test
    fun `zero durations on no revolution do not divide by zero`() {
        val laid = layout(
            listOf(input("a", 0.0, 0.0), input("b", 0.0, 0.0)),
            cycleSeconds = 0.0,
        )
        assertTrue(laid.isEmpty())
    }

    // --- Hit-testing ---

    @Test
    fun `a tap finds the clip actually at that angle`() {
        val state = PlatterState(
            deckA = layout(
                listOf(input("early", 0.0, 30.0), input("late", 60.0, 60.0)),
                cycleSeconds = 120.0,
            ),
        )
        assertEquals("early", state.clipAt(PlatterGeometry.Deck.A, 0.05f)?.id)
        assertEquals("late", state.clipAt(PlatterGeometry.Deck.A, 0.6f)?.id)
        assertNull("nothing is there", state.clipAt(PlatterGeometry.Deck.A, 0.4f))
    }

    @Test
    fun `a tap on an empty deck finds nothing`() {
        assertNull(PlatterState().clipAt(PlatterGeometry.Deck.B, 0.5f))
    }

    @Test
    fun `the two decks are addressed separately`() {
        val state = PlatterState(
            deckA = PlatterState.layout(
                listOf(input("outer", 0.0, 10.0)), emptySet(), PlatterGeometry.Deck.A, 10.0,
            ),
            deckB = PlatterState.layout(
                listOf(input("inner", 0.0, 10.0)), emptySet(), PlatterGeometry.Deck.B, 10.0,
            ),
        )
        assertEquals("outer", state.clipAt(PlatterGeometry.Deck.A, 0.3f)?.id)
        assertEquals("inner", state.clipAt(PlatterGeometry.Deck.B, 0.3f)?.id)
        assertEquals(1, state.clipsFor(PlatterGeometry.Deck.A).size)
        assertEquals(1, state.clipsFor(PlatterGeometry.Deck.B).size)
    }

    @Test
    fun `emptiness is reported`() {
        assertTrue(PlatterState().isEmpty)
        assertTrue(!PlatterState(deckA = layout(listOf(input("a", 0.0, 5.0)), 5.0)).isEmpty)
    }

    @Test
    fun `a clip is present at every angle it spans`() {
        val state = PlatterState(
            deckA = layout(
                listOf(input("a", 0.0, 25.0), input("b", 25.0, 75.0)),
                cycleSeconds = 100.0,
            ),
        )
        var fraction = 0f
        while (fraction < 1f) {
            assertNotNull("nothing at $fraction", state.clipAt(PlatterGeometry.Deck.A, fraction))
            fraction += 0.02f
        }
    }

    // --- The beat grid ---

    @Test
    fun `the grid has a line on every beat of the revolution`() {
        // 120 BPM is two beats a second; a sixteen-second revolution is 32 beats,
        // plus the line at the end of the turn.
        val grid = PlatterBeatGrid.of(bpm = 120.0, firstBeatSeconds = 0.0, cycleSeconds = 16.0)
        assertEquals(33, grid.beats.size)
        assertEquals(0f, grid.beats.first(), 1e-6f)
        assertEquals(1f, grid.beats.last(), 1e-6f)
    }

    @Test
    fun `bars are every fourth beat`() {
        val grid = PlatterBeatGrid.of(bpm = 120.0, firstBeatSeconds = 0.0, cycleSeconds = 16.0)
        assertEquals(9, grid.downbeats.size)
    }

    @Test
    fun `the grid starts on the measured first beat, not on frame zero`() {
        // A track whose first downbeat is half a second in has every line half a
        // second in. Drawing from zero would put the grid on the file rather
        // than on the music.
        val grid = PlatterBeatGrid.of(bpm = 120.0, firstBeatSeconds = 0.5, cycleSeconds = 16.0)
        assertEquals(0.5f / 16f, grid.beats.first(), 1e-5f)
    }

    @Test
    fun `an unmeasured tempo draws no grid rather than a guessed one`() {
        assertTrue(PlatterBeatGrid.of(null, 0.0, 16.0).isEmpty)
        assertTrue(PlatterBeatGrid.of(0.0, 0.0, 16.0).isEmpty)
        assertTrue(PlatterBeatGrid.of(120.0, 0.0, 0.0).isEmpty)
    }

    @Test
    fun `a revolution too long for beats still shows bars`() {
        // Ten minutes at 174 BPM is 1,740 beats — a solid ring at any real size.
        // Past the ceiling the beats become the bars, which is the information
        // that survives at that density.
        val grid = PlatterBeatGrid.of(
            bpm = 174.0,
            firstBeatSeconds = 0.0,
            cycleSeconds = 600.0,
            maximumBeats = 512,
        )
        assertTrue("bars should still be drawn", grid.downbeats.isNotEmpty())
        assertEquals("beats collapse onto the bars", grid.downbeats.size, grid.beats.size)
    }

    @Test
    fun `every line lands inside the revolution`() {
        val grid = PlatterBeatGrid.of(bpm = 128.0, firstBeatSeconds = 0.3, cycleSeconds = 45.0)
        assertTrue(grid.beats.all { it in 0f..1f })
        assertTrue(grid.downbeats.all { it in 0f..1f })
    }
}
