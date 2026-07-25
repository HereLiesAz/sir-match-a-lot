package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeStretchTest {

    private val stretcher = TimeStretcher()

    @Test
    fun `ratio of one returns a copy`() {
        val input = SignalFixtures.sine(440.0, 0.5)
        val out = stretcher.stretch(input, 1.0)
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test
    fun `compressing shortens by the ratio`() {
        val input = SignalFixtures.sine(440.0, 2.0)
        val out = stretcher.stretch(input, 1.5)
        assertEquals((input.size / 1.5), out.size.toDouble(), 1.0)
    }

    @Test
    fun `expanding lengthens by the ratio`() {
        val input = SignalFixtures.sine(440.0, 2.0)
        val out = stretcher.stretch(input, 0.5)
        assertEquals((input.size / 0.5), out.size.toDouble(), 1.0)
    }

    @Test
    fun `tempo change preserves pitch`() {
        // This is the whole point of WSOLA: the duration moves, the frequency
        // does not. A plain resample would drag the pitch with it.
        val input = SignalFixtures.sine(440.0, 3.0)

        for (ratio in listOf(0.75, 1.25, 1.5)) {
            val out = stretcher.stretch(input, ratio)
            val frequency = SignalFixtures.dominantFrequency(out)
            assertEquals("ratio $ratio shifted pitch", 440.0, frequency, 8.0)
        }
    }

    @Test
    fun `a plain resample by contrast does shift pitch`() {
        // Guards the test above against passing for the wrong reason.
        val input = SignalFixtures.sine(440.0, 3.0)
        val out = FloatArray((input.size / 1.5).toInt())
        Resampler.render(input, 0.0, 1.5, out)
        val frequency = SignalFixtures.dominantFrequency(out)
        assertEquals(660.0, frequency, 8.0)
    }

    @Test
    fun `stretched output keeps a comparable level`() {
        // Constant-overlap-add with a periodic Hann window at half-frame hop
        // should sum to unity gain, not double or halve the signal.
        val input = SignalFixtures.sine(440.0, 2.0, amplitude = 0.5f)
        val out = stretcher.stretch(input, 1.25)

        fun rms(samples: FloatArray, from: Int, to: Int): Double {
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            return Math.sqrt(sum / (to - from))
        }

        val inputRms = rms(input, 4096, input.size - 4096)
        val outputRms = rms(out, 4096, out.size - 4096)
        assertEquals(inputRms, outputRms, inputRms * 0.15)
    }

    @Test
    fun `handles empty and very short input`() {
        assertEquals(0, stretcher.stretch(FloatArray(0), 1.5).size)
        val short = FloatArray(64) { it / 64f }
        val out = stretcher.stretch(short, 2.0)
        assertEquals(32, out.size)
    }

    @Test
    fun `rejects a non positive ratio`() {
        for (ratio in listOf(0.0, -1.0)) {
            try {
                stretcher.stretch(FloatArray(1024), ratio)
                throw AssertionError("expected ratio $ratio to be rejected")
            } catch (expected: IllegalArgumentException) {
                // intended
            }
        }
    }

    @Test
    fun `pitch shift moves frequency and keeps duration`() {
        val shifter = PitchShifter()
        val input = SignalFixtures.sine(440.0, 3.0)

        val up = shifter.shift(input, 12.0)
        assertEquals(input.size, up.size)
        assertEquals("an octave up should be 880 Hz", 880.0, SignalFixtures.dominantFrequency(up), 15.0)

        val down = shifter.shift(input, -12.0)
        assertEquals(input.size, down.size)
        assertEquals("an octave down should be 220 Hz", 220.0, SignalFixtures.dominantFrequency(down), 8.0)
    }

    @Test
    fun `pitch shift of zero semitones is a copy`() {
        val shifter = PitchShifter()
        val input = SignalFixtures.sine(440.0, 0.5)
        val out = shifter.shift(input, 0.0)
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test
    fun `a semitone shift is about six percent`() {
        val shifter = PitchShifter()
        val input = SignalFixtures.sine(440.0, 3.0)
        val up = shifter.shift(input, 1.0)
        // 440 * 2^(1/12) = 466.16
        assertEquals(466.16, SignalFixtures.dominantFrequency(up), 8.0)
        assertTrue(true)
    }
}
