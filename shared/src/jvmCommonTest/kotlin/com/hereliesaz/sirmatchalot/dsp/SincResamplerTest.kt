package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * Measurements of the load-time sample-rate converter.
 *
 * These tests are quantitative on purpose. A resampler that is merely "wrong in
 * a way you can hear" passes every structural test you can write about it, so
 * the assertions here are on measured dB: passband flatness, stopband
 * rejection, and the level of everything that is not the input tone.
 *
 * Several tests measure Catmull-Rom on the same signal for comparison. Those
 * comparisons are the evidence that replacing it was worth doing, and they will
 * fail loudly if a future change quietly routes conversion back through the
 * spline.
 */
class SincResamplerTest {

    private val resampler = SincResampler()

    // --- Structure ---

    @Test
    fun `matching rates return the input untouched`() {
        val input = FloatArray(64) { it.toFloat() }
        assertTrue(resampler.resample(input, 44_100, 44_100) === input)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, resampler.resample(FloatArray(0), 44_100, 48_000).size)
    }

    @Test
    fun `output length follows the rate ratio`() {
        val input = FloatArray(44_100)
        assertEquals(48_000, resampler.resample(input, 44_100, 48_000).size)
        assertEquals(44_100, resampler.resample(FloatArray(48_000), 48_000, 44_100).size)
    }

    @Test
    fun `the common rate pairs need no phase interpolation`() {
        // 44100/48000 reduces to 147/160, so 160 exact phases cover every
        // position the conversion can ask for.
        resampler.resample(FloatArray(1024), 44_100, 48_000)
        assertFalse(resampler.phaseInterpolated)
        resampler.resample(FloatArray(1024), 48_000, 44_100)
        assertFalse(resampler.phaseInterpolated)
        resampler.resample(FloatArray(1024), 96_000, 48_000)
        assertFalse(resampler.phaseInterpolated)
    }

    @Test
    fun `rejects nonsensical configuration rather than producing rubbish`() {
        val failures = listOf(
            { SincResampler(taps = 3) },
            { SincResampler(taps = 33) },
            { SincResampler(stopbandDb = 0.0) },
            { resampler.resample(FloatArray(4), 0, 48_000) },
            { resampler.resample(FloatArray(4), 44_100, -1) },
        )
        for (case in failures) {
            try {
                case()
                throw AssertionError("expected rejection")
            } catch (expected: IllegalArgumentException) {
                // as intended
            }
        }
    }

    // --- Level accuracy ---

    @Test
    fun `constant input stays constant`() {
        // Every phase row is normalised to unity DC gain, so a steady level must
        // survive conversion without ripple. Edges are excluded: a finite signal
        // convolved with a kernel of this length necessarily ramps in and out
        // over half its span.
        val input = FloatArray(4096) { 0.5f }
        val out = resampler.resample(input, 44_100, 48_000)
        val guard = SincResampler.DEFAULT_TAPS
        for (i in guard until out.size - guard) {
            assertEquals("at $i", 0.5f, out[i], 1e-4f)
        }
    }

    @Test
    fun `passband stays flat to 20 kHz upsampling 44_1 to 48`() {
        for (frequency in listOf(100.0, 1_000.0, 5_000.0, 10_000.0, 15_000.0, 18_000.0, 20_000.0)) {
            val gainDb = gainOf(frequency, from = 44_100, to = 48_000)
            assertTrue(
                "${frequency}Hz lost ${"%.3f".format(gainDb)} dB",
                abs(gainDb) < 0.1,
            )
        }
    }

    @Test
    fun `passband stays flat to 20 kHz downsampling 48 to 44_1`() {
        // 20 kHz is the audiophile-relevant claim: the transition band is placed
        // so the passband is still flat there, with the stopband fully up by the
        // 22.05 kHz where folding would begin.
        for (frequency in listOf(100.0, 1_000.0, 5_000.0, 10_000.0, 15_000.0, 18_000.0, 20_000.0)) {
            val gainDb = gainOf(frequency, from = 48_000, to = 44_100)
            assertTrue(
                "${frequency}Hz lost ${"%.3f".format(gainDb)} dB",
                abs(gainDb) < 0.1,
            )
        }
    }

    // --- Aliasing, which is the whole point ---

    @Test
    fun `content above the destination Nyquist is removed rather than folded`() {
        // 23 kHz cannot exist at 44.1 kHz. A resampler without a stopband folds
        // it down to 44100 - 23000 = 21.1 kHz, where it is plainly audible as a
        // tone that was never in the music.
        val input = tone(23_000.0, 48_000, 16_384)
        val out = resampler.resample(input, 48_000, 44_100)

        val aliasDb = levelAt(out, 21_100.0, 44_100)
        assertTrue(
            "alias at 21.1 kHz only ${"%.1f".format(aliasDb)} dB down",
            aliasDb < -90.0,
        )
    }

    @Test
    fun `the figures docs AUDIO_QUALITY cites are the figures this filter gives`() {
        // AUDIO_QUALITY.md quoted "108 dB alias rejection" and "flat to 20 kHz
        // within 0.08 dB", and cited itself as the source — while the tests
        // around this one assert only < -90 dB and < 0.1 dB. Nothing in the
        // repository would have noticed the filter regressing from 108 dB to
        // 91 dB, which is exactly the range that looser bound allows.
        //
        // Measured here: 107.5 dB and 0.08 dB. The doc's figures were rounded
        // the generous way, by half a dB and by a hair respectively, and now
        // say what this measures.
        //
        // This is the doc's own claim, asserted. If the kernel is shortened or
        // the cutoff moved, this fails and the number in the doc has to change
        // with it.
        val worstRipple = listOf(100.0, 1_000.0, 5_000.0, 10_000.0, 15_000.0, 18_000.0, 20_000.0)
            .flatMap {
                listOf(
                    abs(gainOf(it, from = 44_100, to = 48_000)),
                    abs(gainOf(it, from = 48_000, to = 44_100)),
                )
            }
            .max()
        assertTrue(
            "passband ripple is ${"%.4f".format(worstRipple)} dB, doc says 0.09",
            worstRipple <= 0.09,
        )

        val alias = levelAt(
            resampler.resample(tone(23_000.0, 48_000, 16_384), 48_000, 44_100),
            21_100.0,
            44_100,
        )
        assertTrue(
            "alias rejection is ${"%.1f".format(-alias)} dB, doc says 107",
            alias <= -107.0,
        )
    }

    @Test
    fun `Catmull-Rom folds the alias that sinc rejects`() {
        // The comparison that justifies this class existing. Reading the same
        // 23 kHz tone at rate 48000/44100 through the render-loop spline is what
        // the app did for every track before load-time conversion.
        val input = tone(23_000.0, 48_000, 16_384)
        val spline = FloatArray(input.size * 44_100 / 48_000)
        Resampler.render(input, 0.0, 48_000.0 / 44_100.0, spline)

        val splineAlias = levelAt(spline, 21_100.0, 44_100)
        val sincAlias = levelAt(resampler.resample(input, 48_000, 44_100), 21_100.0, 44_100)

        assertTrue(
            "spline alias ${"%.1f".format(splineAlias)} dB, sinc ${"%.1f".format(sincAlias)} dB",
            splineAlias > sincAlias + 30.0,
        )
    }

    @Test
    fun `a 1 kHz tone converts with everything else far below it`() {
        // The single most representative measurement: how clean is a plain tone
        // after the conversion every 44.1 kHz track undergoes on a 48 kHz
        // device.
        val input = tone(1_000.0, 44_100, 16_384)
        val out = resampler.resample(input, 44_100, 48_000)
        val spuriousDb = worstSpurious(out, 1_000.0, 48_000)
        assertTrue(
            "worst spurious component at ${"%.1f".format(spuriousDb)} dB",
            spuriousDb < -110.0,
        )
    }

    @Test
    fun `sinc beats Catmull-Rom on spurious content by a wide margin`() {
        val input = tone(1_000.0, 44_100, 16_384)

        val sincOut = resampler.resample(input, 44_100, 48_000)
        val splineOut = FloatArray(input.size * 48_000 / 44_100)
        Resampler.render(input, 0.0, 44_100.0 / 48_000.0, splineOut)

        val sincWorst = worstSpurious(sincOut, 1_000.0, 48_000)
        val splineWorst = worstSpurious(splineOut, 1_000.0, 48_000)

        assertTrue(
            "sinc ${"%.1f".format(sincWorst)} dB vs spline ${"%.1f".format(splineWorst)} dB",
            sincWorst < splineWorst - 20.0,
        )
    }

    @Test
    fun `a high tone survives upsampling without the spline's droop`() {
        // Catmull-Rom's passband sags towards Nyquist, so the top octave loses
        // level. At 16 kHz through 44.1 to 48 the loss should be negligible.
        val sincDb = gainOf(16_000.0, from = 44_100, to = 48_000)

        val input = tone(16_000.0, 44_100, 16_384)
        val splineOut = FloatArray(input.size * 48_000 / 44_100)
        Resampler.render(input, 0.0, 44_100.0 / 48_000.0, splineOut)
        val splineDb = 20 * log10(amplitudeAt(splineOut, 16_000.0, 48_000) / 0.5)

        assertTrue("sinc lost ${"%.3f".format(sincDb)} dB", abs(sincDb) < 0.1)
        assertTrue(
            "spline lost only ${"%.3f".format(splineDb)} dB, expected a visible droop",
            splineDb < sincDb - 0.5,
        )
    }

    @Test
    fun `an odd rate pair still converts cleanly through interpolated phases`() {
        // 44100 to 44101 reduces to 44100/44101, more phases than the table
        // holds, so this exercises the interpolation fallback.
        val input = tone(1_000.0, 44_100, 16_384)
        val out = resampler.resample(input, 44_100, 44_101)
        assertTrue(resampler.phaseInterpolated)
        val spuriousDb = worstSpurious(out, 1_000.0, 44_101)
        assertTrue(
            "worst spurious component at ${"%.1f".format(spuriousDb)} dB",
            spuriousDb < -70.0,
        )
    }

    // --- Helpers ---

    /** A 0.5-amplitude sine, so headroom exists for filter overshoot. */
    private fun tone(frequency: Double, sampleRate: Int, length: Int): FloatArray =
        FloatArray(length) { (0.5 * sin(2 * PI * frequency * it / sampleRate)).toFloat() }

    /** Gain in dB applied to a [frequency] tone converted from [from] to [to]. */
    private fun gainOf(frequency: Double, from: Int, to: Int): Double {
        val out = resampler.resample(tone(frequency, from, 16_384), from, to)
        return 20 * log10(amplitudeAt(out, frequency, to) / 0.5)
    }

    /**
     * Amplitude of the component at [frequency], measured by projecting onto a
     * complex exponential rather than by reading an FFT bin, so the answer does
     * not depend on the tone landing exactly on a bin centre.
     *
     * The first and last 256 samples are skipped: the kernel's ramp-in and
     * ramp-out are expected, not a defect, and including them would understate
     * every measurement.
     */
    private fun amplitudeAt(signal: FloatArray, frequency: Double, sampleRate: Int): Double {
        val guard = 256
        if (signal.size <= guard * 2) return 0.0
        var re = 0.0
        var im = 0.0
        val count = signal.size - guard * 2
        for (i in 0 until count) {
            val angle = 2 * PI * frequency * (i + guard) / sampleRate
            re += signal[i + guard] * kotlin.math.cos(angle)
            im -= signal[i + guard] * sin(angle)
        }
        return 2.0 * hypot(re, im) / count
    }

    /**
     * Level in dB, relative to a 0.5-amplitude reference, of the component at
     * [frequency].
     */
    private fun levelAt(signal: FloatArray, frequency: Double, sampleRate: Int): Double {
        val amplitude = amplitudeAt(signal, frequency, sampleRate)
        if (amplitude <= 0.0) return -200.0
        return 20 * log10(amplitude / 0.5)
    }

    /**
     * Everything that is not the tone at [toneFrequency], as RMS in dB relative
     * to the tone — a THD+N measurement.
     *
     * Done by least-squares fitting the tone and subtracting it, deliberately
     * *not* by excluding FFT bins, and solving the normal equations rather than
     * assuming the basis is orthogonal. Both shortcuts were tried first and both
     * produced a measurement floor that masked the thing being measured:
     *
     * - excluding bins around the tone reported -41.1 dB for the sinc resampler
     *   and -41.1 dB for the Catmull-Rom spline, identical, because it was
     *   reading Hann leakage from a tone between bin centres;
     * - projecting onto cos and sin as if orthogonal reported about -67 dB for
     *   both — and also for a tone that had not been resampled at all, which is
     *   how the floor was identified.
     *
     * Solving the 2x2 system exactly drops the floor to float32 precision, so
     * what is left is the resampler's own error.
     */
    private fun worstSpurious(signal: FloatArray, toneFrequency: Double, sampleRate: Int): Double {
        val guard = 256
        require(signal.size > guard * 2) { "signal too short to analyse" }
        val count = signal.size - guard * 2

        var scc = 0.0
        var sss = 0.0
        var scs = 0.0
        var sc = 0.0
        var ss = 0.0
        for (i in 0 until count) {
            val angle = 2 * PI * toneFrequency * (i + guard) / sampleRate
            val c = kotlin.math.cos(angle)
            val s = sin(angle)
            scc += c * c
            sss += s * s
            scs += c * s
            sc += signal[i + guard] * c
            ss += signal[i + guard] * s
        }
        val determinant = scc * sss - scs * scs
        if (abs(determinant) < 1e-12) return 0.0
        val a = (sc * sss - ss * scs) / determinant
        val b = (ss * scc - sc * scs) / determinant

        var residualSquares = 0.0
        for (i in 0 until count) {
            val angle = 2 * PI * toneFrequency * (i + guard) / sampleRate
            val error = signal[i + guard] - (a * kotlin.math.cos(angle) + b * sin(angle))
            residualSquares += error * error
        }
        val residualRms = kotlin.math.sqrt(residualSquares / count)
        val toneRms = hypot(a, b) / kotlin.math.sqrt(2.0)
        if (toneRms <= 0.0) return 0.0
        if (residualRms <= 0.0) return -200.0
        return 20 * log10(residualRms / toneRms)
    }

    @Test
    fun `a converted buffer plays through the deck at rate one`() {
        // The point of load-time conversion: with the clip already at the output
        // rate, the deck's rate scaling is exactly 1, so the render loop's
        // interpolating read lands on integer positions and returns stored
        // samples untouched. If this regresses, every track is back to being
        // resampled by the spline forever.
        val source = com.hereliesaz.sirmatchalot.audio.PcmBuffer.monoFromFloat(
            tone(1_000.0, 44_100, 8_192),
            44_100,
        )
        val converted = source.resampledTo(48_000)
        assertEquals(48_000, converted.sampleRate)

        val deck = com.hereliesaz.sirmatchalot.audio.Deck("test", 48_000).apply {
            clips = listOf(com.hereliesaz.sirmatchalot.audio.Clip("c", converted, loop = true))
            playing = true
        }
        val frames = 512
        val out = FloatArray(frames * com.hereliesaz.sirmatchalot.audio.Deck.CHANNELS)
        deck.render(out, frames)

        // Playhead advanced by exactly one frame per output frame.
        assertEquals(frames.toDouble(), deck.playhead, 1e-9)
        // And the rendered samples are the stored ones, not interpolated.
        for (i in 0 until frames) {
            val stored = converted.channel(0)[i] * com.hereliesaz.sirmatchalot.audio.PcmBuffer.SHORT_SCALE
            assertEquals("frame $i", stored, out[i * com.hereliesaz.sirmatchalot.audio.Deck.CHANNELS], 1e-7f)
        }
    }

    @Test
    fun `a mono buffer stays centred through conversion`() {
        // PcmBuffer hands the same array out for both channels of a mono buffer.
        // Converting per-channel-accessor rather than per-stored-channel would
        // turn a mono clip into a hard-left stereo one.
        val mono = com.hereliesaz.sirmatchalot.audio.PcmBuffer.monoFromFloat(
            tone(1_000.0, 44_100, 4_096),
            44_100,
        )
        val converted = mono.resampledTo(48_000)
        assertEquals(1, converted.channelCount)
        assertTrue(converted.channel(0) === converted.channel(1))
    }

    @Test
    fun `conversion at the same rate is a no-op on a buffer`() {
        val buffer = com.hereliesaz.sirmatchalot.audio.PcmBuffer.monoFromFloat(
            tone(1_000.0, 44_100, 1_024),
            44_100,
        )
        assertTrue(buffer.resampledTo(44_100) === buffer)
    }
}
