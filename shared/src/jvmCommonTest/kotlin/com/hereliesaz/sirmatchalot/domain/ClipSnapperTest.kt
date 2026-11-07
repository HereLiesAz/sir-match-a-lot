package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapping a dropped or dragged clip onto the clips on both decks, in frames.
 *
 * The engine thinks in frames and one reference grid; the timeline thinks in
 * seconds and every clip's own grid. These pin the bridge between them.
 */
class ClipSnapperTest {

    private val rate = 44_100

    private fun music(firstBeat: Double = 0.0, bpm: Double? = 120.0) =
        ClipMusic(bpm = bpm, firstBeatSeconds = firstBeat)

    private fun placed(id: String, startSeconds: Double, lengthSeconds: Double = 60.0, music: ClipMusic = music()) =
        PlacedClip(id, (startSeconds * rate).toInt(), (lengthSeconds * rate).toInt(), music)

    private fun frames(seconds: Double) = seconds * rate

    @Test
    fun `a clip dropped near a bar line lands its bars on the other deck's`() {
        val a = placed("a", 0.0)
        // Its first beat is 0.1 s into the clip, dropped so that beat is 0.05 s early.
        val b = placed("b", 5.85, music = music(firstBeat = 0.1))

        val result = ClipSnapper.snap(b, listOf(a), rate)

        assertEquals(SnapTier.STRONG, result.snap!!.tier)
        assertEquals(frames(5.9), result.startFrame.toDouble(), 1.0)
        assertTrue(result.overlapsOthers)
    }

    @Test
    fun `a stretched clip is snapped by where its beats are now, not where they were`() {
        // 140 BPM stretched to 120: its 0.1 s first beat is now at 0.1 * 140/120.
        val conformed = ClipMusic.conformed(
            bpm = 140.0, firstBeatSeconds = 0.1, downbeatOffset = 0, markers = emptyList(), scale = 140.0 / 120.0,
        )
        assertEquals(120.0, conformed.bpm!!, 1e-9)
        assertEquals(0.1 * 140.0 / 120.0, conformed.firstBeatSeconds, 1e-9)

        val a = placed("a", 0.0)
        // Drop it so its first beat (its downbeat) lands 0.05 s after A's bar at 6.0.
        val dropAt = 6.05 - conformed.firstBeatSeconds
        val b = placed("b", dropAt, music = conformed)

        val result = ClipSnapper.snap(b, listOf(a), rate)

        val downbeatNow = result.startFrame.toDouble() / rate + conformed.firstBeatSeconds
        assertEquals(6.0, downbeatNow, 2.0 / rate)
    }

    @Test
    fun `markers are scaled with the clip`() {
        val conformed = ClipMusic.conformed(
            bpm = 120.0,
            firstBeatSeconds = 0.0,
            downbeatOffset = 0,
            markers = listOf(PlanMarker(60.0, PointOfInterest.Kind.DROP)),
            scale = 1.2,
        )

        assertEquals(72.0, conformed.markers.single().fileSeconds, 1e-9)
    }

    @Test
    fun `drops snap to each other across the decks`() {
        val a = placed(
            "a", 0.0,
            music = ClipMusic(bpm = null, markers = listOf(PlanMarker(30.0, PointOfInterest.Kind.DROP))),
        )
        val b = placed(
            "b", 9.9,
            music = ClipMusic(bpm = null, markers = listOf(PlanMarker(20.0, PointOfInterest.Kind.DROP))),
        )

        // Neither has a grid, so there is no beat length to size the reach from.
        val result = ClipSnapper.snap(b, listOf(a), rate)

        assertNull(result.snap)
        assertEquals(b.startFrame, result.startFrame)
    }

    @Test
    fun `drops snap when a grid supplies the reach`() {
        val a = placed(
            "a", 0.0,
            music = ClipMusic(bpm = 120.0, markers = listOf(PlanMarker(30.0, PointOfInterest.Kind.DROP))),
        )
        val b = placed(
            "b", 9.9,
            music = ClipMusic(bpm = null, markers = listOf(PlanMarker(20.0, PointOfInterest.Kind.DROP))),
        )

        val result = ClipSnapper.snap(b, listOf(a), rate)

        assertEquals(frames(10.0), result.startFrame.toDouble(), 1.0)
    }

    @Test
    fun `a clip over empty timeline is left alone and reports it`() {
        val a = placed("a", 0.0, lengthSeconds = 60.0)
        val b = placed("b", 500.03, music = music(firstBeat = 0.1))

        val result = ClipSnapper.snap(b, listOf(a), rate)

        assertNull(result.snap)
        assertFalse(result.overlapsOthers)
        assertEquals(b.startFrame, result.startFrame)
    }

    @Test
    fun `with nothing else on the decks there is nothing to snap to`() {
        val result = ClipSnapper.snap(placed("b", 12.0), emptyList(), rate)

        assertNull(result.snap)
        assertFalse(result.overlapsOthers)
    }

    @Test
    fun `zero radius leaves the clip where it is`() {
        val a = placed("a", 0.0)
        val b = placed("b", 5.85, music = music(firstBeat = 0.1))

        val result = ClipSnapper.snap(b, listOf(a), rate, radiusBeats = 0.0)

        assertNull(result.snap)
        assertEquals(b.startFrame, result.startFrame)
    }

    @Test
    fun `an unusable sample rate changes nothing`() {
        val b = placed("b", 5.85)

        assertEquals(b.startFrame, ClipSnapper.snap(b, listOf(placed("a", 0.0)), 0).startFrame)
    }
}
