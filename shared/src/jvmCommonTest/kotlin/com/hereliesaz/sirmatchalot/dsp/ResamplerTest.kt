package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ResamplerTest {

    @Test
    fun `reads exact samples at integer positions`() {
        val source = floatArrayOf(0f, 0.25f, -0.5f, 0.75f, -1f)
        for (i in source.indices) {
            assertEquals(source[i], Resampler.read(source, i.toDouble()), 0f)
        }
    }

    @Test
    fun `returns silence outside the buffer`() {
        // "Outside" is below zero or at or past `size`. This used to assert
        // silence at 2.001 of a three-sample buffer — a position that is inside
        // it — which is the defect written down as a requirement: every
        // fractional playhead in the last sample interval returned 0.
        val source = floatArrayOf(1f, 1f, 1f)
        assertEquals(0f, Resampler.read(source, -0.001), 0f)
        assertEquals(0f, Resampler.read(source, 3.0), 0f)
        assertEquals(0f, Resampler.read(source, 3.001), 0f)
        assertEquals(1f, Resampler.read(source, 2.001), 1e-5f)
    }

    @Test
    fun `interpolates monotonically along a ramp`() {
        val source = FloatArray(16) { it / 15f }
        var previous = -1f
        var position = 0.0
        while (position <= 15.0) {
            val value = Resampler.read(source, position)
            assertTrue("not monotonic at $position", value >= previous - 1e-6f)
            previous = value
            position += 0.125
        }
    }

    @Test
    fun `reproduces a linear ramp exactly where all neighbours exist`() {
        // Catmull-Rom is exact on a straight line given four real control
        // points. In the first and last intervals the missing outer neighbour is
        // clamped to the edge sample, which deliberately trades exactness for
        // continuity, so those intervals are excluded here.
        val source = FloatArray(16) { it / 15f }
        var position = 1.0
        while (position <= 14.0) {
            assertEquals(
                "at $position",
                (position / 15.0).toFloat(),
                Resampler.read(source, position),
                1e-5f,
            )
            position += 0.125
        }
    }

    @Test
    fun `the final sample interval is read, not silenced`() {
        // The bound was `position > size - 1`, which excluded the whole last
        // sample interval rather than the region past the buffer. Anything in
        // it returned 0, and `value in 0f..1f` — which the range test below
        // asserts — is satisfied by 0, so nothing caught it.
        val source = FloatArray(16) { 1f }
        var position = 15.0
        while (position < 16.0) {
            assertEquals("at $position", 1f, Resampler.read(source, position), 1e-5f)
            position += 0.05
        }
        // And past the end really is silence.
        assertEquals(0f, Resampler.read(source, 16.0), 0f)
        assertEquals(0f, Resampler.read(source, 16.001), 0f)
    }

    @Test
    fun `a looping render of a constant emits no gaps`() {
        // The failure this guards: at any rate whose steps do not land on
        // integers, the playhead visits the final sample interval once per
        // revolution. Returning 0 there put a full-amplitude one-sample notch
        // in the output — a click train at the loop rate, on every
        // tempo-matched deck.
        val source = FloatArray(1024) { 0.5f }
        val out = FloatArray(8192)
        Resampler.renderLooping(source, 0.0, 1.037, 0, source.size, out)
        val worst = out.min()
        assertTrue("dropout to $worst inside a looping constant", worst > 0.45f)
    }

    @Test
    fun `clamped edges stay within the signal range`() {
        // The edge intervals must not overshoot, or a waveform would appear to
        // clip at the very start and end of every clip.
        val source = FloatArray(16) { it / 15f }
        var position = 0.0
        while (position <= 15.0) {
            val value = Resampler.read(source, position)
            assertTrue("overshoot $value at $position", value >= 0f && value <= 1f)
            position += 0.05
        }
    }

    @Test
    fun `renders forward at unit rate as a copy`() {
        val source = FloatArray(64) { kotlin.math.sin(it * 0.3).toFloat() }
        val out = FloatArray(64)
        val end = Resampler.render(source, 0.0, 1.0, out)

        assertEquals(64.0, end, 1e-9)
        for (i in 0 until 64) assertEquals(source[i], out[i], 1e-6f)
    }

    @Test
    fun `negative rate plays backwards`() {
        val source = FloatArray(32) { it.toFloat() }
        val out = FloatArray(32)
        Resampler.render(source, 31.0, -1.0, out)

        for (i in 0 until 32) {
            assertEquals("sample $i", (31 - i).toFloat(), out[i], 1e-4f)
        }
    }

    @Test
    fun `zero rate holds position`() {
        val source = FloatArray(32) { it.toFloat() }
        val out = FloatArray(8)
        val end = Resampler.render(source, 12.0, 0.0, out)

        assertEquals(12.0, end, 0.0)
        for (v in out) assertEquals(12f, v, 1e-4f)
    }

    @Test
    fun `signal stays continuous as the rate crosses zero`() {
        // A scratch decelerating through zero into reverse must not click, so
        // consecutive output samples must never jump more than the underlying
        // signal's own maximum step.
        val source = FloatArray(2048) { kotlin.math.sin(it * 0.05).toFloat() }
        val out = FloatArray(600)

        var position = 1000.0
        var rate = 0.6
        for (i in out.indices) {
            out[i] = Resampler.read(source, position)
            position += rate
            rate -= 0.002 // sweeps +0.6 -> -0.6
        }

        var maxStep = 0f
        for (i in 1 until out.size) {
            maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        }
        // The source's own per-sample step is at most 0.05; at |rate| <= 0.6 the
        // interpolated step cannot exceed that by much.
        assertTrue("discontinuity of $maxStep across zero crossing", maxStep < 0.06f)
    }

    @Test
    fun `looping wraps forward`() {
        val source = FloatArray(100) { it.toFloat() }
        val out = FloatArray(20)
        val end = Resampler.renderLooping(source, 8.0, 1.0, 8, 12, out)

        // Loop covers samples 8..11 then repeats.
        for (i in out.indices) {
            assertEquals("sample $i", (8 + i % 4).toFloat(), out[i], 1e-4f)
        }
        assertTrue("end position $end outside loop", end >= 8.0 && end < 12.0)
    }

    @Test
    fun `looping wraps backward`() {
        val source = FloatArray(100) { it.toFloat() }
        val out = FloatArray(8)
        val end = Resampler.renderLooping(source, 10.0, -1.0, 8, 12, out)

        val expected = floatArrayOf(10f, 9f, 8f, 11f, 10f, 9f, 8f, 11f)
        for (i in out.indices) {
            assertEquals("sample $i", expected[i], out[i], 1e-4f)
        }
        assertTrue("end position $end outside loop", end >= 8.0 && end < 12.0)
    }

    @Test
    fun `looping rejects an empty range`() {
        try {
            Resampler.renderLooping(FloatArray(10), 0.0, 1.0, 5, 5, FloatArray(4))
            throw AssertionError("expected an empty loop range to be rejected")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
    }
}
