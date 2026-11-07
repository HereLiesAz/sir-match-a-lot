package com.hereliesaz.sirmatchalot.audio

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.domain.BeatSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does applying a beat alignment actually put the beats together?
 *
 * The domain computes how far a deck's beats must move; the engine turns that into
 * a playhead nudge. The two disagreed about the sign — a playhead moved forward
 * makes every beat arrive *sooner* — so a correction of 0.1 s left the decks
 * 0.2 s apart. Nothing exercised the pair together, so these render two real decks
 * of clicks and compare where the clicks come out.
 */
class PhaseAlignmentTest {

    private val rate = 44_100

    /** A click, one full-scale frame, at [firstBeat] and every [beat] seconds after. */
    private fun clicks(firstBeat: Double, beat: Double, seconds: Double = 10.0): PcmBuffer {
        val samples = FloatArray((seconds * rate).toInt())
        var t = firstBeat
        while (t < seconds) {
            val frame = Math.round(t * rate).toInt()
            if (frame in samples.indices) samples[frame] = 1f
            t += beat
        }
        return PcmBuffer.monoFromFloat(samples, rate)
    }

    private fun deckOf(buffer: PcmBuffer): Deck =
        Deck("T", rate).apply {
            clips = listOf(Clip("clip", buffer))
            playing = true
        }

    /** Frames, in heard order, at which the deck's output has a click in its first [seconds]. */
    private fun onsets(deck: Deck, seconds: Double): List<Int> {
        val total = (seconds * rate).toInt()
        val block = 512
        val out = FloatArray(block * 2)
        val found = ArrayList<Int>()
        var rendered = 0
        while (rendered < total) {
            val n = minOf(block, total - rendered)
            deck.render(out, n)
            for (i in 0 until n) if (out[i * 2] > 0.5f) found.add(rendered + i)
            rendered += n
        }
        return found
    }

    private fun track(bpm: Double, firstBeat: Double) =
        Track(title = "t", artist = "a", bpm = bpm, firstBeatSeconds = firstBeat)

    @Test
    fun `a source whose beats are late is brought onto the target's`() {
        // Target clicks at 0, 0.5, 1.0 ...; source at 0.1, 0.6, 1.1 ...: 0.1 s late.
        val target = deckOf(clicks(firstBeat = 0.0, beat = 0.5))
        val source = deckOf(clicks(firstBeat = 0.1, beat = 0.5))
        val phase = BeatSync.phaseOffset(track(120.0, 0.1), track(120.0, 0.0), 120.0, 0.0)

        source.alignTo(1.0, phase)

        val expected = onsets(target, 4.0)
        val actual = onsets(source, 4.0)
        assertTrue("there should be clicks to compare", expected.size >= 7)
        assertEquals(expected, actual)
    }

    @Test
    fun `a source whose beats are early is brought onto the target's`() {
        // Source clicks at 0, 0.5 ...; target at 0.1, 0.6 ...: the source is 0.1 s early.
        val target = deckOf(clicks(firstBeat = 0.1, beat = 0.5))
        val source = deckOf(clicks(firstBeat = 0.0, beat = 0.5))
        val phase = BeatSync.phaseOffset(track(120.0, 0.0), track(120.0, 0.1), 120.0, 0.0)

        source.alignTo(1.0, phase)

        val expected = onsets(target, 4.0)
        val actual = onsets(source, 4.0)
        // The source is 0.1 s early, so its first click is moved from 0 to 0.1 s.
        assertTrue("there should be clicks to compare", expected.size >= 7)
        assertEquals(expected.take(7), actual.take(7))
    }

    @Test
    fun `the offset is a distance the beats move later, so the playhead goes the other way`() {
        val deck = deckOf(clicks(firstBeat = 0.0, beat = 0.5))

        deck.alignTo(1.0, 0.25)

        // Beats 0.25 s later: the playhead is 0.25 s *behind* where it was.
        assertEquals(-0.25 * rate + deck.cycleFrames, deck.playhead, 1e-6)
    }

    @Test
    fun `the nudge scales with the tempo ratio, because the offset is in heard seconds`() {
        val deck = deckOf(clicks(firstBeat = 0.0, beat = 0.5))
        deck.playhead = 5.0 * rate

        deck.alignTo(0.5, -0.2)

        // Running at half speed: 0.2 heard seconds is 0.1 s of material.
        assertEquals(5.0 * rate + 0.1 * rate, deck.playhead, 1e-6)
        assertEquals(0.5, deck.rate, 0.0)
    }

    @Test
    fun `an offset of zero leaves the playhead alone`() {
        val deck = deckOf(clicks(firstBeat = 0.0, beat = 0.5))
        deck.playhead = 3.0 * rate

        deck.alignTo(1.0, 0.0)

        assertEquals(3.0 * rate, deck.playhead, 0.0)
    }
}
