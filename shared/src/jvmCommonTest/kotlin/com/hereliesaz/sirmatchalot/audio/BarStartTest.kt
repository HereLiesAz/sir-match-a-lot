package com.hereliesaz.sirmatchalot.audio

import com.hereliesaz.sirmatchalot.domain.ClipMusic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Starting a deck so its bars land on a deck that is already playing.
 *
 * The director used to start the incoming track at its entry point the instant a
 * timer fired, wherever in its bar the outgoing track happened to be, so a
 * handover that was planned bar to bar came out a random fraction of a bar out.
 * These render two real decks and compare where the clicks come out.
 */
class BarStartTest {

    private val sr = 44_100

    private fun clicks(beat: Double = 0.5, seconds: Double = 40.0): PcmBuffer {
        val samples = FloatArray((seconds * sr).toInt())
        var t = 0.0
        while (t < seconds) {
            val frame = Math.round(t * sr).toInt()
            if (frame in samples.indices) samples[frame] = 1f
            t += beat
        }
        return PcmBuffer.monoFromFloat(samples, sr)
    }

    private fun deckOf(): Deck = Deck("T", sr).apply {
        clips = listOf(Clip("clip", clicks()))
        playing = true
    }

    private fun onsets(deck: Deck, seconds: Double): List<Int> {
        val total = (seconds * sr).toInt()
        val out = FloatArray(512 * 2)
        val found = ArrayList<Int>()
        var done = 0
        while (done < total) {
            val n = minOf(512, total - done)
            deck.render(out, n)
            for (i in 0 until n) if (out[i * 2] > 0.5f) found.add(done + i)
            done += n
        }
        return found
    }

    @Test
    fun `an incoming deck started a bar-wait early puts its bars on the outgoing deck's`() {
        val music = ClipMusic(bpm = 120.0)
        val outgoing = deckOf().apply { playhead = 13.3 * sr }      // 0.7 s before its next bar
        val incoming = deckOf()

        val wait = music.secondsToNextBar(13.3)!!
        val entry = 20.0                                              // on a bar line of the incoming clip
        incoming.playhead = (entry - wait) * sr

        // The outgoing deck next clicks at 0.2 s and 0.7 s (its bar line); so must the incoming.
        val expected = onsets(outgoing, 4.0)
        val actual = onsets(incoming, 4.0)
        assertTrue(expected.size >= 7)
        assertEquals(expected, actual)
    }

    @Test
    fun `starting at the entry regardless leaves the decks out of step`() {
        val outgoing = deckOf().apply { playhead = 13.3 * sr }
        val incoming = deckOf().apply { playhead = 20.0 * sr }

        // 13.3 is 0.3 s into a half-second beat; 20.0 is on one. They never coincide.
        assertTrue(onsets(outgoing, 2.0) != onsets(incoming, 2.0))
    }
}
