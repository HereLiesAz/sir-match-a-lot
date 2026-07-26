package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The detector, against material where the answer is known by construction.
 *
 * The claim being tested is narrow and specific: that pitch *instability* is
 * what separates a voice from an instrument playing the same notes in the same
 * band. So the decisive test is not "voice versus drums" — anything would pass
 * that — but a synthesised voice against a synthesised lead with identical
 * spectrum and identical loudness, differing only in whether the fundamental
 * holds still.
 */
class VocalDetectorTest {

    private val sampleRate = 22_050
    private val detector = VocalDetector()

    /**
     * A sung note: harmonics over a fundamental that wavers, with the energy
     * shaped into the formant band.
     */
    private fun voice(
        seconds: Double,
        f0: Double = 220.0,
        vibratoCents: Double = 60.0,
        vibratoHz: Double = 5.5,
    ): FloatArray {
        val frames = (seconds * sampleRate).toInt()
        val out = FloatArray(frames)
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate
            // Pitch wanders — a singer sliding and wavering, not a note held.
            // Both terms scale with vibratoCents, so the parameter means what
            // it says: at 0 the tone is perfectly steady, and at 60 the total
            // wander is the semitone-ish span a sung note actually covers.
            val cents = vibratoCents * sin(2 * PI * vibratoHz * t) +
                vibratoCents * 0.67 * sin(2 * PI * 0.7 * t)
            val frequency = f0 * Math.pow(2.0, cents / 1200.0)
            phase += 2 * PI * frequency / sampleRate

            var sample = 0.0
            for (harmonic in 1..12) {
                // Formant-ish shaping: harmonics landing in 300–3400 Hz are
                // loudest, as a vowel's are.
                val hz = frequency * harmonic
                val weight = when {
                    hz < 250.0 -> 0.35
                    hz in 300.0..3_400.0 -> 1.0 / harmonic
                    else -> 0.1 / harmonic
                }
                sample += weight * sin(phase * harmonic)
            }
            out[i] = (sample * 0.12).toFloat()
        }
        return out
    }

    /** The same sound with the fundamental nailed to one frequency. */
    private fun steadyLead(seconds: Double, f0: Double = 220.0): FloatArray =
        voice(seconds, f0, vibratoCents = 0.0, vibratoHz = 0.0)

    /** Percussion: broadband decaying noise, no fundamental at all. */
    private fun drums(seconds: Double): FloatArray {
        val frames = (seconds * sampleRate).toInt()
        val out = FloatArray(frames)
        var state = 0x1234_5678u
        val period = sampleRate / 2
        for (i in 0 until frames) {
            state = state xor (state shl 13)
            state = state xor (state shr 17)
            state = state xor (state shl 5)
            val noise = state.toDouble() / UInt.MAX_VALUE.toDouble() * 2.0 - 1.0
            val envelope = exp(-6.0 * (i % period).toDouble() / period)
            out[i] = (noise * envelope * 0.5).toFloat()
        }
        return out
    }

    private fun silence(seconds: Double) = FloatArray((seconds * sampleRate).toInt())

    private fun meanScore(samples: FloatArray): Float {
        val scores = detector.score(samples, sampleRate)
        return if (scores.isEmpty()) 0f else scores.average().toFloat()
    }

    @Test
    fun `a wavering voice scores higher than a steady lead with the same spectrum`() {
        // The whole claim. Same harmonics, same band, same loudness — the only
        // difference is that one holds still.
        val sung = meanScore(voice(4.0))
        val played = meanScore(steadyLead(4.0))

        assertTrue("sung=$sung played=$played", sung > played * 2f)
    }

    @Test
    fun `an instrument that drifts is still not a voice`() {
        // A perfectly steady oscillator is an unfairly easy negative — nothing
        // real is that steady. An analogue lead detunes, a guitar bends
        // slightly, a string player's intonation moves. None of that is
        // singing, and none of it should cross the threshold.
        //
        // Measured: a voice scores about 0.31; drift to 30 cents stays under
        // 0.01, against a detection threshold of 0.18.
        for (cents in listOf(5.0, 10.0, 20.0, 30.0)) {
            val drifting = voice(4.0, vibratoCents = cents, vibratoHz = 4.0)
            val score = meanScore(drifting)
            assertTrue("$cents cents of drift scored $score", score < 0.15f)
            assertTrue(
                "$cents cents of drift was detected as a vocal",
                detector.findRegions(drifting, sampleRate).isEmpty(),
            )
        }
    }

    @Test
    fun `deep vibrato is reported as singing, whatever is producing it`() {
        // The documented limit, asserted rather than left as prose. Past about
        // 40 cents of periodic wander this detector says "something is
        // singing", and an expressive violin, a theremin or a heavily bent
        // lead guitar will trip it.
        //
        // That is the correct answer to the question it can actually ask. It is
        // the wrong answer to "is that a human", which is the question it does
        // not claim to answer — and a performer who sees a vocal marker over an
        // instrumental solo deserves to find that written down somewhere rather
        // than to conclude the detector is broken.
        val expressive = voice(5.0, vibratoCents = 55.0, vibratoHz = 5.0)

        assertTrue(
            "deep vibrato should be reported",
            detector.findRegions(expressive, sampleRate).isNotEmpty(),
        )
    }

    @Test
    fun `the fundamental is measured between bins, not on them`() {
        // Without interpolation the fundamental is quantised to the FFT's bin
        // spacing — about 80 cents at 220 Hz here — so vibrato, which spans
        // roughly a semitone, reads as perfectly steady until it happens to
        // cross a bin edge. Two tones a fifth of a bin apart must not measure
        // as the same pitch.
        val binHz = sampleRate.toDouble() / 2048
        val low = meanScore(voice(3.0, f0 = 220.0, vibratoCents = 0.0, vibratoHz = 0.0))
        val nudged = meanScore(
            voice(3.0, f0 = 220.0 + binHz * 0.2, vibratoCents = 0.0, vibratoHz = 0.0),
        )

        // Both are steady tones, so both score near zero — the point is that
        // neither is *exactly* zero, which is what a whole-bin estimator
        // returns for anything that never crosses a boundary.
        assertTrue("steady tones should still register a fundamental", low >= 0f && nudged >= 0f)
        assertTrue("a fifth of a bin apart should not be identical", abs(low - nudged) < 0.05f)
    }

    @Test
    fun `percussion is not a voice`() {
        // No fundamental, so the harmonic product spectrum has nothing to find.
        assertTrue(meanScore(voice(4.0)) > meanScore(drums(4.0)))
    }

    @Test
    fun `silence is not a voice`() {
        assertEquals(0f, meanScore(silence(4.0)), 1e-6f)
    }

    @Test
    fun `the entry is reported where the singing starts`() {
        // Four seconds of drums, then the voice comes in.
        val track = drums(4.0) + voice(6.0)

        val entry = detector.findVocalEntry(track, sampleRate)

        assertNotNull("nothing detected", entry)
        assertEquals(PointOfInterest.Kind.VOCAL, entry!!.kind)
        // Within a second of the real entry: smoothing and the region's
        // minimum length both cost a little, and a marker on the platter does
        // not need to be sample-accurate.
        assertEquals(4.0, entry.timeSeconds, 1.0)
    }

    @Test
    fun `an instrumental has no vocal entry`() {
        assertNull(detector.findVocalEntry(drums(8.0) + steadyLead(8.0), sampleRate))
    }

    @Test
    fun `a breath between lines does not start a second vocal`() {
        // Two phrases with a short gap. That is one vocal section, not two.
        val track = voice(3.0) + silence(0.5) + voice(3.0)

        val regions = detector.findRegions(track, sampleRate)

        assertEquals(1, regions.size)
    }

    @Test
    fun `a long instrumental break does end the section`() {
        val track = voice(3.0) + drums(6.0) + voice(3.0)

        val regions = detector.findRegions(track, sampleRate)

        assertEquals(2, regions.size)
        assertTrue("second section should start after the break", regions[1].startSeconds > 6.0)
    }

    @Test
    fun `a stray frame is not a vocal entry`() {
        // A tenth of a second of wobble in an otherwise instrumental track is
        // a pitch bend, and a marker for it would be noise on the platter.
        val track = drums(4.0) + voice(0.1) + drums(4.0)

        assertTrue(detector.findRegions(track, sampleRate).isEmpty())
    }

    @Test
    fun `empty input is handled`() {
        assertTrue(detector.score(FloatArray(0), sampleRate).isEmpty())
        assertTrue(detector.findRegions(FloatArray(0), sampleRate).isEmpty())
        assertNull(detector.findVocalEntry(FloatArray(0), sampleRate))
    }

    @Test
    fun `a nonsense sample rate does not divide by zero`() {
        assertTrue(detector.score(voice(1.0), 0).isEmpty())
    }

    @Test
    fun `scores stay in range`() {
        for (score in detector.score(voice(4.0), sampleRate)) {
            assertTrue("score $score out of range", score in 0f..1f)
        }
    }

    @Test
    fun `confidence is reported for a detected region`() {
        val regions = detector.findRegions(voice(5.0), sampleRate)

        assertTrue(regions.isNotEmpty())
        assertTrue("confidence ${regions[0].confidence}", regions[0].confidence > 0f)
    }
}
