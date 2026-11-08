package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.LoopCandidate
import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A track's shape, as the mix director sees it.
 *
 * All of this was measured during analysis and cached long before any of it
 * reached the thing performing the mix. These tests are about the one question
 * the director actually asks of it — where can this track be left, and where can
 * it be joined — because those two answers are the difference between a set that
 * is planned and a set that is timed.
 */
class TrackStructureTest {

    private fun structure(
        duration: Double = 240.0,
        bpm: Double? = 120.0,
        points: List<PointOfInterest> = emptyList(),
        loops: List<LoopCandidate> = emptyList(),
    ) = TrackStructure(
        trackId = "t",
        durationSeconds = duration,
        bpm = bpm,
        firstBeatSeconds = 0.0,
        points = points,
        loops = loops,
    )

    private fun point(at: Double, kind: PointOfInterest.Kind, strength: Float = 1f) =
        PointOfInterest(at, kind, strength)

    private fun loop(start: Double, seconds: Double, score: Float = 0.9f) =
        LoopCandidate(start, bars = 8, durationSeconds = seconds, score = score)

    // --- Exits ---

    @Test
    fun `a breakdown outranks the end of the track`() {
        // The whole reason a set can be shorter than the sum of its songs: the
        // track has already taken itself away, so there is somewhere to leave.
        val exits = structure(points = listOf(point(150.0, PointOfInterest.Kind.BREAKDOWN)))
            .exits(after = 60.0)

        assertEquals(TrackStructure.Exit.Reason.BREAKDOWN, exits.first().reason)
        assertEquals(150.0, exits.first().atSeconds, 1e-6)
    }

    @Test
    fun `leaving on a drop means leaving after it, not on it`() {
        // Cutting on the downbeat of a drop sells the drop for nothing. The exit
        // is bars later, once it has actually landed.
        val s = structure(points = listOf(point(120.0, PointOfInterest.Kind.DROP)))
        val exit = s.exits(after = 60.0).first { it.reason == TrackStructure.Exit.Reason.AFTER_DROP }

        assertTrue("exit should follow the drop", exit.atSeconds > 120.0)
        assertEquals(120.0 + s.barSeconds * TrackStructure.DROP_PAYOFF_BARS, exit.atSeconds, 1e-6)
    }

    @Test
    fun `the end of the track is always available`() {
        // Even with nothing measured, there is always somewhere to go — the
        // director must never be handed an empty list and have to invent one.
        val exits = structure(points = emptyList()).exits(after = 60.0)
        assertEquals(1, exits.size)
        assertEquals(TrackStructure.Exit.Reason.TRACK_END, exits.single().reason)
        assertEquals(240.0, exits.single().atSeconds, 1e-6)
    }

    @Test
    fun `exits before the floor are not offered`() {
        // A track cut at forty seconds was not played, it was sampled.
        val exits = structure(points = listOf(point(40.0, PointOfInterest.Kind.BREAKDOWN)))
            .exits(after = 75.0)
        assertTrue(exits.none { it.reason == TrackStructure.Exit.Reason.BREAKDOWN })
    }

    @Test
    fun `a loop's seam is an exit`() {
        val exits = structure(loops = listOf(loop(100.0, 16.0))).exits(after = 60.0)
        assertTrue(exits.any { it.reason == TrackStructure.Exit.Reason.LOOP_END })
    }

    @Test
    fun `a weak breakdown still outranks a plain ending`() {
        // Quality is comparable across reasons on purpose; that comparison is
        // the entire decision.
        val exits = structure(
            points = listOf(point(150.0, PointOfInterest.Kind.BREAKDOWN, strength = 0.3f)),
        ).exits(after = 60.0)
        assertEquals(TrackStructure.Exit.Reason.BREAKDOWN, exits.first().reason)
    }

    // --- Entries ---

    @Test
    fun `a drop is entered a run-up before it, on a bar line`() {
        val s = structure(points = listOf(point(96.0, PointOfInterest.Kind.DROP)))
        val entries = s.entries()

        val runUp = 96.0 - s.barSeconds * TrackStructure.DROP_RUN_UP_BARS
        assertTrue("expected an entry near $runUp, got $entries", entries.any { abs(it - runUp) < 1e-6 })
    }

    @Test
    fun `the top of the track is always an option`() {
        assertTrue(structure(points = listOf(point(96.0, PointOfInterest.Kind.DROP))).entries().contains(0.0))
        assertEquals(listOf(0.0), structure(points = emptyList()).entries())
    }

    @Test
    fun `an entry past half way is not offered`() {
        // Coming in three quarters of the way through a track is not an entry,
        // it is skipping the track.
        val entries = structure(points = listOf(point(220.0, PointOfInterest.Kind.BUILD))).entries()
        assertEquals(listOf(0.0), entries)
    }

    @Test
    fun `an unmeasured track can only be entered from the top`() {
        val entries = structure(bpm = null, points = listOf(point(96.0, PointOfInterest.Kind.DROP)))
            .entries()
        assertEquals(listOf(0.0), entries)
    }

    // --- Usability and lookups ---

    @Test
    fun `a track with no tempo has no grid and is not usable`() {
        val s = structure(bpm = null)
        assertNull(s.grid)
        assertTrue(!s.isUsable)
        assertEquals(0.0, s.barSeconds, 1e-9)
    }

    @Test
    fun `bars are counted from the measured tempo`() {
        assertEquals(2.0, structure(bpm = 120.0).barSeconds, 1e-9)
    }

    @Test
    fun `the strongest earlier loop is what gets quoted`() {
        val s = structure(loops = listOf(loop(10.0, 8.0, 0.6f), loop(40.0, 8.0, 0.95f), loop(200.0, 8.0, 1f)))
        assertEquals(40.0, s.bestLoopBefore(150.0)?.startSeconds)
        assertNull("nothing has finished yet", s.bestLoopBefore(5.0))
    }

    @Test
    fun `the first drop after a moment is found`() {
        val s = structure(
            points = listOf(
                point(30.0, PointOfInterest.Kind.DROP),
                point(150.0, PointOfInterest.Kind.DROP),
            ),
        )
        assertEquals(150.0, s.firstPoint(PointOfInterest.Kind.DROP, after = 100.0)?.timeSeconds)
        assertNull(s.firstPoint(PointOfInterest.Kind.VOCAL))
    }

    // --- Playback time ---

    @Test
    fun `scaling moves every landmark into the time it will actually be heard at`() {
        // A track conformed to a faster session is physically stretched before it
        // is heard, so a drop measured at 120 seconds arrives earlier. Scaling
        // once here is what lets everything downstream treat these as wall clock.
        val s = structure(
            duration = 240.0,
            bpm = 120.0,
            points = listOf(point(120.0, PointOfInterest.Kind.DROP)),
            loops = listOf(loop(60.0, 16.0)),
        ).scaledBy(0.5)

        assertEquals(120.0, s.durationSeconds, 1e-9)
        assertEquals(240.0, s.bpm!!, 1e-9)
        assertEquals(60.0, s.points.single().timeSeconds, 1e-9)
        assertEquals(30.0, s.loops.single().startSeconds, 1e-9)
        assertEquals(8.0, s.loops.single().durationSeconds, 1e-9)
    }

    @Test
    fun `a ratio of one changes nothing at all`() {
        val s = structure(points = listOf(point(120.0, PointOfInterest.Kind.DROP)))
        assertEquals(s, s.scaledBy(1.0))
        assertEquals(s, s.scaledBy(0.0))
        assertEquals(s, s.scaledBy(Double.NaN))
    }

    @Test
    fun `structure comes off a track without a second analysis pass`() {
        val track = com.hereliesaz.sirmatchalot.data.Track(
            title = "Measured",
            artist = "Test",
            bpm = 128.0,
            firstBeatSeconds = 0.25,
            durationMs = 300_000,
        )
        val s = TrackStructure.of(track, points = listOf(point(60.0, PointOfInterest.Kind.DROP)))

        assertEquals(300.0, s.durationSeconds, 1e-9)
        assertEquals(0.25, s.firstBeatSeconds, 1e-9)
        assertNotNull(s.grid)
    }

    private fun abs(x: Double) = kotlin.math.abs(x)
}

/** Entry and exit points the user chose, which beat everything measured. */
class TrackStructurePinTest {

    private fun structure(
        pinnedEntry: Double? = null,
        pinnedExit: Double? = null,
        points: List<PointOfInterest> = emptyList(),
    ) = TrackStructure(
        trackId = "t",
        durationSeconds = 240.0,
        bpm = 120.0,
        firstBeatSeconds = 0.0,
        points = points,
        pinnedEntrySeconds = pinnedEntry,
        pinnedExitSeconds = pinnedExit,
    )

    @Test
    fun `a pinned entry comes first and sits on a bar line`() {
        // Bars are 2 s at 120 BPM, so 31.3 is moved to 32.
        val entries = structure(pinnedEntry = 31.3).entries()
        assertEquals(32.0, entries.first(), 1e-9)
        assertEquals(0.0, entries.last(), 0.0)
    }

    @Test
    fun `a pinned entry past the end of the track is ignored`() {
        assertEquals(listOf(0.0), structure(pinnedEntry = 500.0).entries())
    }

    @Test
    fun `a pinned entry that matches a measured one is listed once`() {
        val s = structure(
            pinnedEntry = 40.0,
            points = listOf(PointOfInterest(72.0, PointOfInterest.Kind.DROP, 1f)),
        )
        // The drop's run-up is 16 bars = 32 s before it: 40.0 as well.
        assertTrue("the measured entry should be 40.0", s.pinOptions().entries.any { it.seconds == 40.0 })
        assertEquals(1, s.entries().count { it == 40.0 })
    }

    @Test
    fun `a pinned exit beats a measured breakdown`() {
        val s = structure(
            pinnedExit = 100.0,
            points = listOf(PointOfInterest(150.0, PointOfInterest.Kind.BREAKDOWN, 1f)),
        )
        val exits = s.exits(after = 60.0)
        assertEquals(100.0, exits.first().atSeconds, 1e-9)
        assertTrue(exits.first().pinned)
        assertTrue("the measured exits stay available behind it", exits.any { it.atSeconds == 150.0 })
    }

    @Test
    fun `a pinned exit too early to leave is ignored`() {
        val exits = structure(pinnedExit = 30.0).exits(after = 60.0)
        assertTrue(exits.none { it.pinned })
    }

    @Test
    fun `pins are scaled with the rest of the structure`() {
        val s = structure(pinnedEntry = 32.0, pinnedExit = 100.0).scaledBy(1.25)
        assertEquals(40.0, s.pinnedEntrySeconds!!, 1e-9)
        assertEquals(125.0, s.pinnedExitSeconds!!, 1e-9)
    }

    @Test
    fun `pin options list the measured places without the pin`() {
        val s = structure(
            pinnedEntry = 10.0,
            pinnedExit = 100.0,
            points = listOf(
                PointOfInterest(72.0, PointOfInterest.Kind.DROP, 1f),
                PointOfInterest(150.0, PointOfInterest.Kind.BREAKDOWN, 1f),
            ),
        )
        val options = s.pinOptions()

        assertEquals(listOf(40.0, 0.0), options.entries.map { it.seconds })
        assertEquals("the start", options.entries.last().label)
        assertEquals(150.0, options.exits.first().seconds, 1e-9)
        assertEquals("breakdown", options.exits.first().label)
        assertEquals("end of the track", options.exits.last().label)
        assertTrue("a pin is not one of its own options", options.exits.none { it.seconds == 100.0 })
    }
}
