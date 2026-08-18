package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.dsp.KeyEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Camelot model was kept from the previous codebase; these tests pin the
 * behaviour it was relied upon for but never had.
 */
class HarmonicEngineTest {

    @Test
    fun `maps major pitch classes onto the Camelot wheel`() {
        // Canonical anchors from the Camelot system.
        assertEquals("8B", HarmonicEngine.camelotFor(0, isMinor = false))  // C
        assertEquals("9B", HarmonicEngine.camelotFor(7, isMinor = false))  // G
        assertEquals("10B", HarmonicEngine.camelotFor(2, isMinor = false)) // D
        assertEquals("11B", HarmonicEngine.camelotFor(9, isMinor = false)) // A
        assertEquals("12B", HarmonicEngine.camelotFor(4, isMinor = false)) // E
        assertEquals("1B", HarmonicEngine.camelotFor(11, isMinor = false)) // B
        assertEquals("7B", HarmonicEngine.camelotFor(5, isMinor = false))  // F
    }

    @Test
    fun `maps minor pitch classes onto the Camelot wheel`() {
        assertEquals("8A", HarmonicEngine.camelotFor(9, isMinor = true))  // A minor
        assertEquals("9A", HarmonicEngine.camelotFor(4, isMinor = true))  // E minor
        assertEquals("10A", HarmonicEngine.camelotFor(11, isMinor = true))// B minor
        assertEquals("11A", HarmonicEngine.camelotFor(6, isMinor = true)) // F# minor
        assertEquals("5A", HarmonicEngine.camelotFor(0, isMinor = true))  // C minor
        assertEquals("7A", HarmonicEngine.camelotFor(2, isMinor = true))  // D minor
    }

    @Test
    fun `relative major and minor share a Camelot number`() {
        // C major (8B) and A minor (8A) are relatives; every such pair must
        // agree on the number and differ only in mode.
        for (pitchClass in 0 until 12) {
            val major = HarmonicEngine.camelotFor(pitchClass, isMinor = false)
            val relativeMinor = HarmonicEngine.camelotFor((pitchClass + 9) % 12, isMinor = true)
            assertEquals(
                "pitch class $pitchClass",
                major.dropLast(1),
                relativeMinor.dropLast(1),
            )
        }
    }

    @Test
    fun `every key maps to a distinct Camelot code`() {
        val codes = mutableSetOf<String>()
        for (pitchClass in 0 until 12) {
            codes.add(HarmonicEngine.camelotFor(pitchClass, isMinor = false))
            codes.add(HarmonicEngine.camelotFor(pitchClass, isMinor = true))
        }
        assertEquals(24, codes.size)
    }

    @Test
    fun `accepts a detected key directly`() {
        assertEquals("8A", HarmonicEngine.camelotFor(KeyEstimate(9, isMinor = true, confidence = 1f)))
        assertEquals("8B", HarmonicEngine.camelotFor(KeyEstimate(0, isMinor = false, confidence = 1f)))
    }

    @Test
    fun `normalises out of range pitch classes`() {
        assertEquals("8B", HarmonicEngine.camelotFor(12, isMinor = false))
        assertEquals("8B", HarmonicEngine.camelotFor(-12, isMinor = false))
    }

    @Test
    fun `parses Camelot codes and rejects nonsense`() {
        assertEquals(HarmonicEngine.CamelotKey(8, 'A'), HarmonicEngine.parseCamelotKey("8A"))
        assertEquals(HarmonicEngine.CamelotKey(12, 'B'), HarmonicEngine.parseCamelotKey(" 12b "))
        assertNull(HarmonicEngine.parseCamelotKey(""))
        assertNull(HarmonicEngine.parseCamelotKey("8C"))
        assertNull(HarmonicEngine.parseCamelotKey("A8"))
    }

    @Test
    fun `scores an identical key highest`() {
        val (score, _) = HarmonicEngine.calculateKeyCompatibility("8A", "8A")
        assertEquals(100, score)
    }

    @Test
    fun `ranks harmonic relationships in the expected order`() {
        val identical = HarmonicEngine.calculateKeyCompatibility("8A", "8A").first
        val relative = HarmonicEngine.calculateKeyCompatibility("8A", "8B").first
        val fifth = HarmonicEngine.calculateKeyCompatibility("8A", "9A").first
        val diagonal = HarmonicEngine.calculateKeyCompatibility("8A", "9B").first
        val distant = HarmonicEngine.calculateKeyCompatibility("8A", "2A").first

        assertTrue(identical > relative)
        assertTrue(relative > fifth)
        assertTrue(fifth > diagonal)
        assertTrue(diagonal > distant)
    }

    @Test
    fun `treats the wheel as circular`() {
        // 12 and 1 are adjacent, so they must score as a fifth, not as distant.
        val wrapped = HarmonicEngine.calculateKeyCompatibility("12A", "1A").first
        val adjacent = HarmonicEngine.calculateKeyCompatibility("8A", "9A").first
        assertEquals(adjacent, wrapped)
    }

    @Test
    fun `transposes within the wheel`() {
        // Up a perfect fifth (7 semitones) from C major (8B) is G major (9B).
        assertEquals("9B", HarmonicEngine.transposeCamelotKey("8B", 7))
        // Up a whole tone from A minor (8A) is B minor (10A).
        assertEquals("10A", HarmonicEngine.transposeCamelotKey("8A", 2))
        assertEquals("8A", HarmonicEngine.transposeCamelotKey("8A", 0))
        assertEquals("8A", HarmonicEngine.transposeCamelotKey("8A", 12))
    }

    @Test
    fun `shortest semitone shift takes the near way round`() {
        // Never more than six semitones in either direction.
        for (from in 0 until 12) {
            for (to in 0 until 12) {
                val a = HarmonicEngine.camelotFor(from, isMinor = false)
                val b = HarmonicEngine.camelotFor(to, isMinor = false)
                val shift = HarmonicEngine.getShortestSemitoneShift(a, b)
                assertTrue("shift $shift from $a to $b", shift in -6..6)
                // Applying the shift to b must reach a.
                assertEquals(a, HarmonicEngine.transposeCamelotKey(b, shift))
            }
        }
    }

    @Test
    fun `matches identical tempi perfectly`() {
        val result = HarmonicEngine.calculateTempoCompatibility(128.0, 128.0)
        assertEquals(100, result["score"])
        assertTrue(result["canMixWithNudge"] as Boolean)
        assertTrue(!(result["isHalfTimeDoubleTime"] as Boolean))
    }

    @Test
    fun `recognises a half time relationship`() {
        // 174 BPM drum-and-bass over 87 BPM hip-hop is a real, mixable pairing.
        val result = HarmonicEngine.calculateTempoCompatibility(174.0, 87.0)
        assertTrue("should be flagged as half time", result["isHalfTimeDoubleTime"] as Boolean)
        assertEquals(100, result["score"])
    }

    @Test
    fun `recognises a double time relationship`() {
        val result = HarmonicEngine.calculateTempoCompatibility(70.0, 140.0)
        assertTrue(result["isHalfTimeDoubleTime"] as Boolean)
        assertEquals(100, result["score"])
    }

    @Test
    fun `flags tempi that are too far apart`() {
        val result = HarmonicEngine.calculateTempoCompatibility(100.0, 145.0)
        assertTrue(!(result["canMixWithNudge"] as Boolean))
        assertTrue("score was ${result["score"]}", (result["score"] as Int) < 50)
    }

    @Test
    fun `allows a small nudge within six percent`() {
        val result = HarmonicEngine.calculateTempoCompatibility(128.0, 132.0)
        assertTrue("4 BPM at 128 is about 3 percent", result["canMixWithNudge"] as Boolean)
    }
}
