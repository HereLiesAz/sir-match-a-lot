package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakEnvelopeTest {

    @Test
    fun `captures both extremes of each bucket`() {
        // Four buckets over sixteen samples; bucket 1 holds the extremes.
        val samples = FloatArray(16)
        samples[5] = 0.9f
        samples[6] = -0.7f
        val envelope = PeakEnvelope.compute(samples, buckets = 4)

        assertEquals(4, envelope.size)
        assertEquals(0.9f, envelope.maxima[1], 1e-6f)
        assertEquals(-0.7f, envelope.minima[1], 1e-6f)
        assertEquals(1.6f, envelope.heightAt(1), 1e-6f)
        assertEquals(0f, envelope.heightAt(0), 1e-6f)
    }

    @Test
    fun `an asymmetric signal produces an asymmetric envelope`() {
        // The point of storing min and max separately: a signal that swings
        // further positive than negative must look that way when drawn.
        val samples = FloatArray(1024) { if (it % 2 == 0) 0.8f else -0.2f }
        val envelope = PeakEnvelope.compute(samples, buckets = 8)
        for (i in 0 until 8) {
            assertEquals(0.8f, envelope.maxima[i], 1e-6f)
            assertEquals(-0.2f, envelope.minima[i], 1e-6f)
        }
    }

    @Test
    fun `handles an empty buffer`() {
        val envelope = PeakEnvelope.compute(FloatArray(0), buckets = 32)
        assertEquals(32, envelope.size)
        for (i in 0 until 32) assertEquals(0f, envelope.heightAt(i), 0f)
    }

    @Test
    fun `handles more buckets than samples`() {
        val envelope = PeakEnvelope.compute(floatArrayOf(0.5f, -0.5f), buckets = 8)
        assertEquals(8, envelope.size)
        for (i in 0 until 8) assertTrue(envelope.heightAt(i) >= 0f)
    }

    @Test
    fun `height outside the range is zero`() {
        val envelope = PeakEnvelope.compute(FloatArray(64) { 0.5f }, buckets = 4)
        assertEquals(0f, envelope.heightAt(-1), 0f)
        assertEquals(0f, envelope.heightAt(4), 0f)
    }

    @Test
    fun `interpolates height at a fractional position`() {
        val envelope = PeakEnvelope(
            minima = floatArrayOf(0f, 0f, 0f),
            maxima = floatArrayOf(0f, 1f, 0f),
        )
        assertEquals(0f, envelope.heightAtFraction(0f), 1e-6f)
        assertEquals(1f, envelope.heightAtFraction(0.5f), 1e-6f)
        assertEquals(0f, envelope.heightAtFraction(1f), 1e-6f)
        assertEquals(0.5f, envelope.heightAtFraction(0.25f), 1e-6f)
        // Out-of-range positions clamp rather than throw.
        assertEquals(0f, envelope.heightAtFraction(-1f), 1e-6f)
        assertEquals(0f, envelope.heightAtFraction(2f), 1e-6f)
    }

    @Test
    fun `resampling down preserves extremes`() {
        val samples = FloatArray(4096) { kotlin.math.sin(it * 0.01).toFloat() }
        val fine = PeakEnvelope.compute(samples, buckets = 512)
        val coarse = fine.resample(64)

        assertEquals(64, coarse.size)
        // The global extremes must survive reduction; averaging would lose them.
        assertEquals(fine.maxima.max(), coarse.maxima.max(), 1e-6f)
        assertEquals(fine.minima.min(), coarse.minima.min(), 1e-6f)
    }

    @Test
    fun `resampling to the same size is identity`() {
        val fine = PeakEnvelope.compute(FloatArray(256) { it / 256f }, buckets = 32)
        assertTrue(fine === fine.resample(32))
    }

    @Test
    fun `survives a serialisation round trip`() {
        val original = PeakEnvelope.compute(
            FloatArray(2048) { kotlin.math.sin(it * 0.05).toFloat() },
            buckets = 256,
        )
        val restored = PeakEnvelope.fromByteArray(original.toByteArray())

        assertEquals(original.size, restored.size)
        for (i in 0 until original.size) {
            assertEquals(original.minima[i], restored.minima[i], 0f)
            assertEquals(original.maxima[i], restored.maxima[i], 0f)
        }
    }

    @Test
    fun `content bounds trim leading and trailing silence`() {
        val samples = FloatArray(10_000)
        for (i in 3000 until 7000) samples[i] = 0.6f
        val bounds = requireNotNull(PeakEnvelope.contentBounds(samples, windowSize = 512))

        // Bounds land within one analysis window of the true edges.
        assertTrue("start ${bounds.first}", bounds.first in 2488..3000)
        assertTrue("end ${bounds.last}", bounds.last in 6999..7511)
    }

    @Test
    fun `content bounds ignore sub-threshold noise`() {
        val samples = FloatArray(10_000) { 0.005f }
        for (i in 4000 until 5000) samples[i] = 0.9f
        val bounds = requireNotNull(PeakEnvelope.contentBounds(samples, threshold = 0.02f, windowSize = 512))
        assertTrue("start ${bounds.first}", bounds.first >= 3584)
        assertTrue("end ${bounds.last}", bounds.last <= 5631)
    }

    @Test
    fun `content bounds are null for silence`() {
        assertNull(PeakEnvelope.contentBounds(FloatArray(4096)))
        assertNull(PeakEnvelope.contentBounds(FloatArray(0)))
    }

    @Test
    fun `rejects invalid sizes`() {
        try {
            PeakEnvelope.compute(FloatArray(16), buckets = 0)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
        try {
            PeakEnvelope(FloatArray(4), FloatArray(5))
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
    }
}
