package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeatGridTest {

    @Test
    fun `beat and bar durations follow the tempo`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0)
        assertEquals(0.5, grid.beatDuration, 1e-12)
        assertEquals(2.0, grid.barDuration, 1e-12)
    }

    @Test
    fun `beat times respect phase`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.25)
        assertEquals(0.25, grid.beatTime(0), 1e-12)
        assertEquals(0.75, grid.beatTime(1), 1e-12)
        assertEquals(-0.25, grid.beatTime(-1), 1e-12)
    }

    @Test
    fun `beat index is the most recent beat`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0)
        assertEquals(0, grid.beatIndexAt(0.0))
        assertEquals(0, grid.beatIndexAt(0.49))
        assertEquals(1, grid.beatIndexAt(0.5))
        assertEquals(4, grid.beatIndexAt(2.1))
    }

    @Test
    fun `nearest beat rounds to the closer side`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0)
        assertEquals(0.0, grid.nearestBeatTime(0.2), 1e-12)
        assertEquals(0.5, grid.nearestBeatTime(0.3), 1e-12)
        assertEquals(2.0, grid.nearestBeatTime(1.9), 1e-12)
    }

    @Test
    fun `nearest bar snaps to downbeats`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0, downbeatOffset = 0)
        assertEquals(0.0, grid.nearestBarTime(0.4), 1e-12)
        assertEquals(2.0, grid.nearestBarTime(1.2), 1e-12)
        assertEquals(4.0, grid.nearestBarTime(3.5), 1e-12)
    }

    @Test
    fun `nearest bar honours a shifted downbeat`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0, downbeatOffset = 2)
        // Downbeats now fall on beats 2, 6, 10 -> 1.0 s, 3.0 s, 5.0 s.
        assertEquals(1.0, grid.nearestBarTime(1.2), 1e-12)
        assertEquals(3.0, grid.nearestBarTime(2.6), 1e-12)
    }

    @Test
    fun `identifies downbeats`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0, downbeatOffset = 1)
        assertTrue(!grid.isDownbeat(0))
        assertTrue(grid.isDownbeat(1))
        assertTrue(grid.isDownbeat(5))
        assertTrue(!grid.isDownbeat(2))
        assertTrue(grid.isDownbeat(-3))
    }

    @Test
    fun `enumerates beats within a duration`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.25)
        val times = grid.beatTimes(2.0)
        assertEquals(listOf(0.25, 0.75, 1.25, 1.75), times.toList())
    }

    @Test
    fun `enumerating from a negative phase skips beats before zero`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatSeconds = -0.3)
        val times = grid.beatTimes(1.0)
        assertTrue("first beat ${times.first()} must not be negative", times.first() >= 0.0)
        assertEquals(listOf(0.2, 0.7), times.toList().map { Math.round(it * 100) / 100.0 })
    }

    @Test
    fun `phase error is the correction needed to align two grids`() {
        val a = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0)
        val b = BeatGrid(bpm = 120.0, firstBeatSeconds = 0.1)

        val error = a.phaseErrorTo(b, atSeconds = 4.0)
        assertEquals(0.1, error, 1e-9)

        // Applying the correction nulls it out.
        val corrected = BeatGrid(bpm = 120.0, firstBeatSeconds = a.firstBeatSeconds + error)
        assertEquals(0.0, corrected.phaseErrorTo(b, atSeconds = 4.0), 1e-9)
    }

    @Test
    fun `phase error never exceeds half a beat`() {
        val a = BeatGrid(bpm = 128.0, firstBeatSeconds = 0.0)
        for (offset in 0..40) {
            val b = BeatGrid(bpm = 128.0, firstBeatSeconds = offset * 0.03)
            val error = a.phaseErrorTo(b, atSeconds = 10.0)
            assertTrue(
                "error $error exceeded half a beat for offset $offset",
                abs(error) <= a.beatDuration / 2 + 1e-9,
            )
        }
    }

    @Test
    fun `infers the downbeat from accented beats`() {
        // clickTrack accents beat 0 of every bar, so with a grid phase of zero
        // the inferred downbeat offset must be zero.
        val audio = SignalFixtures.clickTrack(120.0, 20.0)
        val envelope = OnsetDetector().detect(audio, SignalFixtures.SAMPLE_RATE)
        val offset = BeatGrid.inferDownbeat(envelope, bpm = 120.0, firstBeatSeconds = 0.0)
        assertEquals(0, offset)
    }

    @Test
    fun `infers a shifted downbeat`() {
        // Start the click track one beat late: the accent now lands on what the
        // grid calls beat 3 when the grid itself still starts at zero.
        val beatDuration = 60.0 / 120.0
        val audio = SignalFixtures.clickTrack(120.0, 20.0, firstBeatSeconds = beatDuration)
        val envelope = OnsetDetector().detect(audio, SignalFixtures.SAMPLE_RATE)
        val offset = BeatGrid.inferDownbeat(envelope, bpm = 120.0, firstBeatSeconds = 0.0)
        assertEquals(1, offset)
    }

    @Test
    fun `rejects invalid construction`() {
        for (block in listOf<() -> Unit>(
            { BeatGrid(bpm = 0.0, firstBeatSeconds = 0.0) },
            { BeatGrid(bpm = -120.0, firstBeatSeconds = 0.0) },
            { BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0, beatsPerBar = 0) },
            { BeatGrid(bpm = 120.0, firstBeatSeconds = 0.0, downbeatOffset = 4) },
        )) {
            try {
                block()
                throw AssertionError("expected rejection")
            } catch (expected: IllegalArgumentException) {
                // intended
            }
        }
    }
}
