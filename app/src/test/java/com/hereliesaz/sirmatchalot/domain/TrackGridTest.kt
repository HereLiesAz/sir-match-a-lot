package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.BeatGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting a beat grid out of a track.
 *
 * This file exists because of a crash report. Six call sites built a [BeatGrid]
 * from the same three columns by hand, and one of them did this:
 *
 * ```kotlin
 * BeatGrid(bpm, first, track.downbeatOffset)   // third parameter is beatsPerBar
 * ```
 *
 * A downbeat on beat one — the column default — became `beatsPerBar = 0` and
 * threw out of a load coroutine that caught only `OutOfMemoryError`, so opening
 * an analysed track killed the app.
 *
 * The tests below deliberately assert the **bar length**, not merely that
 * nothing was thrown. An offset of 1, 2 or 3 produced a perfectly legal
 * `BeatGrid` in 1/4, 2/4 or 3/4 time: no exception, no crash report, and every
 * landmark snapped to bar lines that are not in the music. A "does not throw"
 * test would have passed against that for as long as anyone cared to run it.
 */
class TrackGridTest {

    private fun track(
        bpm: Double? = 120.0,
        firstBeat: Double? = 0.0,
        downbeat: Int = 0,
    ) = Track(
        title = "Measured",
        artist = "Test",
        bpm = bpm,
        firstBeatSeconds = firstBeat,
        downbeatOffset = downbeat,
    )

    // --- The regression ---

    @Test
    fun `a downbeat on beat one does not become a time signature`() {
        // The crash, exactly. Offset 0 is the column default and what
        // BeatGrid.inferDownbeat returns whenever the downbeat lands first.
        val grid = track(downbeat = 0).beatGrid()
        assertNotNull(grid)
        assertEquals(4, grid!!.beatsPerBar)
        assertEquals(0, grid.downbeatOffset)
    }

    @Test
    fun `every downbeat offset gives four four and keeps its own phase`() {
        // The quiet half. Offsets 1, 2 and 3 never crashed; they silently built
        // 1_4, 2_4 and 3_4, so the bars were wrong and nothing said so.
        for (offset in 0..3) {
            val grid = track(downbeat = offset).beatGrid()
            assertNotNull("offset $offset produced no grid", grid)
            assertEquals("offset $offset changed the time signature", 4, grid!!.beatsPerBar)
            assertEquals("offset $offset lost its downbeat", offset, grid.downbeatOffset)
        }
    }

    @Test
    fun `a bar is four beats long, whatever the downbeat is`() {
        // Stated in seconds because that is what the consequence was: at 120 BPM
        // a bar is two seconds, and StructureFinder snaps every drop and
        // breakdown to a multiple of it.
        for (offset in 0..3) {
            assertEquals(2.0, track(bpm = 120.0, downbeat = offset).beatGrid()!!.barDuration, 1e-9)
        }
    }

    // --- Stored values that should not be able to crash anything ---

    @Test
    fun `an out-of-range stored offset is wrapped rather than thrown on`() {
        // A row read back from Room is data, not a programmer error. An offset
        // that arrived out of range should cost that track its downbeat phase,
        // not the session.
        assertEquals(1, track(downbeat = 5).beatGrid()!!.downbeatOffset)
        assertEquals(3, track(downbeat = -1).beatGrid()!!.downbeatOffset)
        assertEquals(0, track(downbeat = 400).beatGrid()!!.downbeatOffset)
    }

    @Test
    fun `wrapping treats an offset as the residue class it is`() {
        // 5 in four four is 1, which is a better answer than clamping to 3.
        assertEquals(1, BeatGrid.sanitiseDownbeat(5))
        assertEquals(3, BeatGrid.sanitiseDownbeat(-5))
        assertEquals(0, BeatGrid.sanitiseDownbeat(0))
        assertEquals(0, BeatGrid.sanitiseDownbeat(7, beatsPerBar = 7))
        assertEquals(0, BeatGrid.sanitiseDownbeat(3, beatsPerBar = 0))
    }

    // --- Not measured is an answer ---

    @Test
    fun `a track with no tempo has no grid`() {
        assertNull(track(bpm = null).beatGrid())
        assertNull(track(bpm = 0.0).beatGrid())
        assertNull(track(bpm = -1.0).beatGrid())
    }

    @Test
    fun `placing something on a bar needs a phase as well as a tempo`() {
        // beatGrid falls back to a phase of zero so the bars are the right
        // length; measuredBeatGrid refuses, because laying bar lines on frame
        // zero of the file is a different claim from measuring them.
        assertNotNull(track(firstBeat = null).beatGrid())
        assertNull(track(firstBeat = null).measuredBeatGrid())
        assertNotNull(track(firstBeat = 0.25).measuredBeatGrid())
    }

    @Test
    fun `the measured phase is carried through`() {
        assertEquals(0.25, track(firstBeat = 0.25).measuredBeatGrid()!!.firstBeatSeconds, 1e-9)
    }

    // --- The consumer that was affected ---

    @Test
    fun `bar snapping lands on real bars`() {
        // TrackStructure.nearestBar is what cues, loops and transitions are
        // placed with. Under the old grid a 1_4 track snapped to every beat, so
        // "snapped to the bar" and "snapped to the nearest beat" were the same
        // operation and nothing looked wrong.
        val grid = requireNotNull(track(bpm = 120.0, firstBeat = 0.0, downbeat = 0).measuredBeatGrid())
        assertEquals(4.0, grid.nearestBarTime(4.4), 1e-9)
        assertEquals(4.0, grid.nearestBarTime(3.6), 1e-9)
        assertTrue(grid.nearestBarTime(5.0) % 2.0 < 1e-9)
    }

    @Test
    fun `a track with no grid has no bar to snap to`() {
        assertNull(track(bpm = null).measuredBeatGrid())
        assertNull(track(firstBeat = null).measuredBeatGrid())
    }
}
