package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Carrying the source's depth, rather than narrowing everything to 16 bits.
 *
 * The point of this is not that float is better in general — for a CD rip it is
 * strictly worse, twice the memory for information that is not there. The point
 * is that a 24-bit master carries detail 16 bits cannot hold, and quantising it
 * at decode destroys that before playback begins.
 *
 * So the decisive test is the quantisation floor: material below the 16-bit
 * least significant bit must survive a float buffer and must not survive a
 * 16-bit one.
 */
class HiResPcmTest {

    private val sampleRate = 48_000

    /**
     * A tone at [amplitude], which may be far below the 16-bit floor.
     *
     * One LSB of 16-bit is 1/32768, about 3.05e-5. A 24-bit source resolves
     * roughly 256 times finer.
     */
    private fun tone(frames: Int, amplitude: Float, hz: Double = 1_000.0): FloatArray =
        FloatArray(frames) { (sin(2 * PI * hz * it / sampleRate) * amplitude).toFloat() }

    private fun peak(samples: FloatArray): Float =
        samples.maxOfOrNull { abs(it) } ?: 0f

    // --- Storage ---

    @Test
    fun `a float buffer reports itself as float and a short buffer does not`() {
        assertTrue(PcmBuffer.float(arrayOf(FloatArray(10)), sampleRate).isFloat)
        assertFalse(PcmBuffer(arrayOf(ShortArray(10)), sampleRate).isFloat)
    }

    @Test
    fun `float storage costs four bytes a sample and short costs two`() {
        val frames = 1_000
        assertEquals(
            frames * 2 * 2L,
            PcmBuffer(Array(2) { ShortArray(frames) }, sampleRate).byteCount,
        )
        assertEquals(
            frames * 2 * 4L,
            PcmBuffer.float(Array(2) { FloatArray(frames) }, sampleRate).byteCount,
        )
    }

    @Test
    fun `detail below the 16-bit floor survives float storage`() {
        // A quarter of a 16-bit LSB. In a Short buffer this rounds to zero and
        // the track is silence; the whole of B14 is that it should not.
        val quiet = 1f / 32768f / 4f
        val samples = tone(4_096, quiet)

        val hiRes = PcmBuffer.float(arrayOf(samples), sampleRate)
        val narrowed = PcmBuffer.monoFromFloat(samples, sampleRate)

        assertTrue("the hi-res buffer lost it", peak(hiRes.toMonoFloat()) > quiet * 0.9f)
        assertEquals("the 16-bit buffer should quantise it away", 0f, peak(narrowed.toMonoFloat()), 0f)
    }

    @Test
    fun `a float buffer refuses the 16-bit accessor rather than quantising silently`() {
        // Converting on the fly would allocate and quantise a whole track at a
        // call site that looks free.
        val hiRes = PcmBuffer.float(arrayOf(FloatArray(10)), sampleRate)
        assertThrows(IllegalStateException::class.java) { hiRes.channels }
    }

    @Test
    fun `a short buffer refuses the float accessor`() {
        val cd = PcmBuffer(arrayOf(ShortArray(10)), sampleRate)
        assertThrows(IllegalStateException::class.java) { cd.floatChannels }
    }

    @Test
    fun `a buffer cannot hold both storages`() {
        // Enforced by the constructor, since holding both would cost the float
        // memory and the quantisation at once.
        assertThrows(IllegalArgumentException::class.java) {
            PcmBuffer.float(emptyArray(), sampleRate)
        }
    }

    @Test
    fun `mismatched channel lengths are rejected for float too`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmBuffer.float(arrayOf(FloatArray(10), FloatArray(20)), sampleRate)
        }
    }

    @Test
    fun `a mono float buffer plays centred`() {
        val mono = PcmBuffer.float(arrayOf(FloatArray(10) { 1f }), sampleRate)
        assertSame(
            "a mono clip asked for its right channel must not go silent",
            mono.floatChannel(0),
            mono.floatChannel(1),
        )
    }

    // --- Operations preserve depth ---

    @Test
    fun `slicing a float buffer keeps it float`() {
        val hiRes = PcmBuffer.float(arrayOf(tone(1_000, 0.5f)), sampleRate)
        val slice = hiRes.slice(100, 200)

        assertTrue(slice.isFloat)
        assertEquals(100, slice.frameCount)
    }

    @Test
    fun `a whole-buffer slice is the same instance, float or not`() {
        // Copying it anyway doubles a whole track's memory for no change.
        val hiRes = PcmBuffer.float(arrayOf(tone(1_000, 0.5f)), sampleRate)
        assertSame(hiRes, hiRes.slice(0, 1_000))
    }

    @Test
    fun `resampling a float buffer keeps it float`() {
        val hiRes = PcmBuffer.float(arrayOf(tone(4_096, 0.5f)), 96_000)
        val converted = hiRes.resampledTo(48_000)

        assertTrue("rounding to 16 bits here would undo the decode", converted.isFloat)
        assertEquals(48_000, converted.sampleRate)
        assertEquals(2_048.0, converted.frameCount.toDouble(), 32.0)
    }

    @Test
    fun `resampling preserves detail below the 16-bit floor`() {
        // The conversion is where a naive implementation would round-trip
        // through Short and throw the extra depth away.
        val quiet = 1f / 32768f / 4f
        val hiRes = PcmBuffer.float(arrayOf(tone(8_192, quiet)), 96_000)

        val converted = hiRes.resampledTo(48_000)

        assertTrue(
            "peak ${peak(converted.toMonoFloat())} — the quiet tone was quantised away",
            peak(converted.toMonoFloat()) > quiet * 0.5f,
        )
    }

    @Test
    fun `time-stretching a float buffer keeps it float`() {
        val hiRes = PcmBuffer.float(arrayOf(tone(16_384, 0.5f)), sampleRate)
        val stretched = hiRes.timeStretched(1.1)

        assertTrue(stretched.isFloat)
        assertTrue("should be longer", stretched.frameCount > hiRes.frameCount)
    }

    @Test
    fun `the stretch ratio is a length, in both directions`() {
        // This caught a real inversion: the ratio is documented as a length
        // multiplier, and it was being forwarded to a stretcher that takes a
        // speed. Conforming a 140 BPM track to a 120 BPM session sped it up to
        // 163 instead of slowing it to 120.
        val frames = 16_384
        val cd = PcmBuffer.monoFromFloat(tone(frames, 0.5f), sampleRate)

        val longer = cd.timeStretched(1.25)
        val shorter = cd.timeStretched(0.8)

        assertEquals(frames * 1.25, longer.frameCount.toDouble(), frames * 0.02)
        assertEquals(frames * 0.8, shorter.frameCount.toDouble(), frames * 0.02)
    }

    @Test
    fun `conforming a faster track to a slower session slows it down`() {
        // The arithmetic the ViewModel performs: stretch by trackBpm/referenceBpm.
        // A 140 BPM track in a 120 BPM session must come out longer, by exactly
        // the ratio of the tempos, or nothing on the platter lines up.
        val frames = 32_768
        val track = PcmBuffer.monoFromFloat(tone(frames, 0.5f), sampleRate)

        val conformed = track.timeStretched(140.0 / 120.0)

        assertEquals(frames * 140.0 / 120.0, conformed.frameCount.toDouble(), frames * 0.02)
        assertTrue("a 140 BPM track played at 120 lasts longer", conformed.frameCount > frames)
    }

    @Test
    fun `pitch-shifting a float buffer keeps it float`() {
        val hiRes = PcmBuffer.float(arrayOf(tone(16_384, 0.5f)), sampleRate)
        val shifted = hiRes.pitchShifted(2.0)

        assertTrue(shifted.isFloat)
    }

    @Test
    fun `a 16-bit buffer stays 16-bit through every operation`() {
        // The other half of the contract: a CD rip must not silently become
        // float and double the heap it occupies.
        val cd = PcmBuffer.monoFromFloat(tone(16_384, 0.5f), 44_100)

        assertFalse(cd.slice(0, 100).isFloat)
        assertFalse(cd.resampledTo(48_000).isFloat)
        assertFalse(cd.timeStretched(1.1).isFloat)
        assertFalse(cd.pitchShifted(2.0).isFloat)
    }

    @Test
    fun `a no-op transform returns the same buffer whatever the depth`() {
        val hiRes = PcmBuffer.float(arrayOf(tone(1_000, 0.5f)), sampleRate)
        assertSame(hiRes, hiRes.pitchShifted(0.0))
        assertSame(hiRes, hiRes.timeStretched(1.0))
        assertSame(hiRes, hiRes.resampledTo(sampleRate))
    }

    // --- Reading ---

    @Test
    fun `content bounds find the same edges in float as in 16-bit`() {
        val frames = 8_000
        val samples = FloatArray(frames)
        for (i in 2_000 until 6_000) {
            samples[i] = (sin(2 * PI * 440.0 * i / sampleRate) * 0.5).toFloat()
        }

        val hiResBounds = PcmBuffer.float(arrayOf(samples), sampleRate).contentBounds()
        val cdBounds = PcmBuffer.monoFromFloat(samples, sampleRate).contentBounds()

        assertNotEquals(null, hiResBounds)
        assertEquals(cdBounds!!.first, hiResBounds!!.first)
        assertEquals(cdBounds.last, hiResBounds.last)
    }

    @Test
    fun `mono summing matches between depths for material both can hold`() {
        val samples = tone(4_096, 0.5f)
        val hiRes = PcmBuffer.float(arrayOf(samples), sampleRate).toMonoFloat()
        val cd = PcmBuffer.monoFromFloat(samples, sampleRate).toMonoFloat()

        // Within one 16-bit quantisation step: the float path is exact and the
        // 16-bit one is rounded, and that difference is the only one there
        // should be.
        for (i in samples.indices) {
            assertEquals(hiRes[i], cd[i], 1f / 32768f)
        }
    }

    @Test
    fun `float storage does not clamp above full scale`() {
        // A hi-res master can exceed full scale between samples; the limiter at
        // the end of the chain is where that is dealt with. Clamping at storage
        // would bake the clipping in permanently.
        val hot = PcmBuffer.float(arrayOf(floatArrayOf(1.5f, -1.5f)), sampleRate)

        assertEquals(1.5f, hot.floatChannel(0)[0], 0f)
        assertEquals(-1.5f, hot.floatChannel(0)[1], 0f)
    }
}
