package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether the beats come in threes or fours.
 *
 * Synthetic click tracks only: an accent on every Nth beat. Real music has not
 * been run through this, which is why the thresholds lean hard toward four.
 */
class MeterTest {

    private val onsets = OnsetDetector()

    private fun meter(bpm: Double, perBar: Int, seconds: Double, firstBeat: Double = 0.0): Int {
        val audio = SignalFixtures.clickTrack(bpm, seconds, firstBeatSeconds = firstBeat, beatsPerBar = perBar)
        val envelope = onsets.detect(audio, SignalFixtures.SAMPLE_RATE)
        return BeatGrid.inferBeatsPerBar(envelope, bpm, firstBeat)
    }

    @Test
    fun `accents every third beat read as three`() {
        for (bpm in listOf(90.0, 100.0, 120.0, 140.0)) {
            assertEquals("$bpm BPM", 3, meter(bpm, perBar = 3, seconds = 60.0))
        }
    }

    @Test
    fun `accents every fourth beat read as four`() {
        for (bpm in listOf(90.0, 100.0, 120.0, 140.0)) {
            assertEquals("$bpm BPM", 4, meter(bpm, perBar = 4, seconds = 60.0))
        }
    }

    @Test
    fun `the phase of the first beat does not change the reading`() {
        assertEquals(3, meter(110.0, perBar = 3, seconds = 60.0, firstBeat = 0.37))
        assertEquals(4, meter(110.0, perBar = 4, seconds = 60.0, firstBeat = 0.37))
    }

    @Test
    fun `a track with no accent is four`() {
        // One beat in a bar of one: every beat equally loud.
        assertEquals(4, meter(120.0, perBar = 1, seconds = 60.0))
    }

    @Test
    fun `too few beats to read are four`() {
        // 8 s at 120 BPM is 16 beats, under the 24 needed.
        assertEquals(4, meter(120.0, perBar = 3, seconds = 8.0))
    }

    @Test
    fun `an empty envelope is four`() {
        val envelope = onsets.detect(FloatArray(0), SignalFixtures.SAMPLE_RATE)
        assertEquals(4, BeatGrid.inferBeatsPerBar(envelope, 120.0, 0.0))
    }
}
