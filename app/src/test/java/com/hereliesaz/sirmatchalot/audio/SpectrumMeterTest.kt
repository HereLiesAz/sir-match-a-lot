package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The band meter that drives the background.
 *
 * The claim is that three bands separate what one level cannot: that a kick
 * moves different lights from a hi-hat. So the tests feed it tones at known
 * frequencies and check the right band answers — and check the attack/release
 * asymmetry, which is what makes lights read as lights rather than as a strobe.
 */
class SpectrumMeterTest {

    private val sampleRate = 44_100
    private val blockFrames = 512

    /** A block of interleaved stereo at [hz]. */
    private fun tone(hz: Double, amplitude: Float = 0.5f, startFrame: Int = 0): FloatArray {
        val out = FloatArray(blockFrames * Deck.CHANNELS)
        for (i in 0 until blockFrames) {
            val sample = (sin(2 * PI * hz * (startFrame + i) / sampleRate) * amplitude).toFloat()
            out[i * Deck.CHANNELS] = sample
            out[i * Deck.CHANNELS + 1] = sample
        }
        return out
    }

    private fun silence() = FloatArray(blockFrames * Deck.CHANNELS)

    /** Runs [blocks] blocks of a tone so the followers settle. */
    private fun settle(meter: SpectrumMeter, hz: Double, amplitude: Float = 0.5f, blocks: Int = 60) {
        for (block in 0 until blocks) {
            meter.measure(tone(hz, amplitude, block * blockFrames), blockFrames)
        }
    }

    @Test
    fun `a kick lights the low band, not the high one`() {
        val meter = SpectrumMeter(sampleRate)
        settle(meter, 60.0)

        assertTrue("low=${meter.low}", meter.low > 0.5f)
        assertTrue("high=${meter.high} should stay dark", meter.high < 0.1f)
    }

    @Test
    fun `a hi-hat lights the high band, not the low one`() {
        val meter = SpectrumMeter(sampleRate)
        settle(meter, 9_000.0)

        assertTrue("high=${meter.high}", meter.high > 0.5f)
        assertTrue("low=${meter.low} should stay dark", meter.low < 0.1f)
    }

    @Test
    fun `a voice lights the mid band`() {
        val meter = SpectrumMeter(sampleRate)
        settle(meter, SpectrumMeter.MID_CENTRE_HZ)

        assertTrue("mid=${meter.mid}", meter.mid > meter.low)
        assertTrue("mid=${meter.mid}", meter.mid > meter.high)
    }

    @Test
    fun `silence is dark`() {
        val meter = SpectrumMeter(sampleRate)
        settle(meter, 60.0)
        // Long enough for the slow release to finish.
        repeat(400) { meter.measure(silence(), blockFrames) }

        assertEquals(0f, meter.low, 0.02f)
        assertEquals(0f, meter.mid, 0.02f)
        assertEquals(0f, meter.high, 0.02f)
    }

    @Test
    fun `it rises faster than it falls`() {
        // The asymmetry is the difference between a light and a flashing
        // rectangle: a kick has to land on the frame it happens and then bloom
        // away, and no symmetric smoothing gives both.
        val rising = SpectrumMeter(sampleRate)
        rising.measure(tone(60.0), blockFrames)
        rising.measure(tone(60.0, startFrame = blockFrames), blockFrames)
        val afterTwoLoudBlocks = rising.low

        val falling = SpectrumMeter(sampleRate)
        settle(falling, 60.0)
        val settled = falling.low
        falling.measure(silence(), blockFrames)
        falling.measure(silence(), blockFrames)
        val afterTwoSilentBlocks = falling.low

        val rose = afterTwoLoudBlocks
        val fell = settled - afterTwoSilentBlocks
        assertTrue("rose $rose in two blocks, fell $fell in two", rose > fell * 2f)
    }

    @Test
    fun `levels never leave the range the lights expect`() {
        val meter = SpectrumMeter(sampleRate)
        // Far louder than anything the limiter would let through.
        settle(meter, 60.0, amplitude = 1f)

        assertTrue(meter.low in 0f..1f)
        assertTrue(meter.mid in 0f..1f)
        assertTrue(meter.high in 0f..1f)
    }

    @Test
    fun `reset puts the room in darkness`() {
        val meter = SpectrumMeter(sampleRate)
        settle(meter, 60.0)
        meter.reset()

        assertEquals(0f, meter.low, 0f)
        assertEquals(0f, meter.mid, 0f)
        assertEquals(0f, meter.high, 0f)
    }

    @Test
    fun `measuring does not modify the audio`() {
        // The meter sits in the render chain; if it touched the buffer it would
        // be an effect, not a meter.
        val meter = SpectrumMeter(sampleRate)
        val buffer = tone(440.0)
        val original = buffer.copyOf()

        meter.measure(buffer, blockFrames)

        for (i in buffer.indices) assertEquals(original[i], buffer[i], 0f)
    }

    @Test
    fun `an empty block is ignored rather than dividing by zero`() {
        val meter = SpectrumMeter(sampleRate)
        settle(meter, 60.0)
        val before = meter.low

        meter.measure(FloatArray(0), 0)

        assertEquals(before, meter.low, 0f)
    }
}
