package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placing and snapping clips on the master timeline.
 *
 * The owner's complaint was that aligning two tracks is impossible: nothing
 * pulls a bar onto a bar, a drop onto a drop, or beat four onto beat two. These
 * pin each of those against two real clips, not against bare points.
 */
class MixTimelineTest {

    /** 120 BPM in four four: a beat every 0.5 s, a bar every 2 s. */
    private fun clip(
        id: String,
        start: Double,
        firstBeat: Double = 0.0,
        ratio: Double = 1.0,
        bpm: Double? = 120.0,
        markers: List<PlanMarker> = emptyList(),
    ) = PlanClip(
        id = id,
        trackId = id,
        durationSeconds = 120.0,
        bpm = bpm,
        firstBeatSeconds = firstBeat,
        startSeconds = start,
        ratio = ratio,
        markers = markers,
    )

    private fun downbeats(clip: PlanClip, from: Double, to: Double) =
        clip.snapPoints(from, to).filter { it.isDownbeat }.map { it.timeSeconds }

    // --- The clock ---

    @Test
    fun `file time reaches the timeline through the playback ratio`() {
        val clip = clip("a", start = 10.0, ratio = 2.0)

        // Twice as fast: 8 s of file is 4 s of timeline.
        assertEquals(14.0, clip.toMaster(8.0), 1e-9)
        assertEquals(8.0, clip.toFile(14.0), 1e-9)
    }

    @Test
    fun `a marker is drawn where the stretched audio puts it, not where the file did`() {
        val clip = clip("a", start = 0.0, ratio = 1.0 / 1.2, markers = listOf(PlanMarker(60.0, PointOfInterest.Kind.DROP)))

        val drop = clip.snapPoints(0.0, 200.0).single { it.kind == SnapPoint.Kind.MARKER }

        // Played 1.2x slower, a drop at 60 s of file is at 72 s of timeline.
        assertEquals(72.0, drop.timeSeconds, 1e-9)
    }

    @Test
    fun `played tempo and bar follow the ratio`() {
        val clip = clip("a", start = 0.0, ratio = 1.05)

        assertEquals(126.0, clip.playedBpm!!, 1e-9)
        assertEquals(2.0 / 1.05, clip.playedBarSeconds!!, 1e-9)
    }

    // --- Snapping ---

    @Test
    fun `dragging a clip near a bar line puts its bars on the other clip's bars`() {
        val a = clip("a", start = 0.0)
        // First beat 0.1 s into the file: its downbeat is at start + 0.1.
        val b = clip("b", start = 5.85, firstBeat = 0.1)
        val timeline = MixTimeline(listOf(a, b))

        val moved = timeline.snapMove("b", proposedStartSeconds = 5.85)

        assertEquals(SnapTier.STRONG, moved.snap!!.tier)
        assertEquals(5.9, moved.startSeconds, 1e-9)
        // Every downbeat of the moved clip now coincides with one of A's.
        val placed = b.movedTo(moved.startSeconds)
        val aBars = downbeats(a, 0.0, 30.0)
        for (bar in downbeats(placed, 6.0, 30.0)) {
            assertTrue("$bar is not a bar of A", aBars.any { Math.abs(it - bar) < 1e-9 })
        }
    }

    @Test
    fun `beat four on beat two is caught, but as a medium snap`() {
        val a = clip("a", start = 0.0)
        val b = clip("b", start = 4.93, firstBeat = 0.1)
        val timeline = MixTimeline(listOf(a, b))

        val moved = timeline.snapMove("b", proposedStartSeconds = 4.93)

        assertEquals(SnapTier.MEDIUM, moved.snap!!.tier)
        assertEquals(4.9, moved.startSeconds, 1e-9)
    }

    @Test
    fun `a clip dragged over empty timeline is left alone`() {
        val a = clip("a", start = 0.0)
        val b = clip("b", start = 500.0)
        val timeline = MixTimeline(listOf(a, b))

        val moved = timeline.snapMove("b", proposedStartSeconds = 500.03)

        assertNull(moved.snap)
        assertEquals(500.03, moved.startSeconds, 0.0)
    }

    @Test
    fun `zero radius moves freely`() {
        val timeline = MixTimeline(listOf(clip("a", start = 0.0), clip("b", start = 5.85, firstBeat = 0.1)))

        val moved = timeline.snapMove("b", proposedStartSeconds = 5.85, radiusBeats = 0.0)

        assertNull(moved.snap)
        assertEquals(5.85, moved.startSeconds, 0.0)
    }

    @Test
    fun `drops snap together across a tempo conform`() {
        // A's drop is at 30 s of file. B plays 5% fast, with its drop at 20 s of
        // file. B has no grid here, so the drop is the only thing pulling and the
        // test is about the marker's time, not about which beat happens to be near.
        val a = clip("a", start = 0.0, markers = listOf(PlanMarker(30.0, PointOfInterest.Kind.DROP)))
        val b = clip(
            "b",
            start = 10.0,
            ratio = 1.05,
            bpm = null,
            markers = listOf(PlanMarker(20.0, PointOfInterest.Kind.DROP)),
        )
        // Put B's drop 0.2 s late: start so that start + 20/1.05 = 30.2.
        val proposed = 30.2 - 20.0 / 1.05
        val timeline = MixTimeline(listOf(a, b))

        val moved = timeline.snapMove("b", proposedStartSeconds = proposed)

        assertNotNull(moved.snap)
        assertEquals(SnapTier.STRONG, moved.snap!!.tier)
        assertEquals(a.toMaster(30.0), b.movedTo(moved.startSeconds).toMaster(20.0), 1e-9)
    }

    @Test
    fun `a clip with no grid still snaps by marker`() {
        val a = clip("a", start = 0.0, markers = listOf(PlanMarker(30.0, PointOfInterest.Kind.DROP)))
        val b = clip(
            "b",
            start = 9.9,
            bpm = null,
            markers = listOf(PlanMarker(20.0, PointOfInterest.Kind.DROP)),
        )

        val moved = MixTimeline(listOf(a, b)).snapMove("b", proposedStartSeconds = 9.9)

        assertEquals(10.0, moved.startSeconds, 1e-9)
    }

    @Test
    fun `an unknown clip is returned as proposed`() {
        val moved = MixTimeline(listOf(clip("a", start = 0.0))).snapMove("nope", 12.0)

        assertNull(moved.snap)
        assertEquals(12.0, moved.startSeconds, 0.0)
    }

    // --- The model ---

    @Test
    fun `only the heard part of a clip contributes points`() {
        val clip = clip("a", start = 0.0).copy(inSeconds = 20.0, outSeconds = 30.0)

        val beats = clip.snapPoints(0.0, 100.0)

        assertEquals(20.0, beats.first().timeSeconds, 1e-9)
        assertEquals(30.0, beats.last().timeSeconds, 1e-9)
    }

    @Test
    fun `beat positions count from the downbeat offset`() {
        val clip = clip("a", start = 0.0).copy(downbeatOffset = 1)

        val positions = clip.snapPoints(0.0, 2.0).filter { it.kind == SnapPoint.Kind.BEAT }
            .associate { it.timeSeconds to it.barPosition }

        // Beat index 1 (t = 0.5) carries the downbeat.
        assertEquals(0, positions[0.5])
        assertEquals(3, positions[0.0])
    }

    @Test
    fun `clips at a time are the ones audible there`() {
        val a = clip("a", start = 0.0).copy(outSeconds = 60.0)
        val b = clip("b", start = 50.0)
        val timeline = MixTimeline(listOf(a, b))

        assertEquals(listOf("a"), timeline.clipsAt(10.0).map { it.id })
        assertEquals(listOf("a", "b"), timeline.clipsAt(55.0).map { it.id })
        assertEquals(listOf("b"), timeline.clipsAt(100.0).map { it.id })
    }
}
