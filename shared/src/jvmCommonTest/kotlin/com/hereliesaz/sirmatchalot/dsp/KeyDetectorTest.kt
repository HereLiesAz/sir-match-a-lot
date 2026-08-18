package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDetectorTest {

    private val detector = KeyDetector()

    /** Builds a chroma vector by rotating a profile to [root]. */
    private fun profileChroma(profile: DoubleArray, root: Int): DoubleArray {
        val chroma = DoubleArray(12)
        for (i in 0 until 12) chroma[i] = profile[(i - root + 12) % 12]
        val total = chroma.sum()
        for (i in 0 until 12) chroma[i] /= total
        return chroma
    }

    @Test
    fun `classifies every major key from its own profile`() {
        for (root in 0 until 12) {
            val estimate = detector.classify(profileChroma(KeyDetector.MAJOR_PROFILE, root))
            assertEquals("root $root", root, estimate.rootPitchClass)
            assertTrue("root $root classified as minor", !estimate.isMinor)
        }
    }

    @Test
    fun `classifies every minor key from its own profile`() {
        for (root in 0 until 12) {
            val estimate = detector.classify(profileChroma(KeyDetector.MINOR_PROFILE, root))
            assertEquals("root $root", root, estimate.rootPitchClass)
            assertTrue("root $root classified as major", estimate.isMinor)
        }
    }

    @Test
    fun `names keys conventionally`() {
        assertEquals("C major", KeyEstimate(0, false, 1f).name)
        assertEquals("A minor", KeyEstimate(9, true, 1f).name)
        assertEquals("Eb major", KeyEstimate(3, false, 1f).name)
        assertEquals("F# minor", KeyEstimate(6, true, 1f).name)
    }

    @Test
    fun `detects C major from a I-IV-V-I progression`() {
        // C-E-G, F-A-C, G-B-D, then C-E-G held twice as long to establish the
        // tonic, which is what makes this C major rather than its relative A minor.
        val progression = listOf(
            listOf(60, 64, 67) to 1.0,
            listOf(65, 69, 72) to 1.0,
            listOf(67, 71, 74) to 1.0,
            listOf(60, 64, 67) to 2.0,
        )
        val audio = renderProgression(progression)

        val estimate = requireNotNull(detector.detect(audio, SignalFixtures.SAMPLE_RATE))
        assertEquals("root", 0, estimate.rootPitchClass)
        assertTrue("should be major, was ${estimate.name}", !estimate.isMinor)
    }

    @Test
    fun `detects A minor from a i-iv-v-i progression`() {
        val progression = listOf(
            listOf(57, 60, 64) to 1.0, // A-C-E
            listOf(62, 65, 69) to 1.0, // D-F-A
            listOf(64, 67, 71) to 1.0, // E-G-B
            listOf(57, 60, 64) to 2.0,
        )
        val audio = renderProgression(progression)

        val estimate = requireNotNull(detector.detect(audio, SignalFixtures.SAMPLE_RATE))
        assertEquals("root", 9, estimate.rootPitchClass)
        assertTrue("should be minor, was ${estimate.name}", estimate.isMinor)
    }

    @Test
    fun `is transposition equivariant`() {
        // Transposing the audio up a whole tone must move the detected root by
        // exactly two semitones.
        val cMajor = listOf(
            listOf(60, 64, 67) to 1.0,
            listOf(65, 69, 72) to 1.0,
            listOf(67, 71, 74) to 1.0,
            listOf(60, 64, 67) to 2.0,
        )
        val dMajor = cMajor.map { (notes, beats) -> notes.map { it + 2 } to beats }

        val first = requireNotNull(detector.detect(renderProgression(cMajor), SignalFixtures.SAMPLE_RATE))
        val second = requireNotNull(detector.detect(renderProgression(dMajor), SignalFixtures.SAMPLE_RATE))

        assertEquals(second.isMinor, first.isMinor)
        assertEquals((first.rootPitchClass + 2) % 12, second.rootPitchClass)
    }

    @Test
    fun `returns null for silence and for buffers shorter than a frame`() {
        assertNull(detector.detect(FloatArray(SignalFixtures.SAMPLE_RATE), SignalFixtures.SAMPLE_RATE))
        assertNull(detector.detect(FloatArray(128), SignalFixtures.SAMPLE_RATE))
    }

    @Test
    fun `chromagram concentrates energy on the notes played`() {
        val audio = SignalFixtures.chord(listOf(SignalFixtures.midiToHz(60)), 2.0)
        val chroma = requireNotNull(detector.chromagram(audio, SignalFixtures.SAMPLE_RATE))

        // C plus its harmonics: the octave is C again, the twelfth is G.
        var strongest = 0
        for (i in 1 until 12) if (chroma[i] > chroma[strongest]) strongest = i
        assertEquals("expected C to dominate, chroma was ${chroma.toList()}", 0, strongest)
        assertEquals("chroma should be normalised", 1.0, chroma.sum(), 1e-9)
    }

    private fun renderProgression(progression: List<Pair<List<Int>, Double>>): FloatArray {
        val chordSeconds = 1.0
        val parts = progression.map { (notes, beats) ->
            SignalFixtures.chord(notes.map { SignalFixtures.midiToHz(it) }, chordSeconds * beats)
        }
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
