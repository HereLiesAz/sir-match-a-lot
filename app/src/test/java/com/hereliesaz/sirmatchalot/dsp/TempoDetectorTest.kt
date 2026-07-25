package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tempo is measured from the signal here. These tests are the difference between
 * this app and the previous one, whose BPM came from a hash of the filename.
 */
class TempoDetectorTest {

    private val onsets = OnsetDetector()
    private val detector = TempoDetector()

    private fun detect(bpm: Double, seconds: Double = 20.0, firstBeat: Double = 0.0): TempoEstimate {
        val audio = SignalFixtures.clickTrack(bpm, seconds, firstBeatSeconds = firstBeat)
        val envelope = onsets.detect(audio, SignalFixtures.SAMPLE_RATE)
        return requireNotNull(detector.detect(envelope)) { "no tempo detected for $bpm BPM" }
    }

    @Test
    fun `measures common dance tempi`() {
        // Restricted to tempi that are unambiguous under the octave prior: for
        // each of these, neither half nor double lies closer to the prior's
        // centre, so a correct detector has no excuse.
        for (bpm in listOf(90.0, 100.0, 110.0, 120.0, 128.0, 140.0)) {
            val estimate = detect(bpm)
            assertEquals("$bpm BPM click track", bpm, estimate.bpm, 3.0)
        }
    }

    @Test
    fun `is confident about a machine-precise click track`() {
        val estimate = detect(128.0)
        assertTrue("confidence was ${estimate.confidence}", estimate.confidence > 0.2f)
    }

    @Test
    fun `resolves fast tempi up to a metrical octave`() {
        // 174 BPM drum-and-bass is genuinely ambiguous with 87 BPM from
        // autocorrelation alone — both are correct readings of the same pulse.
        // The requirement is that the detector lands on the pulse grid, not that
        // it guesses which multiple a human would name.
        val estimate = detect(174.0)
        val candidates = listOf(174.0, 87.0)
        assertTrue(
            "detected ${estimate.bpm}, expected one of $candidates",
            candidates.any { abs(it - estimate.bpm) < 3.0 },
        )
    }

    @Test
    fun `finds the beat phase`() {
        val offset = 0.25
        val estimate = detect(120.0, firstBeat = offset)
        // Phase resolution is one STFT hop (~11.6 ms), and the flux peak lands
        // slightly before the click as the window first admits it.
        assertEquals(offset, estimate.firstBeatSeconds, 0.05)
    }

    @Test
    fun `phase is within one beat of zero`() {
        val estimate = detect(128.0)
        val beatDuration = 60.0 / estimate.bpm
        assertTrue(
            "phase ${estimate.firstBeatSeconds} should be inside one beat ($beatDuration)",
            estimate.firstBeatSeconds >= 0.0 && estimate.firstBeatSeconds < beatDuration + 1e-6,
        )
    }

    @Test
    fun `returns null for silence`() {
        val envelope = onsets.detect(FloatArray(SignalFixtures.SAMPLE_RATE * 5), SignalFixtures.SAMPLE_RATE)
        assertNull(detector.detect(envelope))
    }

    @Test
    fun `returns null for an envelope too short to be periodic`() {
        assertNull(detector.detect(OnsetEnvelope(FloatArray(4), 86.13)))
    }

    @Test
    fun `onset envelope spikes on beats and rests between them`() {
        val bpm = 120.0
        val audio = SignalFixtures.clickTrack(bpm, 10.0)
        val envelope = onsets.detect(audio, SignalFixtures.SAMPLE_RATE)
        assertNotNull(envelope)
        assertTrue("envelope was empty", envelope.size > 100)

        // Onset strength around each beat should exceed the strength midway
        // between beats.
        val beatDuration = 60.0 / bpm
        var beatsChecked = 0
        var beatsLouder = 0
        var beat = 1
        while (true) {
            val onBeat = envelope.frameOf(beat * beatDuration)
            val offBeat = envelope.frameOf((beat + 0.5) * beatDuration)
            if (offBeat >= envelope.size) break
            // Take a small neighbourhood, since the flux peak may straddle frames.
            fun peakNear(frame: Int): Float {
                var peak = 0f
                for (f in (frame - 2).coerceAtLeast(0)..(frame + 2).coerceAtMost(envelope.size - 1)) {
                    peak = maxOf(peak, envelope.values[f])
                }
                return peak
            }
            beatsChecked++
            if (peakNear(onBeat) > peakNear(offBeat)) beatsLouder++
            beat++
        }

        assertTrue("checked too few beats: $beatsChecked", beatsChecked > 10)
        assertTrue(
            "only $beatsLouder of $beatsChecked beats were louder than the gaps",
            beatsLouder > beatsChecked * 0.8,
        )
    }

    @Test
    fun `all values in the onset envelope are non negative and normalised`() {
        val audio = SignalFixtures.clickTrack(128.0, 10.0)
        val envelope = onsets.detect(audio, SignalFixtures.SAMPLE_RATE)
        var peak = 0f
        for (v in envelope.values) {
            assertTrue("negative onset value $v", v >= 0f)
            peak = maxOf(peak, v)
        }
        assertEquals(1f, peak, 1e-5f)
    }
}
