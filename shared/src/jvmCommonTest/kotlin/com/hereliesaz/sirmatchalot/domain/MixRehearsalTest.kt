package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.audio.Clip
import com.hereliesaz.sirmatchalot.audio.Deck
import com.hereliesaz.sirmatchalot.audio.PcmBuffer
import com.hereliesaz.sirmatchalot.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A planned mix, rehearsed on real decks.
 *
 * The unit tests pin each piece — snapping, layout, bar arithmetic. This plays
 * the whole thing: two tracks laid out by [TimelineLayout], each rendered into a
 * [Deck], started the way the timeline says, and the bar lines compared in the
 * audio that comes out. Each track is a click with the downbeat louder than the
 * other beats, so "bar one of this on bar one of that" is something a test can
 * hear rather than something it assumes.
 */
class MixRehearsalTest {

    private val sr = 44_100

    private val firstBeats = mapOf("a" to 0.3, "b" to 0.45)
    private val offsets = mapOf("a" to 0, "b" to 1)

    /** 120 BPM clicks; the downbeat is full scale, the other beats half. */
    private fun audio(id: String, seconds: Double): PcmBuffer {
        val samples = FloatArray((seconds * sr).toInt())
        val beat = 0.5
        var k = 0
        while (true) {
            val t = firstBeats.getValue(id) + k * beat
            if (t >= seconds) break
            val frame = Math.round(t * sr).toInt()
            val downbeat = ((k - offsets.getValue(id)) % 4 + 4) % 4 == 0
            if (frame in samples.indices) samples[frame] = if (downbeat) 1f else 0.5f
            k++
        }
        return PcmBuffer.monoFromFloat(samples, sr)
    }

    private fun track(id: String) = Track(id = id, title = id, artist = "x", bpm = 120.0, durationMs = 60_000, camelotKey = "8A")

    private fun shape(id: String) = TrackStructure(
        trackId = id,
        durationSeconds = 60.0,
        bpm = 120.0,
        firstBeatSeconds = firstBeats.getValue(id),
        downbeatOffset = offsets.getValue(id),
    )

    /** Frames, relative to the start of the render, of every full-scale click. */
    private fun downbeats(deck: Deck, seconds: Double): List<Int> {
        val total = (seconds * sr).toInt()
        val out = FloatArray(512 * 2)
        val found = ArrayList<Int>()
        var done = 0
        while (done < total) {
            val n = minOf(512, total - done)
            deck.render(out, n)
            for (i in 0 until n) if (out[i * 2] > 0.9f) found.add(done + i)
            done += n
        }
        return found
    }

    @Test
    fun `a planned handover puts the bars of both decks on the same frames`() {
        val plan = MixPlan(
            steps = listOf(
                MixStep(track("a"), null, null),
                MixStep(track("b"), null, BeatAlignment(1.0, 0.0, 0, false)),
            ),
            skipped = emptyList(),
            averageScore = 0,
        )
        val timeline = TimelineLayout.layout(plan, TrackStructureProvider { shape(it.id) })
        val (a, b) = timeline.clips
        val transition = timeline.transitions.single()

        // Both decks as the timeline says they should be at the moment the blend begins.
        val deckA = Deck("A", sr).apply {
            clips = listOf(Clip("a", audio("a", 60.0)))
            playing = true
            playhead = a.toFile(transition.startSeconds) * sr
        }
        val deckB = Deck("B", sr).apply {
            clips = listOf(Clip("b", audio("b", 60.0)))
            playing = true
            playhead = b.inSeconds * sr
        }

        val seconds = transition.lengthSeconds
        val fromA = downbeats(deckA, seconds)
        val fromB = downbeats(deckB, seconds)

        assertTrue("a ${seconds}s blend should contain several bars", fromA.size >= 6)
        assertEquals(fromA.size, fromB.size)
        for ((x, y) in fromA.zip(fromB)) assertEquals("bar lines are $x and $y frames in", x.toDouble(), y.toDouble(), 2.0)
    }

    @Test
    fun `the same two tracks started without the layout are not on the same bars`() {
        // The control: both decks started at their file starts, as a bare Start would.
        val deckA = Deck("A", sr).apply { clips = listOf(Clip("a", audio("a", 60.0))); playing = true }
        val deckB = Deck("B", sr).apply { clips = listOf(Clip("b", audio("b", 60.0))); playing = true }

        assertTrue(downbeats(deckA, 8.0) != downbeats(deckB, 8.0))
    }
}
