package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class BiquadTest {

    private val sampleRate = 44_100

    private fun rms(samples: FloatArray): Double {
        var sum = 0.0
        for (v in samples) sum += v.toDouble() * v
        return sqrt(sum / samples.size)
    }

    private fun filtered(coefficients: BiquadCoefficients, input: FloatArray): FloatArray {
        val filter = Biquad(coefficients)
        val out = input.copyOf()
        filter.processInPlace(out)
        return out
    }

    @Test
    fun `identity passes signal through untouched`() {
        val input = SignalFixtures.sine(1000.0, 0.1)
        val out = filtered(BiquadCoefficients.IDENTITY, input)
        for (i in input.indices) assertEquals(input[i], out[i], 1e-6f)
    }

    @Test
    fun `low shelf boosts the bottom and leaves the top alone`() {
        val gainDb = 12.0
        val coefficients = BiquadCoefficients.lowShelf(200.0, sampleRate, gainDb)

        assertEquals(10.0.pow(gainDb / 20.0), coefficients.magnitudeAt(20.0, sampleRate), 0.05)
        assertEquals(1.0, coefficients.magnitudeAt(10_000.0, sampleRate), 0.02)
        // Midpoint of a shelf sits at half the gain in dB.
        assertEquals(
            10.0.pow(gainDb / 40.0),
            coefficients.magnitudeAt(200.0, sampleRate),
            0.05,
        )
    }

    @Test
    fun `high shelf cuts the top and leaves the bottom alone`() {
        val gainDb = -12.0
        val coefficients = BiquadCoefficients.highShelf(4000.0, sampleRate, gainDb)

        assertEquals(10.0.pow(gainDb / 20.0), coefficients.magnitudeAt(20_000.0, sampleRate), 0.02)
        assertEquals(1.0, coefficients.magnitudeAt(100.0, sampleRate), 0.02)
    }

    @Test
    fun `peaking filter hits its gain at the centre frequency only`() {
        val gainDb = 6.0
        val coefficients = BiquadCoefficients.peaking(1000.0, sampleRate, gainDb, q = 1.0)

        assertEquals(10.0.pow(gainDb / 20.0), coefficients.magnitudeAt(1000.0, sampleRate), 0.01)
        assertEquals(1.0, coefficients.magnitudeAt(20.0, sampleRate), 0.02)
        assertEquals(1.0, coefficients.magnitudeAt(18_000.0, sampleRate), 0.05)
    }

    @Test
    fun `low pass is minus three dB at cutoff`() {
        val coefficients = BiquadCoefficients.lowPass(1000.0, sampleRate, q = 0.7071)
        assertEquals(1.0 / sqrt(2.0), coefficients.magnitudeAt(1000.0, sampleRate), 0.01)
        assertEquals(1.0, coefficients.magnitudeAt(50.0, sampleRate), 0.02)
        assertTrue(coefficients.magnitudeAt(10_000.0, sampleRate) < 0.02)
    }

    @Test
    fun `high pass is minus three dB at cutoff`() {
        val coefficients = BiquadCoefficients.highPass(1000.0, sampleRate, q = 0.7071)
        assertEquals(1.0 / sqrt(2.0), coefficients.magnitudeAt(1000.0, sampleRate), 0.01)
        assertEquals(1.0, coefficients.magnitudeAt(15_000.0, sampleRate), 0.02)
        assertTrue(coefficients.magnitudeAt(50.0, sampleRate) < 0.01)
    }

    @Test
    fun `filtering actually attenuates the stopband`() {
        // The designed response has to hold when samples run through the state
        // machine, not only in the analytic magnitude.
        val coefficients = BiquadCoefficients.lowPass(500.0, sampleRate)

        val low = SignalFixtures.sine(100.0, 0.5)
        val high = SignalFixtures.sine(5000.0, 0.5)

        // Skip the first 2000 samples so the filter has settled.
        val lowOut = filtered(coefficients, low).copyOfRange(2000, low.size)
        val highOut = filtered(coefficients, high).copyOfRange(2000, high.size)

        val lowRatio = rms(lowOut) / rms(low.copyOfRange(2000, low.size))
        val highRatio = rms(highOut) / rms(high.copyOfRange(2000, high.size))

        assertTrue("100 Hz should survive, ratio was $lowRatio", lowRatio > 0.9)
        assertTrue("5 kHz should be crushed, ratio was $highRatio", highRatio < 0.05)
    }

    @Test
    fun `reset clears state`() {
        val filter = Biquad(BiquadCoefficients.lowPass(500.0, sampleRate))
        val impulse = FloatArray(64).also { it[0] = 1f }

        val first = impulse.copyOf().also { filter.processInPlace(it) }
        filter.reset()
        val second = impulse.copyOf().also { filter.processInPlace(it) }

        for (i in first.indices) assertEquals(first[i], second[i], 1e-9f)
    }
}
