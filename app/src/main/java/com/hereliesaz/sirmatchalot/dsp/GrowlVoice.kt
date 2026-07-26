package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * A formant synthesiser, used for exactly one sentence.
 *
 * The request: *"scratching too far backward triggers a very low, growling 'I am
 * Satan, Lord of Darkness', slow at first then accelerating."* `ScratchModel`
 * has fired that trigger, correctly and exactly once per gesture, for some time.
 * What it fired was a line of text on screen. A joke about a voice is not a
 * voice.
 *
 * It is **synthesised** rather than shipped as an audio file, and that is not
 * cleverness for its own sake: requirement F9 is "no built-in audio clips". A
 * bundled growl.ogg would be a built-in audio clip, whatever it contained.
 *
 * ## How it works
 *
 * A source-filter model, which is how speech synthesis has worked since the
 * 1950s and remains the right tool when the alphabet is one sentence long.
 *
 * - **Source.** Voiced sounds use a glottal pulse train at [f0]; unvoiced ones
 *   use white noise. The pulse is asymmetric rather than a bare impulse — a
 *   real glottis opens slowly and closes fast, and that asymmetry is most of
 *   why a voice sounds like a voice rather than like a buzzer.
 * - **Filter.** Three formant resonators in parallel, at the frequencies that
 *   define each vowel. F1 and F2 carry nearly all the vowel identity; F3 adds
 *   the "r" colouring that "Lord" and "Darkness" need.
 * - **Growl.** Two things, because growl is two things. Sub-harmonic content
 *   from a half-rate pulse mixed under the fundamental, and heavy period
 *   jitter, which is what makes a voice read as rough rather than merely low.
 *   Then a `tanh` saturator, gently, for the rasp.
 *
 * ## Slow at first, then accelerating
 *
 * Phoneme durations are scaled by a factor that falls across the utterance, so
 * the last word takes about half the time per sound that the first does, and
 * [f0] rises slightly with it. Read the [ACCELERATION] constant as "how much
 * faster the end is than the start".
 *
 * Pure arithmetic producing a `FloatArray`, so the whole thing is unit-tested:
 * that it is the length it claims, that its formants land where the vowels say
 * they should, that it accelerates, and that it never leaves [-1, 1].
 */
class GrowlVoice(
    /** Fundamental frequency. Low — a bass growl, well under a speaking voice. */
    private val f0: Double = 62.0,
    private val sampleRate: Int = 44_100,
) {

    /**
     * One speech sound: three formants and how long to hold it.
     *
     * @param f1 first formant, Hz. Tracks vowel openness.
     * @param f2 second formant, Hz. Tracks vowel frontness.
     * @param f3 third formant, Hz. Carries the r-colouring.
     * @param seconds nominal duration before acceleration is applied.
     * @param voiced false for fricatives and stops, which excite with noise.
     * @param amplitude relative loudness; consonants sit under vowels.
     */
    data class Phone(
        val f1: Double,
        val f2: Double,
        val f3: Double,
        val seconds: Double,
        val voiced: Boolean = true,
        val amplitude: Double = 1.0,
    )

    /**
     * Renders the line.
     *
     * @return mono audio at [sampleRate], normalised so its peak is [peak].
     */
    fun render(peak: Float = 0.85f): FloatArray {
        val phones = LINE
        val total = phones.indices.sumOf { durationFrames(phones[it], it, phones.size) }
        if (total <= 0) return FloatArray(0)

        val out = FloatArray(total)
        var cursor = 0
        var phase = 0.0
        var subPhase = 0.0
        var noiseState = 0x5EED_1234u

        for ((index, phone) in phones.withIndex()) {
            val frames = durationFrames(phone, index, phones.size)
            if (frames <= 0) continue

            // Resonators are rebuilt per phone rather than swept. A formant
            // transition would be smoother, but a stop consonant's formants
            // genuinely do jump, and the envelope's edges hide the rest.
            val resonators = arrayOf(
                Biquad(BiquadCoefficients.bandPass(phone.f1, sampleRate, q = 8.0)),
                Biquad(BiquadCoefficients.bandPass(phone.f2, sampleRate, q = 10.0)),
                Biquad(BiquadCoefficients.bandPass(phone.f3, sampleRate, q = 12.0)),
            )
            // Formant amplitudes fall with frequency, as they do in a real
            // vocal tract; a flat set reads as a synthesiser, not a throat.
            val gains = doubleArrayOf(1.0, 0.6, 0.3)

            // Pitch rises as the line accelerates, which is what makes the
            // acceleration read as menace rather than as a tape speeding up.
            val progress = index.toDouble() / max(1, phones.size - 1)
            val pitch = f0 * (1.0 + 0.28 * progress)

            for (i in 0 until frames) {
                val t = i.toDouble() / frames

                val excitation: Double
                if (phone.voiced) {
                    // Jitter: the period wanders a few percent, sample by
                    // sample. This is the growl.
                    noiseState = nextRandom(noiseState)
                    val jitter = 1.0 + 0.06 * (noiseState.toDouble() / UInt.MAX_VALUE.toDouble() - 0.5)
                    phase += pitch * jitter / sampleRate
                    if (phase >= 1.0) phase -= 1.0
                    // Half rate: the sub-harmonic that puts the growl an octave
                    // below the fundamental without moving the fundamental.
                    subPhase += pitch * 0.5 / sampleRate
                    if (subPhase >= 1.0) subPhase -= 1.0
                    excitation = glottalPulse(phase) + 0.55 * glottalPulse(subPhase)
                } else {
                    noiseState = nextRandom(noiseState)
                    excitation = noiseState.toDouble() / UInt.MAX_VALUE.toDouble() * 2.0 - 1.0
                }

                var sample = 0.0
                for (r in resonators.indices) {
                    sample += gains[r] * resonators[r].process(excitation.toFloat())
                }
                sample *= phone.amplitude * envelope(t)
                out[cursor + i] = tanh(sample * 1.8).toFloat()
            }
            cursor += frames
        }

        return normalise(out, peak)
    }

    /** How long this phone lasts once acceleration is applied. */
    private fun durationFrames(phone: Phone, index: Int, count: Int): Int {
        val progress = if (count <= 1) 0.0 else index.toDouble() / (count - 1)
        val scale = 1.0 - (1.0 - 1.0 / ACCELERATION) * progress
        return (phone.seconds * scale * sampleRate).toInt()
    }

    /**
     * The glottal source, over one period.
     *
     * A Rosenberg-style pulse: a slow opening over the first 60% of the period,
     * a fast closing over the next 20%, then silence. The abrupt closure is
     * what puts the harmonics in — an impulse train would give a buzz and a
     * sine would give a hum, and a voice is neither.
     */
    private fun glottalPulse(phase: Double): Double {
        val open = 0.6
        val close = 0.2
        return when {
            phase < open -> 0.5 * (1.0 - kotlin.math.cos(PI * phase / open))
            phase < open + close -> kotlin.math.cos(PI * (phase - open) / (2 * close))
            else -> 0.0
        }
    }

    /**
     * Per-phone amplitude envelope: a fast rise, a level hold, a fall.
     *
     * Without it, every phone boundary is a step discontinuity — a click on
     * every sound, which at this pitch is audible as a rattle.
     */
    private fun envelope(t: Double): Double {
        val attack = 0.12
        val release = 0.22
        return when {
            t < attack -> t / attack
            t > 1.0 - release -> (1.0 - t) / release
            else -> 1.0
        }
    }

    /** Scales so the loudest sample is [peak]. Silence stays silent. */
    private fun normalise(samples: FloatArray, peak: Float): FloatArray {
        var loudest = 0f
        for (sample in samples) {
            val magnitude = abs(sample)
            if (magnitude > loudest) loudest = magnitude
        }
        if (loudest <= 1e-6f) return samples
        val scale = peak / loudest
        for (i in samples.indices) samples[i] = (samples[i] * scale).coerceIn(-1f, 1f)
        return samples
    }

    /** xorshift32. Deterministic, so a rendered growl is the same every time. */
    private fun nextRandom(state: UInt): UInt {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x shr 17)
        x = x xor (x shl 5)
        return x
    }

    companion object {
        /** How much faster the last sound is than the first. */
        const val ACCELERATION = 2.2

        /**
         * "I am Satan, Lord of Darkness", as formants.
         *
         * Frequencies are the standard adult-male formant values for each
         * vowel, lowered slightly across the board because this voice is not an
         * adult male, it is whatever is under the record.
         */
        val LINE: List<Phone> = listOf(
            // "I" — the diphthong /aɪ/: open /a/ gliding to close-front /ɪ/.
            Phone(f1 = 700.0, f2 = 1220.0, f3 = 2600.0, seconds = 0.20),
            Phone(f1 = 400.0, f2 = 1900.0, f3 = 2550.0, seconds = 0.12),
            // "am" — /æ/ then the nasal /m/, which is low and quiet.
            Phone(f1 = 660.0, f2 = 1700.0, f3 = 2400.0, seconds = 0.17),
            Phone(f1 = 280.0, f2 = 1100.0, f3 = 2300.0, seconds = 0.10, amplitude = 0.55),
            // "Sa-" — /s/ is unvoiced noise high in the spectrum.
            Phone(f1 = 4000.0, f2 = 6500.0, f3 = 8000.0, seconds = 0.13, voiced = false, amplitude = 0.4),
            Phone(f1 = 700.0, f2 = 1150.0, f3 = 2500.0, seconds = 0.19),
            // "-tan" — /t/ burst, /ə/, /n/.
            Phone(f1 = 1800.0, f2 = 3200.0, f3 = 4200.0, seconds = 0.05, voiced = false, amplitude = 0.35),
            Phone(f1 = 550.0, f2 = 1400.0, f3 = 2450.0, seconds = 0.15),
            Phone(f1 = 300.0, f2 = 1600.0, f3 = 2600.0, seconds = 0.11, amplitude = 0.55),
            // "Lord" — /l/, the r-coloured /ɔr/ where F3 drops hard, /d/.
            Phone(f1 = 380.0, f2 = 950.0, f3 = 2600.0, seconds = 0.11, amplitude = 0.7),
            Phone(f1 = 560.0, f2 = 850.0, f3 = 2400.0, seconds = 0.17),
            Phone(f1 = 470.0, f2 = 1100.0, f3 = 1600.0, seconds = 0.14),
            Phone(f1 = 300.0, f2 = 1700.0, f3 = 2500.0, seconds = 0.05, amplitude = 0.5),
            // "of" — /ə/, /v/.
            Phone(f1 = 500.0, f2 = 1350.0, f3 = 2450.0, seconds = 0.10, amplitude = 0.8),
            Phone(f1 = 350.0, f2 = 1100.0, f3 = 2300.0, seconds = 0.07, amplitude = 0.5),
            // "Dark-" — /d/, the r-coloured /ɑr/, /k/ burst.
            Phone(f1 = 300.0, f2 = 1700.0, f3 = 2500.0, seconds = 0.05, amplitude = 0.5),
            Phone(f1 = 730.0, f2 = 1090.0, f3 = 2440.0, seconds = 0.18),
            Phone(f1 = 490.0, f2 = 1200.0, f3 = 1620.0, seconds = 0.12),
            Phone(f1 = 1400.0, f2 = 2200.0, f3 = 3000.0, seconds = 0.05, voiced = false, amplitude = 0.3),
            // "-ness" — /n/, /ɛ/, and a long trailing /s/ to end on.
            Phone(f1 = 300.0, f2 = 1600.0, f3 = 2600.0, seconds = 0.08, amplitude = 0.55),
            Phone(f1 = 530.0, f2 = 1840.0, f3 = 2480.0, seconds = 0.14),
            Phone(f1 = 4200.0, f2 = 6800.0, f3 = 8200.0, seconds = 0.22, voiced = false, amplitude = 0.4),
        )
    }
}
