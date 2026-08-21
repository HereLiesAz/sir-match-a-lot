package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class EnergyCurveTest {

    private val sampleRate = SignalFixtures.SAMPLE_RATE

    private fun noise(seconds: Double, amplitude: Float, seed: Int = 3): FloatArray {
        val random = Random(seed)
        return FloatArray((seconds * sampleRate).toInt()) {
            (random.nextFloat() * 2f - 1f) * amplitude
        }
    }

    @Test
    fun `louder audio reads as higher energy`() {
        val quiet = EnergyCurve.compute(noise(4.0, 0.05f), sampleRate)
        val loud = EnergyCurve.compute(noise(4.0, 0.9f), sampleRate)
        assertTrue(
            "quiet ${quiet.average()} should be below loud ${loud.average()}",
            quiet.average() < loud.average(),
        )
    }

    @Test
    fun `loudness does not saturate across the range real mastered music occupies`() {
        // Real mastered tracks sit around -14..-6 dBFS RMS. The loudness term
        // used to be measured off the raw FFT magnitude sum rather than the
        // time-domain samples, which put its own "0 dB" reference around
        // -26 dBFS — so every one of these amplitudes read as the same,
        // maximal energy. Amplitude for a uniform-noise RMS of X dBFS is
        // roughly X_dBFS + 4.77 dB (sqrt(3) headroom of a uniform distribution).
        val quiet = EnergyCurve.compute(noise(4.0, 0.20f), sampleRate) // ~ -14 dBFS
        val loud = EnergyCurve.compute(noise(4.0, 0.50f), sampleRate) // ~ -6 dBFS
        assertTrue(
            "quiet ${quiet.average()} should be measurably below loud ${loud.average()}",
            quiet.average() < loud.average() - 0.05f,
        )
    }

    @Test
    fun `brighter audio reads as higher energy at equal loudness`() {
        val dull = SignalFixtures.sine(120.0, 4.0, amplitude = 0.6f)
        val bright = SignalFixtures.sine(6000.0, 4.0, amplitude = 0.6f)

        val dullEnergy = EnergyCurve.compute(dull, sampleRate).average()
        val brightEnergy = EnergyCurve.compute(bright, sampleRate).average()
        assertTrue(
            "dull $dullEnergy should be below bright $brightEnergy",
            dullEnergy < brightEnergy,
        )
    }

    @Test
    fun `values stay within range`() {
        val curve = EnergyCurve.compute(noise(3.0, 1.0f), sampleRate)
        assertTrue(curve.size > 0)
        for (v in curve.values) {
            assertTrue("out of range: $v", v in 0f..1f)
        }
    }

    @Test
    fun `tracks a build up`() {
        // Ramp amplitude from silence to full: energy must rise monotonically
        // when smoothed over the whole curve.
        val seconds = 8.0
        val n = (seconds * sampleRate).toInt()
        val random = Random(11)
        val samples = FloatArray(n) { i ->
            val ramp = i.toFloat() / n
            (random.nextFloat() * 2f - 1f) * ramp
        }
        val curve = EnergyCurve.compute(samples, sampleRate)
        assertTrue(curve.size >= 8)

        val firstQuarter = curve.values.take(curve.size / 4).average()
        val lastQuarter = curve.values.takeLast(curve.size / 4).average()
        assertTrue(
            "start $firstQuarter should be below end $lastQuarter",
            firstQuarter < lastQuarter,
        )
    }

    @Test
    fun `silence has minimal energy`() {
        val curve = EnergyCurve.compute(FloatArray(sampleRate * 2), sampleRate)
        assertTrue("silence measured ${curve.average()}", curve.average() < 0.05f)
    }

    @Test
    fun `energy level maps onto the one to ten library scale`() {
        assertEquals(1, EnergyCurve(floatArrayOf(0f), 0.5).energyLevel())
        assertEquals(10, EnergyCurve(floatArrayOf(1f), 0.5).energyLevel())
        assertEquals(5, EnergyCurve(floatArrayOf(0.5f), 0.5).energyLevel())
        // An empty curve must not throw.
        assertEquals(1, EnergyCurve(FloatArray(0), 0.5).energyLevel())
    }

    @Test
    fun `lookup by time clamps to the ends`() {
        val curve = EnergyCurve(floatArrayOf(0.1f, 0.5f, 0.9f), windowSeconds = 0.5)
        assertEquals(0.1f, curve.at(0.0), 0f)
        assertEquals(0.5f, curve.at(0.6), 0f)
        assertEquals(0.9f, curve.at(1.2), 0f)
        assertEquals(0.9f, curve.at(99.0), 0f)
        assertEquals(0.1f, curve.at(-5.0), 0f)
    }

    @Test
    fun `handles an empty buffer`() {
        val curve = EnergyCurve.compute(FloatArray(0), sampleRate)
        assertEquals(0, curve.size)
        assertEquals(0f, curve.average(), 0f)
        assertEquals(0f, curve.at(1.0), 0f)
    }

    @Test
    fun `rejects a non positive window`() {
        try {
            EnergyCurve.compute(FloatArray(1024), sampleRate, windowSeconds = 0.0)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
    }

    // --- Serialisation ---

    @Test
    fun `a curve survives a round trip through bytes`() {
        val original = EnergyCurve(
            values = FloatArray(97) { it / 97f },
            windowSeconds = 0.25,
        )
        val restored = EnergyCurve.fromByteArray(original.toByteArray())
        assertNotNull(restored)
        restored!!
        assertEquals(original.windowSeconds, restored.windowSeconds, 0.0)
        assertEquals(original.size, restored.size)
        for (i in 0 until original.size) {
            assertEquals("value $i", original.values[i], restored.values[i], 0f)
        }
    }

    @Test
    fun `the window length travels with the values`() {
        // Without it, a curve read back cannot answer at(), and the caller would
        // have to guess the analyser's configuration.
        val restored = EnergyCurve.fromByteArray(
            EnergyCurve(FloatArray(4) { 0.5f }, windowSeconds = 1.75).toByteArray(),
        )
        assertNotNull(restored)
        assertEquals(1.75, restored!!.windowSeconds, 0.0)
        assertEquals(0.5f, restored.at(3.0), 0f)
    }

    @Test
    fun `an empty curve round trips`() {
        val restored = EnergyCurve.fromByteArray(
            EnergyCurve(FloatArray(0), windowSeconds = 0.5).toByteArray(),
        )
        assertNotNull(restored)
        assertEquals(0, restored!!.size)
    }

    @Test
    fun `rubbish is rejected rather than read as a curve`() {
        // A truncated or corrupt file must not produce a curve with a nonsense
        // window length, because at() would then index by garbage.
        assertNull(EnergyCurve.fromByteArray(ByteArray(0)))
        assertNull(EnergyCurve.fromByteArray(ByteArray(4)))
        // Eight zero bytes decode to windowSeconds = 0.0, which is unusable.
        assertNull(EnergyCurve.fromByteArray(ByteArray(8)))
    }
}
