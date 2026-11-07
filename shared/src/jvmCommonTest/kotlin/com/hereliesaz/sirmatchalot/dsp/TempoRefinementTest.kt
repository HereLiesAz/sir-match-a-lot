package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A tempo good enough to keep a grid on the beat for a whole track.
 *
 * Autocorrelation gives the period to the nearest STFT frame plus a parabola, a
 * fraction of a percent off — and a fraction of a percent of a five-minute track
 * is seconds of drift: the grid is on the beat at the start and a bar out at the
 * end, which is no use to anything that aligns bars. The detector now fits a
 * line through where the beats actually fall.
 */
class TempoRefinementTest {

    private val onsets = OnsetDetector()
    private val detector = TempoDetector()

    private fun estimate(bpm: Double, seconds: Double, firstBeat: Double): TempoEstimate {
        val audio = SignalFixtures.clickTrack(bpm, seconds, firstBeatSeconds = firstBeat)
        val envelope = onsets.detect(audio, SignalFixtures.SAMPLE_RATE)
        return requireNotNull(detector.detect(envelope)) { "no tempo detected for $bpm BPM" }
    }

    @Test
    fun `tempo is accurate to a few hundredths of a BPM`() {
        for (bpm in listOf(90.0, 100.0, 110.0, 125.5, 128.0, 133.7, 140.3)) {
            val e = estimate(bpm, seconds = 120.0, firstBeat = 0.2)
            assertEquals("$bpm BPM", bpm, e.bpm, 0.05)
        }
    }

    @Test
    fun `the grid stays on the beat to the end of a long track`() {
        val seconds = 300.0
        for (bpm in listOf(100.0, 125.5, 128.0, 133.7)) {
            val first = 0.2
            val e = estimate(bpm, seconds, first)
            val trueBeat = 60.0 / bpm
            val lastIndex = ((seconds - 5.0 - first) / trueBeat).toInt()
            val trueTime = first + lastIndex * trueBeat
            val gridTime = e.firstBeatSeconds + Math.round((trueTime - e.firstBeatSeconds) / (60.0 / e.bpm)) * (60.0 / e.bpm)
            assertTrue(
                "$bpm BPM: grid is ${abs(gridTime - trueTime) * 1000} ms off after ${trueTime.toInt()} s",
                abs(gridTime - trueTime) < 0.04,
            )
        }
    }
}
