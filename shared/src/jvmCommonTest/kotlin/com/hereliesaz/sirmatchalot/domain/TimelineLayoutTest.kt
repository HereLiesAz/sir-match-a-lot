package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Laying an ordered plan out on the master timeline.
 *
 * The rule under test is the one a DJ follows by ear: the outgoing track leaves
 * on a bar line, the incoming one enters on a bar line, and the two are a whole
 * number of bars apart — so that after the handover every bar stays together.
 */
class TimelineLayoutTest {

    private fun track(id: String, bpm: Double? = 120.0) = Track(
        id = id,
        title = id,
        artist = "artist",
        bpm = bpm,
        durationMs = 240_000L,
        camelotKey = "8A",
    )

    private fun shape(
        id: String,
        bpm: Double? = 120.0,
        firstBeat: Double = 0.0,
        downbeatOffset: Int = 0,
        points: List<PointOfInterest> = emptyList(),
    ) = TrackStructure(
        trackId = id,
        durationSeconds = 240.0,
        bpm = bpm,
        firstBeatSeconds = firstBeat,
        downbeatOffset = downbeatOffset,
        points = points,
    )

    private fun step(id: String, ratio: Double? = null) = MixStep(
        track = track(id),
        transition = null,
        alignment = ratio?.let { BeatAlignment(it, 0.0, 0, false) },
    )

    private fun layout(vararg steps: MixStep, shapes: Map<String, TrackStructure>): MixTimeline =
        TimelineLayout.layout(
            MixPlan(steps.toList(), skipped = emptyList(), averageScore = 0),
            TrackStructureProvider { shapes[it.id] },
        )

    private fun downbeats(clip: PlanClip) =
        clip.snapPoints(clip.masterIn, clip.masterOut).filter { it.isDownbeat }.map { it.timeSeconds }

    private fun assertBarsCoincide(a: PlanClip, b: PlanClip) {
        val from = b.masterIn
        val to = a.masterOut
        // A little slack either end: the last bar sits exactly on the outgoing
        // clip's end, where float noise decides which side of it it falls.
        val window = (from - 1e-6)..(to + 1e-6)
        val aBars = downbeats(a).filter { it in window }
        val bBars = downbeats(b).filter { it in window }
        assertTrue("there should be overlapping bars", aBars.isNotEmpty())
        assertEquals(aBars.size, bBars.size)
        for ((x, y) in aBars.zip(bBars)) assertEquals(x, y, 1e-6)
    }

    @Test
    fun `an empty plan is an empty timeline`() {
        val timeline = layout(shapes = emptyMap())

        assertTrue(timeline.clips.isEmpty())
        assertEquals(0.0, timeline.endSeconds, 0.0)
    }

    @Test
    fun `a single track is one clip and no transitions`() {
        val timeline = layout(step("a"), shapes = mapOf("a" to shape("a")))

        assertEquals(1, timeline.clips.size)
        assertTrue(timeline.transitions.isEmpty())
        assertEquals(0.0, timeline.clips[0].masterIn, 0.0)
    }

    @Test
    fun `two tracks at the same tempo have their bars on each other's`() {
        val timeline = layout(
            step("a"), step("b", ratio = 1.0),
            shapes = mapOf("a" to shape("a"), "b" to shape("b")),
        )
        val (a, b) = timeline.clips

        assertBarsCoincide(a, b)
        // No drop to size the blend by, so the default eight bars: 16 s at 120 BPM.
        val transition = timeline.transitions.single()
        assertEquals(TimelineLayout.DEFAULT_TRANSITION_BARS, transition.lengthBars)
        assertEquals(16.0, transition.lengthSeconds, 1e-9)
        // The outgoing track leaves as the blend ends.
        assertEquals(transition.endSeconds, a.masterOut, 1e-9)
        assertEquals(transition.startSeconds, b.masterIn, 1e-9)
    }

    @Test
    fun `bars still coincide when the grids start off the zero and the downbeat is not beat one`() {
        val timeline = layout(
            step("a"), step("b", ratio = 1.0),
            shapes = mapOf(
                "a" to shape("a", firstBeat = 0.3),
                "b" to shape("b", firstBeat = 0.3, downbeatOffset = 2),
            ),
        )
        val (a, b) = timeline.clips

        assertBarsCoincide(a, b)
    }

    @Test
    fun `an incoming track at another tempo is conformed and its bars still land`() {
        // 128 BPM played at 0.9375x is 120 BPM, the outgoing tempo.
        val timeline = layout(
            step("a"), step("b", ratio = 120.0 / 128.0),
            shapes = mapOf("a" to shape("a"), "b" to shape("b", bpm = 128.0)),
        )
        val (a, b) = timeline.clips

        assertEquals(120.0, b.playedBpm!!, 1e-9)
        assertBarsCoincide(a, b)
    }

    @Test
    fun `a drop in the incoming track lands as the outgoing track leaves`() {
        val timeline = layout(
            step("a"), step("b", ratio = 1.0),
            shapes = mapOf(
                "a" to shape("a"),
                "b" to shape("b", points = listOf(PointOfInterest(40.0, PointOfInterest.Kind.DROP, 1f))),
            ),
        )
        val (a, b) = timeline.clips

        // entries() puts the entry 16 bars (32 s) before the drop; the blend is 16 bars.
        assertEquals(16, timeline.transitions.single().lengthBars)
        val dropOnTimeline = b.toMaster(40.0)
        assertEquals(a.masterOut, dropOnTimeline, 1e-6)
        assertBarsCoincide(a, b)
    }

    @Test
    fun `the outgoing track leaves on a bar line, not on the raw end of the file`() {
        // First beat 0.3: bars at 0.3 + 2n, so 240.3 is the nearest bar to the end
        // but runs past it. The exit has to be the last bar line inside the file.
        val timeline = layout(
            step("a"), step("b", ratio = 1.0),
            shapes = mapOf("a" to shape("a", firstBeat = 0.3), "b" to shape("b", firstBeat = 0.3)),
        )
        val a = timeline.clips[0]

        assertEquals(238.3, a.outSeconds, 1e-9)
    }

    @Test
    fun `an unanalysed track gets a plain overlap instead of bars`() {
        val timeline = layout(
            step("a"), step("b"),
            shapes = mapOf("a" to shape("a"), "b" to shape("b", bpm = null)),
        )

        val transition = timeline.transitions.single()
        assertEquals(0, transition.lengthBars)
        assertEquals(TimelineLayout.FALLBACK_OVERLAP_SECONDS, transition.lengthSeconds, 1e-9)
    }

    @Test
    fun `a track with no structure still gets a clip from the track itself`() {
        val timeline = TimelineLayout.layout(
            MixPlan(listOf(step("a"), step("b", ratio = 1.0)), emptyList(), 0),
            TrackStructureProvider { null },
        )

        assertEquals(2, timeline.clips.size)
        assertEquals(240.0, timeline.clips[1].durationSeconds, 1e-9)
    }

    @Test
    fun `a three-track chain keeps every handover on the bar`() {
        val timeline = layout(
            step("a"), step("b", ratio = 1.0), step("c", ratio = 1.0),
            shapes = mapOf("a" to shape("a"), "b" to shape("b"), "c" to shape("c")),
        )

        assertEquals(2, timeline.transitions.size)
        assertBarsCoincide(timeline.clips[0], timeline.clips[1])
        assertBarsCoincide(timeline.clips[1], timeline.clips[2])
        // Each blend starts after the previous one has finished.
        assertTrue(timeline.transitions[1].startSeconds >= timeline.transitions[0].endSeconds)
    }

    @Test
    fun `the plan's tempo ratio and pitch shift are carried onto the clip`() {
        val shifted = MixStep(track("b"), null, BeatAlignment(1.05, 0.0, -2, false))
        val timeline = layout(
            step("a"), shifted,
            shapes = mapOf("a" to shape("a"), "b" to shape("b")),
        )

        assertEquals(1.05, timeline.clips[1].ratio, 1e-9)
        assertEquals(-2, timeline.clips[1].semitones)
    }

    @Test
    fun `a pinned entry and exit are where the clips come in and leave, on bars`() {
        val timeline = layout(
            step("a"), step("b", ratio = 1.0),
            shapes = mapOf(
                "a" to shape("a").copy(pinnedExitSeconds = 150.3),
                "b" to shape("b").copy(pinnedEntrySeconds = 45.1),
            ),
        )
        val (a, b) = timeline.clips

        // Bars are 2 s at 120 BPM: 150.3 -> 150, 45.1 -> 46.
        assertEquals(150.0, a.outSeconds, 1e-9)
        assertEquals(46.0, b.inSeconds, 1e-9)
        assertBarsCoincide(a, b)
    }
}
