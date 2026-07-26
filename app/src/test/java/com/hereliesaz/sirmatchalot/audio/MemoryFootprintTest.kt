package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The load path's memory behaviour.
 *
 * Reported as `OutOfMemoryError: Failed to allocate a 30437392 byte allocation`
 * on a 256 MB heap. Nothing here was wrong in the sense of producing wrong
 * samples — every allocation was correct, and there were simply too many of them
 * alive at once, each proportional to the length of the track.
 *
 * Correctness tests cannot catch that, because the results were right. These
 * assert the *shape* of the allocation instead: that the no-op paths return the
 * same object rather than a copy, and that the conversions read 16-bit storage
 * without widening a whole track to float first.
 */
class MemoryFootprintTest {

    private fun stereo(frames: Int, sampleRate: Int = 44_100): PcmBuffer =
        PcmBuffer(
            channels = arrayOf(
                ShortArray(frames) { (16_000 * sin(2 * PI * 440.0 * it / sampleRate)).toInt().toShort() },
                ShortArray(frames) { (16_000 * sin(2 * PI * 660.0 * it / sampleRate)).toInt().toShort() },
            ),
            sampleRate = sampleRate,
        )

    // --- No-op paths must not copy ---

    @Test
    fun `a slice covering the whole buffer returns the same buffer`() {
        // The common case: a track with no silence to trim. Copying it anyway
        // doubles a whole track in memory for no change.
        val buffer = stereo(1_000)
        assertSame(buffer, buffer.slice(0, 1_000))
    }

    @Test
    fun `a slice that is out of range but covers everything still returns the same buffer`() {
        val buffer = stereo(1_000)
        assertSame(buffer, buffer.slice(-5, 5_000))
    }

    @Test
    fun `a genuine slice does copy`() {
        val buffer = stereo(1_000)
        val sliced = buffer.slice(10, 900)
        assertNotSame(buffer, sliced)
        assertEquals(890, sliced.frameCount)
    }

    @Test
    fun `converting to the same rate returns the same buffer`() {
        val buffer = stereo(1_000)
        assertSame(buffer, buffer.resampledTo(44_100))
    }

    @Test
    fun `a zero pitch shift returns the same buffer`() {
        val buffer = stereo(1_000)
        assertSame(buffer, buffer.pitchShifted(0.0))
    }

    // --- Bounds scanning without materialising the track as float ---

    @Test
    fun `content bounds finds the same range as the float scan it replaces`() {
        val frames = 20_000
        val left = ShortArray(frames)
        val right = ShortArray(frames)
        for (i in 5_000 until 15_000) {
            val v = (16_000 * sin(2 * PI * 440.0 * i / 44_100)).toInt().toShort()
            left[i] = v
            right[i] = v
        }
        val buffer = PcmBuffer(arrayOf(left, right), 44_100)

        val direct = buffer.contentBounds(AudioDecoder.SILENCE_THRESHOLD)
        val viaFloat = com.hereliesaz.sirmatchalot.dsp.PeakEnvelope.contentBounds(
            buffer.toMonoFloat(),
            AudioDecoder.SILENCE_THRESHOLD,
        )
        assertEquals(viaFloat, direct)
    }

    @Test
    fun `silence has no content bounds`() {
        assertNull(PcmBuffer.silence(frameCount = 5_000).contentBounds())
    }

    @Test
    fun `an empty buffer has no content bounds`() {
        assertNull(PcmBuffer(arrayOf(ShortArray(0)), 44_100).contentBounds())
    }

    @Test
    fun `a channel loud only on one side is still found`() {
        // Bounds come from the summed channels precisely so that a channel which
        // is silent alone does not make the track look silent.
        val frames = 8_000
        val left = ShortArray(frames)
        for (i in 2_000 until 6_000) left[i] = 20_000
        val buffer = PcmBuffer(arrayOf(left, ShortArray(frames)), 44_100)
        val bounds = buffer.contentBounds(AudioDecoder.SILENCE_THRESHOLD)
        assertTrue("loud content was missed", bounds != null)
    }

    // --- The short-input resampler matches the float one ---

    @Test
    fun `resampling shorts gives the same result as widening then resampling`() {
        val frames = 8_192
        val shorts = ShortArray(frames) {
            (16_000 * sin(2 * PI * 1_000.0 * it / 44_100)).toInt().toShort()
        }
        val widened = FloatArray(frames) { shorts[it] * PcmBuffer.SHORT_SCALE }

        val fromShorts = com.hereliesaz.sirmatchalot.dsp.SincResampler()
            .resample(shorts, 44_100, 48_000)
        val fromFloats = com.hereliesaz.sirmatchalot.dsp.SincResampler()
            .resample(widened, 44_100, 48_000)

        assertEquals(fromFloats.size, fromShorts.size)
        for (i in fromFloats.indices) {
            assertEquals("sample $i", fromFloats[i], fromShorts[i], 1e-6f)
        }
    }

    @Test
    fun `resampling shorts at the same rate still widens to float`() {
        // The float overload returns its input untouched when rates match; the
        // short overload cannot, because its return type differs from its input.
        val shorts = ShortArray(64) { (it * 100).toShort() }
        val out = com.hereliesaz.sirmatchalot.dsp.SincResampler().resample(shorts, 44_100, 44_100)
        assertEquals(64, out.size)
        assertEquals(shorts[10] * PcmBuffer.SHORT_SCALE, out[10], 0f)
    }

    @Test
    fun `an empty short input yields an empty result`() {
        assertEquals(
            0,
            com.hereliesaz.sirmatchalot.dsp.SincResampler().resample(ShortArray(0), 44_100, 48_000).size,
        )
    }

    // --- Conversion still produces correct audio ---

    @Test
    fun `a converted stereo buffer keeps both channels and their lengths`() {
        val buffer = stereo(44_100)
        val converted = buffer.resampledTo(48_000)
        assertEquals(2, converted.channelCount)
        assertEquals(48_000, converted.sampleRate)
        assertEquals(converted.channels[0].size, converted.channels[1].size)
        // The two channels held different tones, so they must stay different.
        assertTrue(
            "channels became identical",
            !converted.channels[0].contentEquals(converted.channels[1]),
        )
    }

    @Test
    fun `a mono buffer stays mono through conversion`() {
        val mono = PcmBuffer.monoFromFloat(
            FloatArray(44_100) { (0.5 * sin(2 * PI * 440.0 * it / 44_100)).toFloat() },
            44_100,
        )
        val converted = mono.resampledTo(48_000)
        assertEquals(1, converted.channelCount)
        assertSame(converted.channel(0), converted.channel(1))
    }

    // --- Knowing the cost before paying it ---

    @Test
    fun `a probe estimates what a track will cost at the engine's rate`() {
        // Five minutes of stereo. The point of the estimate is to be able to
        // refuse a track before the decoder is holding it, so it has to be
        // close enough to decide on.
        val probe = AudioDecoder.AudioProbe(
            durationSeconds = 300.0,
            sampleRate = 44_100,
            channelCount = 2,
        )
        assertEquals(300L * 48_000 * 2 * 2, probe.decodedBytes(atRate = 48_000))
        assertEquals(300L * 22_050 * 2 * 2, probe.decodedBytes(atRate = 22_050))
    }

    @Test
    fun `halving the engine rate halves the estimate`() {
        // This is the arithmetic the sample-rate setting rests on. If it stops
        // holding, the advice the app gives on running out of memory — choose a
        // lower rate — stops being true.
        val probe = AudioDecoder.AudioProbe(600.0, 44_100, 2)
        assertEquals(probe.decodedBytes(48_000) / 2, probe.decodedBytes(24_000))
    }

    @Test
    fun `a mono source is estimated at half a stereo one`() {
        val stereo = AudioDecoder.AudioProbe(120.0, 44_100, 2)
        val mono = AudioDecoder.AudioProbe(120.0, 44_100, 1)
        assertEquals(stereo.decodedBytes(44_100) / 2, mono.decodedBytes(44_100))
    }

    @Test
    fun `a container that declares no duration estimates nothing`() {
        // Zero rather than a guess, so the caller can tell it learned nothing
        // and let the decode proceed rather than refusing a track on invented
        // arithmetic.
        val probe = AudioDecoder.AudioProbe(0.0, 44_100, 2)
        assertEquals(0L, probe.decodedBytes(48_000))
    }
}
