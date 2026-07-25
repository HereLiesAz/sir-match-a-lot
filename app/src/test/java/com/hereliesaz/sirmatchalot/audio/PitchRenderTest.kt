package com.hereliesaz.sirmatchalot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Rendering a pitch shift into a buffer.
 *
 * "Harmonize" and "auto pitch" have always computed a semitone shift and then
 * discarded it, so the app's whole harmonic-mixing premise made no audible
 * difference. These tests are about the shift being *heard*: they measure the
 * frequency that comes out, and the length that comes with it.
 */
class PitchRenderTest {

    private val sampleRate = 44_100

    @Test
    fun `a zero shift returns the same buffer`() {
        val buffer = tone(440.0, sampleRate)
        assertTrue(buffer.pitchShifted(0.0) === buffer)
    }

    @Test
    fun `shifting up an octave doubles the frequency`() {
        val shifted = tone(440.0, sampleRate).pitchShifted(12.0)
        val measured = dominantFrequency(shifted)
        // WSOLA is not exact, so allow a few percent — but an octave is a factor
        // of two, and anything near 440 would mean the shift did nothing.
        assertEquals(880.0, measured, 880.0 * 0.05)
    }

    @Test
    fun `shifting down a fifth lowers the frequency by the right ratio`() {
        val shifted = tone(600.0, sampleRate).pitchShifted(-7.0)
        val expected = 600.0 * 2.0.pow(-7.0 / 12.0)
        assertEquals(expected, dominantFrequency(shifted), expected * 0.05)
    }

    @Test
    fun `a shift preserves length, and therefore tempo`() {
        // This is the whole point of shifting pitch rather than changing rate: the
        // track must still be the same number of beats long.
        val original = tone(440.0, sampleRate)
        for (semitones in listOf(-5.0, -1.0, 1.0, 7.0)) {
            val shifted = original.pitchShifted(semitones)
            val drift = abs(shifted.frameCount - original.frameCount).toDouble() / original.frameCount
            assertTrue(
                "$semitones semitones changed length by ${"%.4f".format(drift * 100)}%",
                drift < 0.02,
            )
        }
    }

    @Test
    fun `shifting is derived from the buffer given, so repeats do not compound`() {
        // The ViewModel always shifts from the pristine decoded buffer for this
        // reason; this pins the property the buffer side must hold, which is that
        // shifting is a pure function of its input.
        val original = tone(440.0, sampleRate)
        val once = original.pitchShifted(12.0)
        val again = original.pitchShifted(12.0)
        assertEquals(once.frameCount, again.frameCount)
        for (i in 0 until once.frameCount) {
            assertEquals("frame $i", once.channel(0)[i].toLong(), again.channel(0)[i].toLong())
        }

        // And shifting an already-shifted buffer stacks the interval, which is
        // exactly why the caller must not do it.
        val twice = once.pitchShifted(12.0)
        assertEquals(1760.0, dominantFrequency(twice), 1760.0 * 0.08)
    }

    @Test
    fun `a mono buffer stays centred through a shift`() {
        val mono = tone(440.0, sampleRate)
        assertEquals(1, mono.channelCount)
        val shifted = mono.pitchShifted(5.0)
        assertEquals(1, shifted.channelCount)
        assertTrue(shifted.channel(0) === shifted.channel(1))
    }

    @Test
    fun `stereo channels stay the same length`() {
        val stereo = PcmBuffer.fromFloat(
            arrayOf(
                FloatArray(sampleRate) { (0.4 * sin(2 * PI * 440.0 * it / sampleRate)).toFloat() },
                FloatArray(sampleRate) { (0.4 * sin(2 * PI * 660.0 * it / sampleRate)).toFloat() },
            ),
            sampleRate,
        )
        val shifted = stereo.pitchShifted(3.0)
        assertEquals(2, shifted.channelCount)
        assertEquals(shifted.channels[0].size, shifted.channels[1].size)
    }

    // --- The keylock arithmetic ---

    @Test
    fun `keylock correction cancels the pitch drag of a tempo change`() {
        // Playing at rate r raises pitch by 12*log2(r) semitones. Rendering the
        // negative of that into the clip means a beat-match changes tempo without
        // moving the key — which is what stops two compatible keys from drifting
        // apart the moment they are synced.
        for (ratio in listOf(0.9, 0.95, 1.05, 1.12)) {
            val drag = 12.0 * ln(ratio) / ln(2.0)
            val correction = -12.0 * ln(ratio) / ln(2.0)
            assertEquals("ratio $ratio", 0.0, drag + correction, 1e-12)
        }
    }

    @Test
    fun `a rendered keylock correction holds the pitch through the rate change`() {
        // End to end: shift the buffer by the correction, then read it back at the
        // tempo ratio, and the frequency should be where it started.
        val ratio = 1.06
        val correction = -12.0 * ln(ratio) / ln(2.0)
        val shifted = tone(440.0, sampleRate).pitchShifted(correction)

        // Reading at `ratio` is what the deck's rate does.
        val played = FloatArray((shifted.frameCount / ratio).toInt())
        com.hereliesaz.sirmatchalot.dsp.Resampler.render(
            FloatArray(shifted.frameCount) { shifted.channel(0)[it] * PcmBuffer.SHORT_SCALE },
            0.0,
            ratio,
            played,
        )
        assertEquals(440.0, dominantFrequency(played, sampleRate), 440.0 * 0.05)
    }

    @Test
    fun `the harmonic interval and the keylock correction add`() {
        // A +2 semitone key match at a 1.06 tempo ratio should render
        // 2 - 12*log2(1.06) = about +1.0 semitones, not +2 and not -1.
        val ratio = 1.06
        val total = 2 + -12.0 * ln(ratio) / ln(2.0)
        assertEquals(0.991, total, 0.01)

        val shifted = tone(440.0, sampleRate).pitchShifted(total)
        val played = FloatArray((shifted.frameCount / ratio).toInt())
        com.hereliesaz.sirmatchalot.dsp.Resampler.render(
            FloatArray(shifted.frameCount) { shifted.channel(0)[it] * PcmBuffer.SHORT_SCALE },
            0.0,
            ratio,
            played,
        )
        // Heard pitch should be the +2 semitones asked for, with the rate's own
        // contribution cancelled.
        val expected = 440.0 * 2.0.pow(2.0 / 12.0)
        assertEquals(expected, dominantFrequency(played, sampleRate), expected * 0.05)
    }

    // --- Helpers ---

    private fun tone(frequency: Double, samples: Int): PcmBuffer =
        PcmBuffer.monoFromFloat(
            FloatArray(samples) { (0.4 * sin(2 * PI * frequency * it / sampleRate)).toFloat() },
            sampleRate,
        )

    private fun dominantFrequency(buffer: PcmBuffer): Double =
        dominantFrequency(
            FloatArray(buffer.frameCount) { buffer.channel(0)[it] * PcmBuffer.SHORT_SCALE },
            buffer.sampleRate,
        )

    /**
     * Strongest frequency present, found by projecting onto a bank of candidate
     * exponentials over the interior of the signal.
     *
     * A direct search rather than an FFT peak, because the answer needs to be
     * finer than a bin and WSOLA's output is not a clean sinusoid — its peak bin
     * would quantise the measurement more coarsely than the tolerances here.
     */
    private fun dominantFrequency(signal: FloatArray, rate: Int = sampleRate): Double {
        val guard = signal.size / 8
        val count = signal.size - guard * 2
        if (count < 1_000) return 0.0

        var best = 0.0
        var bestAmplitude = -1.0
        var frequency = 100.0
        while (frequency < 3_000.0) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until count) {
                val angle = 2 * PI * frequency * (i + guard) / rate
                re += signal[i + guard] * kotlin.math.cos(angle)
                im -= signal[i + guard] * sin(angle)
            }
            val amplitude = hypot(re, im)
            if (amplitude > bestAmplitude) {
                bestAmplitude = amplitude
                best = frequency
            }
            frequency *= 1.002
        }
        return best
    }
}
