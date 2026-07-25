package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {

    @Test
    fun `rejects non power of two sizes`() {
        for (size in listOf(0, 1, 3, 100, 1000)) {
            try {
                Fft(size)
                throw AssertionError("expected Fft($size) to be rejected")
            } catch (expected: IllegalArgumentException) {
                // intended
            }
        }
    }

    @Test
    fun `matches a naive DFT on random input`() {
        val size = 64
        val random = Random(42)
        val input = FloatArray(size) { random.nextFloat() * 2f - 1f }

        val re = input.copyOf()
        val im = FloatArray(size)
        Fft(size).forward(re, im)

        for (k in 0 until size) {
            var expectedRe = 0.0
            var expectedIm = 0.0
            for (n in 0 until size) {
                val angle = -2.0 * PI * k * n / size
                expectedRe += input[n] * cos(angle)
                expectedIm += input[n] * sin(angle)
            }
            assertEquals("bin $k real", expectedRe, re[k].toDouble(), 1e-3)
            assertEquals("bin $k imaginary", expectedIm, im[k].toDouble(), 1e-3)
        }
    }

    @Test
    fun `puts a sine's energy in its own bin`() {
        val size = 1024
        val bin = 37
        val re = FloatArray(size) { sin(2.0 * PI * bin * it / size).toFloat() }
        val im = FloatArray(size)
        val fft = Fft(size)
        fft.forward(re, im)

        val magnitudes = FloatArray(size / 2 + 1)
        fft.magnitudes(re, im, magnitudes)

        var peak = 0
        for (k in magnitudes.indices) if (magnitudes[k] > magnitudes[peak]) peak = k
        assertEquals(bin, peak)

        // Everything outside the peak should be negligible for an exactly
        // bin-centred sine.
        val peakMagnitude = magnitudes[peak]
        for (k in magnitudes.indices) {
            if (abs(k - bin) <= 1) continue
            assertTrue(
                "bin $k leaked ${magnitudes[k]} against peak $peakMagnitude",
                magnitudes[k] < peakMagnitude * 0.01f,
            )
        }
    }

    @Test
    fun `conserves energy per Parseval`() {
        val size = 256
        val random = Random(7)
        val input = FloatArray(size) { random.nextFloat() * 2f - 1f }

        var timeEnergy = 0.0
        for (v in input) timeEnergy += v.toDouble() * v

        val re = input.copyOf()
        val im = FloatArray(size)
        Fft(size).forward(re, im)

        var frequencyEnergy = 0.0
        for (k in 0 until size) {
            frequencyEnergy += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        }
        frequencyEnergy /= size

        assertEquals(timeEnergy, frequencyEnergy, timeEnergy * 1e-4)
    }

    @Test
    fun `reports bin frequencies`() {
        val fft = Fft(1024)
        assertEquals(0.0, fft.binFrequency(0, 44_100).toDouble(), 0.0)
        assertEquals(43.066, fft.binFrequency(1, 44_100).toDouble(), 0.01)
        assertEquals(22_050.0, fft.binFrequency(512, 44_100).toDouble(), 0.01)
    }
}
