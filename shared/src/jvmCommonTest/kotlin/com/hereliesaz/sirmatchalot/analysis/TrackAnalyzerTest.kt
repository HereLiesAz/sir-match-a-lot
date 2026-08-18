package com.hereliesaz.sirmatchalot.analysis

import com.hereliesaz.sirmatchalot.audio.PcmBuffer
import com.hereliesaz.sirmatchalot.dsp.SignalFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipeline that replaced fabricated track metadata.
 *
 * These tests exist to make one property impossible to regress: a value is
 * reported only when it was measured from the signal. The implementation this
 * replaced derived BPM from `110 + (filenameCharSum % 40)` and would happily
 * report a confident tempo for silence.
 */
class TrackAnalyzerTest {

    private val analyzer = TrackAnalyzer()
    private val sampleRate = SignalFixtures.SAMPLE_RATE

    private fun pcmOf(samples: FloatArray) = PcmBuffer.monoFromFloat(samples, sampleRate)

    @Test
    fun `measures tempo from the audio`() {
        for (bpm in listOf(100.0, 120.0, 128.0)) {
            val analysis = analyzer.analyse(pcmOf(SignalFixtures.clickTrack(bpm, 20.0)))
            val measured = requireNotNull(analysis.bpm) { "no tempo measured for $bpm BPM" }
            assertEquals("$bpm BPM click track", bpm, measured, 3.0)
        }
    }

    @Test
    fun `the same audio always measures the same tempo`() {
        // The previous implementation used `(90..150).random()` for store
        // imports, so re-importing a pack changed its tempo. Determinism is the
        // property that was missing.
        val audio = pcmOf(SignalFixtures.clickTrack(128.0, 20.0))
        val first = analyzer.analyse(audio).bpm
        val second = analyzer.analyse(audio).bpm
        assertEquals(first!!, second!!, 1e-9)
    }

    @Test
    fun `tempo does not depend on any name`() {
        // Nothing about the analysis takes a filename, which is what makes this
        // structurally true rather than merely currently true.
        val audio = SignalFixtures.clickTrack(120.0, 20.0)
        assertEquals(
            analyzer.analyse(pcmOf(audio)).bpm!!,
            analyzer.analyse(pcmOf(audio.copyOf())).bpm!!,
            1e-9,
        )
    }

    @Test
    fun `produces a beat grid aligned to the measured tempo`() {
        val analysis = analyzer.analyse(pcmOf(SignalFixtures.clickTrack(120.0, 20.0)))
        val grid = requireNotNull(analysis.beatGrid)
        assertEquals(analysis.bpm!!, grid.bpm, 1e-9)
        assertEquals(60.0 / grid.bpm, grid.beatDuration, 1e-9)
        assertTrue("beat times should cover the track", grid.beatTimes(10.0).size > 15)
    }

    @Test
    fun `reports no tempo for silence rather than inventing one`() {
        val analysis = analyzer.analyse(PcmBuffer.silence(sampleRate * 5, 1, sampleRate))
        assertNull("silence has no tempo", analysis.bpm)
        assertNull("silence has no beat grid", analysis.beatGrid)
        assertTrue(!analysis.isFullyAnalysed)
    }

    @Test
    fun `reports no key for silence rather than inventing one`() {
        val analysis = analyzer.analyse(PcmBuffer.silence(sampleRate * 5, 1, sampleRate))
        assertNull(analysis.camelotKey)
        assertNull(analysis.keyName)
    }

    @Test
    fun `measures key and maps it onto the Camelot wheel`() {
        val progression = listOf(
            listOf(60, 64, 67), // C-E-G
            listOf(65, 69, 72), // F-A-C
            listOf(67, 71, 74), // G-B-D
            listOf(60, 64, 67),
            listOf(60, 64, 67),
        )
        val parts = progression.map { notes ->
            SignalFixtures.chord(notes.map { SignalFixtures.midiToHz(it) }, 1.0)
        }
        val audio = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(audio, offset)
            offset += part.size
        }

        val analysis = analyzer.analyse(pcmOf(audio))
        assertEquals("C major is 8B on the Camelot wheel", "8B", analysis.camelotKey)
        assertEquals("C major", analysis.keyName)
    }

    @Test
    fun `a low confidence tempo is withheld`() {
        // Raising the bar above what the signal supports must yield null, not a
        // number carried through anyway.
        val audio = pcmOf(SignalFixtures.clickTrack(120.0, 20.0))
        assertNotNull(analyzer.analyse(audio, minimumTempoConfidence = 0f).bpm)
        assertNull(analyzer.analyse(audio, minimumTempoConfidence = 1.1f).bpm)
    }

    @Test
    fun `a low confidence key is withheld`() {
        val audio = pcmOf(SignalFixtures.chord(listOf(SignalFixtures.midiToHz(60)), 3.0))
        assertNull(analyzer.analyse(audio, minimumKeyConfidence = 1.1f).camelotKey)
    }

    @Test
    fun `reports duration and sample rate from the buffer`() {
        val analysis = analyzer.analyse(pcmOf(FloatArray(sampleRate * 2)))
        assertEquals(2.0, analysis.durationSeconds, 1e-6)
        assertEquals(sampleRate, analysis.sampleRate)
    }

    @Test
    fun `produces peaks and an energy curve for drawing`() {
        val analysis = analyzer.analyse(pcmOf(SignalFixtures.clickTrack(120.0, 10.0)))
        assertTrue("peaks should be populated", analysis.peaks.size > 0)
        assertTrue("energy curve should be populated", analysis.energyCurve.size > 0)
        assertTrue(analysis.energyLevel in 1..10)
    }

    @Test
    fun `louder material reports a higher energy level`() {
        val quiet = analyzer.analyse(pcmOf(SignalFixtures.sine(440.0, 4.0, amplitude = 0.02f)))
        val loud = analyzer.analyse(pcmOf(SignalFixtures.sine(440.0, 4.0, amplitude = 0.9f)))
        assertTrue(
            "quiet ${quiet.energyLevel} should be below loud ${loud.energyLevel}",
            quiet.energyLevel < loud.energyLevel,
        )
    }

    @Test
    fun `is fully analysed only when both tempo and key were found`() {
        val complete = analyzer.analyse(pcmOf(SignalFixtures.clickTrack(120.0, 20.0)))
        // A click track has a tempo; whether it has a key is immaterial here —
        // the flag must require both.
        assertEquals(
            complete.bpm != null && complete.camelotKey != null,
            complete.isFullyAnalysed,
        )
        assertTrue(!analyzer.analyse(PcmBuffer.silence(sampleRate, 1, sampleRate)).isFullyAnalysed)
    }

    @Test
    fun `handles stereo input`() {
        val left = SignalFixtures.clickTrack(120.0, 20.0)
        val stereo = PcmBuffer.fromFloat(arrayOf(left, left.copyOf()), sampleRate)
        assertEquals(120.0, requireNotNull(analyzer.analyse(stereo).bpm), 3.0)
    }
}
