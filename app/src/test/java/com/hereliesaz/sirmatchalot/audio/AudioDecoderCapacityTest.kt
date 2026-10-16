package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How much the decoder allocates before it has decoded anything. */
class AudioDecoderCapacityTest {

    @Test
    fun `a normal duration gets the estimate plus slack`() {
        val frames = AudioDecoder.initialCapacityFrames(durationUs = 180_000_000L, sampleRate = 44_100)
        val estimate = 180 * 44_100
        assertEquals(estimate + estimate / 32 + 44_100, frames)
    }

    @Test
    fun `a header claiming hours is capped rather than trusted`() {
        val frames = AudioDecoder.initialCapacityFrames(durationUs = 10L * 3600 * 1_000_000, sampleRate = 192_000)
        assertEquals((192_000L * AudioDecoder.MAX_INITIAL_SECONDS).toInt(), frames)
    }

    @Test
    fun `an absurd duration does not overflow`() {
        val frames = AudioDecoder.initialCapacityFrames(durationUs = Long.MAX_VALUE, sampleRate = 48_000)
        assertTrue(frames in 1..(48_000L * AudioDecoder.MAX_INITIAL_SECONDS).toInt())
    }

    @Test
    fun `no duration starts at one second`() {
        assertEquals(48_000, AudioDecoder.initialCapacityFrames(durationUs = null, sampleRate = 48_000))
        assertEquals(48_000, AudioDecoder.initialCapacityFrames(durationUs = -1, sampleRate = 48_000))
    }
}
