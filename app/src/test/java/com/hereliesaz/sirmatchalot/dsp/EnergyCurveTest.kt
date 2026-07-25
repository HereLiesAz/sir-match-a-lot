package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
