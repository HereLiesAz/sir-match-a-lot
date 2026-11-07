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

/** Where in its bar a clip is, as a starting deck needs to know it. */
class ClipMusicBarTest {

    private fun music(firstBeat: Double = 0.0, offset: Int = 0, perBar: Int = 4, bpm: Double? = 120.0) =
        ClipMusic(bpm = bpm, firstBeatSeconds = firstBeat, beatsPerBar = perBar, downbeatOffset = offset)

    @Test
    fun `time to the next bar counts down through the bar`() {
        // 120 BPM four four: a bar every 2 s, bar lines at 0, 2, 4 ...
        assertEquals(0.7, music().secondsToNextBar(13.3)!!, 1e-9)
        assertEquals(2.0 - 0.25, music().secondsToNextBar(0.25)!!, 1e-9)
    }

    @Test
    fun `on a bar line there is no wait`() {
        assertEquals(0.0, music().secondsToNextBar(14.0)!!, 0.0)
        assertEquals(0.0, music().secondsToNextBar(0.0)!!, 0.0)
    }

    @Test
    fun `bar lines follow the first beat and the downbeat offset`() {
        // First beat 0.3, downbeat on the third beat: bar lines at 0.3 + 1.0 + 2n = 1.3, 3.3 ...
        assertEquals(0.3, music(firstBeat = 0.3, offset = 2).secondsToNextBar(3.0)!!, 1e-9)
        // Before the first bar line still counts forward to it.
        assertEquals(1.3, music(firstBeat = 0.3, offset = 2).secondsToNextBar(0.0)!!, 1e-9)
    }

    @Test
    fun `a three four bar is three beats long`() {
        // 1.5 s a bar: 4.0 is 1.0 s past the line at 3.0, so 0.5 s to the next.
        assertEquals(0.5, music(perBar = 3).secondsToNextBar(4.0)!!, 1e-9)
    }

    @Test
    fun `with no measured tempo there is nothing to count`() {
        assertNull(music(bpm = null).secondsToNextBar(5.0))
        assertNull(music(bpm = 0.0).secondsToNextBar(5.0))
    }

    @Test
    fun `an entry already on a bar is kept and one off a bar moves to the next`() {
        val m = music(firstBeat = 0.3)
        // Bar lines at 0.3, 2.3, 4.3 ...
        assertEquals(4.3, m.barAtOrAfter(4.3), 0.0)
        assertEquals(4.3000001, m.barAtOrAfter(4.3000001), 0.0)
        assertEquals(0.3, m.barAtOrAfter(0.0), 1e-9)
        assertEquals(6.3, m.barAtOrAfter(4.5), 1e-9)
    }

    @Test
    fun `with no tempo the entry is left as it was`() {
        assertEquals(1.7, music(bpm = null).barAtOrAfter(1.7), 0.0)
    }

    @Test
    fun `the nearest beat follows the first beat and tempo`() {
        val m = music(firstBeat = 0.3)
        // Beats at 0.3, 0.8, 1.3 ...
        assertEquals(1.3, m.nearestBeat(1.1), 1e-9)
        assertEquals(0.8, m.nearestBeat(0.6), 1e-9)
        assertEquals(-0.2, m.nearestBeat(0.0), 1e-9)  // -0.2 is nearer than 0.3
        assertEquals(2.2, music(bpm = null).nearestBeat(2.2), 0.0)
    }
}
