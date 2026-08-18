package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotVoiceTest {

    private fun stereo(frames: Int) = FloatArray(frames * Deck.CHANNELS)

    @Test
    fun `an unloaded voice renders nothing and cannot be triggered`() {
        val voice = OneShotVoice()
        voice.trigger()

        assertFalse(voice.isPlaying)
        val out = stereo(16)
        voice.render(out, 16)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `a triggered voice plays centred on both channels`() {
        val voice = OneShotVoice().apply {
            load(floatArrayOf(0.5f, 0.25f))
            gain = 1f
            trigger()
        }

        val out = stereo(2)
        voice.render(out, 2)

        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(0.25f, out[2], 1e-6f)
        assertEquals(0.25f, out[3], 1e-6f)
    }

    @Test
    fun `it adds to the mix rather than replacing it`() {
        // A voice that overwrote the buffer would silence the music underneath
        // it for its whole length.
        val voice = OneShotVoice().apply {
            load(floatArrayOf(0.25f))
            gain = 1f
            trigger()
        }

        val out = floatArrayOf(0.5f, -0.5f)
        voice.render(out, 1)

        assertEquals(0.75f, out[0], 1e-6f)
        assertEquals(-0.25f, out[1], 1e-6f)
    }

    @Test
    fun `it stops at the end rather than reading past it`() {
        val voice = OneShotVoice().apply {
            load(floatArrayOf(1f, 1f))
            gain = 1f
            trigger()
        }

        val out = stereo(8)
        voice.render(out, 8)

        assertFalse("a one-shot does not loop", voice.isPlaying)
        // Two frames of audio, then silence — not a repeat and not a crash.
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(1f, out[2], 1e-6f)
        for (i in 4 until out.size) assertEquals(0f, out[i], 1e-6f)
    }

    @Test
    fun `retriggering restarts rather than layering`() {
        val voice = OneShotVoice().apply {
            load(floatArrayOf(1f, 0f, 0f, 0f))
            gain = 1f
            trigger()
        }

        voice.render(stereo(2), 2)
        voice.trigger()
        val out = stereo(1)
        voice.render(out, 1)

        // Back at the first sample. Two overlapping copies of a voice saying
        // the same sentence is not twice as good.
        assertEquals(1f, out[0], 1e-6f)
    }

    @Test
    fun `gain scales the voice`() {
        val voice = OneShotVoice().apply {
            load(floatArrayOf(1f))
            gain = 0.25f
            trigger()
        }

        val out = stereo(1)
        voice.render(out, 1)

        assertEquals(0.25f, out[0], 1e-6f)
    }

    @Test
    fun `loading new audio stops what was playing`() {
        // Swapping the buffer under a live playhead would read past the end of
        // a shorter one.
        val voice = OneShotVoice().apply {
            load(FloatArray(1000) { 1f })
            trigger()
        }
        voice.render(stereo(500), 500)

        voice.load(floatArrayOf(1f))

        assertFalse(voice.isPlaying)
        val out = stereo(4)
        voice.render(out, 4)
        assertTrue("nothing should play until triggered again", out.all { it == 0f })
    }

    @Test
    fun `loading empty audio leaves the voice unloaded`() {
        val voice = OneShotVoice()
        voice.load(FloatArray(0))

        assertFalse(voice.isLoaded)
        voice.trigger()
        assertFalse(voice.isPlaying)
    }

    @Test
    fun `rendering fewer frames than the buffer holds resumes where it left off`() {
        val voice = OneShotVoice().apply {
            load(floatArrayOf(0.1f, 0.2f, 0.3f))
            gain = 1f
            trigger()
        }

        val first = stereo(1)
        voice.render(first, 1)
        val second = stereo(2)
        voice.render(second, 2)

        assertEquals(0.1f, first[0], 1e-6f)
        assertEquals(0.2f, second[0], 1e-6f)
        assertEquals(0.3f, second[2], 1e-6f)
    }
}
