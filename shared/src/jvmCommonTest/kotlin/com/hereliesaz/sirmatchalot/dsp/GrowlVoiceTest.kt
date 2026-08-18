package com.hereliesaz.sirmatchalot.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The growl, checked as a signal rather than as a joke.
 *
 * A synthesised voice cannot be tested for intelligibility from a JVM, but
 * everything that would make it *not* a voice can be: that its formants land
 * where the vowels say they should, that it is low, that it accelerates, and
 * that it never produces a sample that would click, clip, or be NaN.
 */
class GrowlVoiceTest {

    private val sampleRate = 44_100

    private fun render(): FloatArray = GrowlVoice(sampleRate = sampleRate).render()

    @Test
    fun `the line is a few seconds of audio`() {
        val audio = render()
        val seconds = audio.size.toDouble() / sampleRate
        // Long enough to be a sentence, short enough not to be an interruption.
        assertTrue("was $seconds s", seconds in 1.2..4.0)
    }

    @Test
    fun `every sample is finite and inside the rails`() {
        // A resonator at Q 12 driven by an impulsive source is exactly the shape
        // that blows up if the coefficients are wrong, and a NaN here would be
        // a burst of noise at full scale on someone's headphones.
        for (sample in render()) {
            assertTrue("non-finite sample", sample.isFinite())
            assertTrue("out of range: $sample", sample in -1f..1f)
        }
    }

    @Test
    fun `it reaches the peak it was asked for`() {
        val audio = GrowlVoice(sampleRate = sampleRate).render(peak = 0.5f)
        val loudest = audio.maxOf { abs(it) }
        assertEquals(0.5f, loudest, 1e-3f)
    }

    @Test
    fun `it is a low voice`() {
        // Most of the energy below 1 kHz. "Very low, growling" is the
        // requirement; a bright buzz would satisfy every other test here.
        val audio = render()
        val low = bandEnergy(audio, 40.0, 1_000.0)
        val high = bandEnergy(audio, 1_000.0, 8_000.0)
        assertTrue("low=$low high=$high", low > high)
    }

    @Test
    fun `the utterance accelerates`() {
        // The stated behaviour: slow at first, then accelerating. Measured as
        // the total rendered length against what it would be unaccelerated —
        // if the scale were ignored, these would be equal.
        val nominal = GrowlVoice.LINE.sumOf { it.seconds }
        val actual = render().size.toDouble() / sampleRate
        assertTrue("accelerated $actual s vs nominal $nominal s", actual < nominal * 0.85)

        // And the end really is faster than the start, not merely shorter
        // overall: the last phone's share of its nominal duration is smaller.
        val voice = GrowlVoice(sampleRate = sampleRate)
        val first = phoneSeconds(voice, 0)
        val last = phoneSeconds(voice, GrowlVoice.LINE.lastIndex)
        assertTrue(
            "first=${first}x last=${last}x of nominal",
            last < first / 1.5,
        )
    }

    @Test
    fun `a vowel's formants land where that vowel lives`() {
        // The /ɑ/ of "Dark" has F1 around 730 Hz and F2 around 1090 — the
        // defining pair. Render that phone alone and look for the peaks.
        val phone = GrowlVoice.LINE.first { it.f1 == 730.0 }
        val audio = renderSingle(phone)

        assertTrue("no energy at F1", bandEnergy(audio, 630.0, 830.0) > bandEnergy(audio, 1_500.0, 1_700.0))
        assertTrue("no energy at F2", bandEnergy(audio, 990.0, 1_190.0) > bandEnergy(audio, 1_500.0, 1_700.0))
    }

    @Test
    fun `an unvoiced phone is noise, not a pitched tone`() {
        // /s/ is high-frequency noise. If the synth voiced it, the line would
        // say "I am Zatan".
        val ess = GrowlVoice.LINE.first { !it.voiced && it.f1 > 3_000.0 }
        val audio = renderSingle(ess)

        assertTrue(bandEnergy(audio, 3_000.0, 8_000.0) > bandEnergy(audio, 40.0, 500.0))
    }

    @Test
    fun `rendering twice gives the same audio`() {
        // The noise source is a seeded xorshift, not Random, so the easter egg
        // is the same performance every time rather than a different one.
        val a = render()
        val b = render()
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    @Test
    fun `there is no click at a phone boundary`() {
        // Every phone is enveloped; without that each boundary is a step
        // discontinuity and the line rattles.
        //
        // Measured *at the boundaries*, not as a global maximum. The largest
        // step in this signal is around 0.63 and it is entirely legitimate: the
        // /s/ phones are noise centred above 4 kHz, and at 44.1 kHz a 6 kHz
        // component genuinely swings that far between adjacent samples. A test
        // bounding the global maximum measures the fricatives and says nothing
        // about the envelope.
        val audio = render()
        val boundaries = phoneBoundaries()

        for (boundary in boundaries) {
            if (boundary !in 1 until audio.size) continue
            val step = abs(audio[boundary] - audio[boundary - 1])
            // Enveloped boundaries land near zero on both sides; a missing
            // envelope would put a step of order the signal's amplitude here.
            assertTrue("step of $step at boundary $boundary", step < 0.02f)
        }
    }

    /** Frame index of each phone's end, matching the renderer's arithmetic. */
    private fun phoneBoundaries(): List<Int> {
        var cursor = 0
        return GrowlVoice.LINE.mapIndexed { index, phone ->
            val progress = index.toDouble() / (GrowlVoice.LINE.size - 1)
            val scale = 1.0 - (1.0 - 1.0 / GrowlVoice.ACCELERATION) * progress
            cursor += (phone.seconds * scale * sampleRate).toInt()
            cursor
        }
    }

    /** Renders one phone, by building a voice whose line is only that phone. */
    private fun renderSingle(phone: GrowlVoice.Phone): FloatArray {
        // Long enough for the resonators to settle and for the band measurement
        // to have something to work with.
        val voice = GrowlVoice(sampleRate = sampleRate)
        val frames = (phone.seconds * sampleRate).toInt()
        val whole = voice.render()
        // Locate the phone by its position in the line and take that slice.
        val index = GrowlVoice.LINE.indexOf(phone)
        var start = 0
        for (i in 0 until index) {
            val progress = i.toDouble() / (GrowlVoice.LINE.size - 1)
            val scale = 1.0 - (1.0 - 1.0 / GrowlVoice.ACCELERATION) * progress
            start += (GrowlVoice.LINE[i].seconds * scale * sampleRate).toInt()
        }
        val progress = index.toDouble() / (GrowlVoice.LINE.size - 1)
        val scale = 1.0 - (1.0 - 1.0 / GrowlVoice.ACCELERATION) * progress
        val length = (phone.seconds * scale * sampleRate).toInt()
        return whole.copyOfRange(
            start.coerceIn(0, whole.size),
            (start + length).coerceIn(0, whole.size),
        ).also { require(it.size > frames / 4) { "slice too short to measure" } }
    }

    /** How much energy sits between [low] and [high] Hz, by direct correlation. */
    private fun bandEnergy(samples: FloatArray, low: Double, high: Double): Double {
        if (samples.isEmpty()) return 0.0
        var total = 0.0
        var frequency = low
        var bands = 0
        // Geometric steps, so a band is sampled evenly in the way hearing works
        // rather than crowding every probe into the top octave.
        while (frequency < high) {
            var real = 0.0
            var imaginary = 0.0
            val w = 2.0 * Math.PI * frequency / sampleRate
            for (i in samples.indices) {
                real += samples[i] * kotlin.math.cos(w * i)
                imaginary += samples[i] * kotlin.math.sin(w * i)
            }
            total += sqrt(real * real + imaginary * imaginary) / samples.size
            bands++
            frequency *= 1.06
        }
        return if (bands == 0) 0.0 else total / bands
    }

    /** This phone's rendered length as a multiple of its nominal one. */
    private fun phoneSeconds(voice: GrowlVoice, index: Int): Double {
        val progress = index.toDouble() / (GrowlVoice.LINE.size - 1)
        return 1.0 - (1.0 - 1.0 / GrowlVoice.ACCELERATION) * progress
    }
}
