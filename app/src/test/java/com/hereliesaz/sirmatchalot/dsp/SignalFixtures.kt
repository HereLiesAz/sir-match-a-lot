package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic signals with known properties, for asserting against measurements. */
object SignalFixtures {

    const val SAMPLE_RATE = 44_100

    /** A sine of [frequency] Hz lasting [seconds]. */
    fun sine(
        frequency: Double,
        seconds: Double,
        sampleRate: Int = SAMPLE_RATE,
        amplitude: Float = 0.8f,
    ): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { i ->
            (amplitude * sin(2.0 * PI * frequency * i / sampleRate)).toFloat()
        }
    }

    /** Sum of sines, all starting in phase. */
    fun chord(
        frequencies: List<Double>,
        seconds: Double,
        sampleRate: Int = SAMPLE_RATE,
        amplitude: Float = 0.25f,
    ): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { i ->
            var sum = 0.0
            for (f in frequencies) {
                // A couple of harmonics each, so the chromagram sees something
                // closer to an instrument than a test tone.
                sum += sin(2.0 * PI * f * i / sampleRate)
                sum += 0.5 * sin(2.0 * PI * 2 * f * i / sampleRate)
                sum += 0.25 * sin(2.0 * PI * 3 * f * i / sampleRate)
            }
            (amplitude * sum / frequencies.size).toFloat()
        }
    }

    /**
     * A percussive click track at [bpm] with a decaying noise burst per beat and
     * a louder accent every fourth beat, so a downbeat is inferable.
     */
    fun clickTrack(
        bpm: Double,
        seconds: Double,
        sampleRate: Int = SAMPLE_RATE,
        firstBeatSeconds: Double = 0.0,
        beatsPerBar: Int = 4,
        seed: Int = 7,
    ): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val out = FloatArray(n)
        val random = Random(seed)
        val period = 60.0 / bpm
        val burstLength = (0.03 * sampleRate).toInt()

        var beat = 0
        while (true) {
            val time = firstBeatSeconds + beat * period
            val start = (time * sampleRate).toInt()
            if (start >= n) break
            if (start >= 0) {
                val accent = if (beat % beatsPerBar == 0) 1.0f else 0.55f
                for (i in 0 until burstLength) {
                    val index = start + i
                    if (index >= n) break
                    val decay = exp(-12.0 * i / burstLength).toFloat()
                    out[index] += accent * decay * (random.nextFloat() * 2f - 1f) * 0.9f
                }
            }
            beat++
        }
        return out
    }

    /** MIDI note number to frequency in Hz. */
    fun midiToHz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    /**
     * Dominant frequency of [samples], found by locating the largest FFT bin and
     * refining it with parabolic interpolation on the magnitude peak.
     */
    fun dominantFrequency(samples: FloatArray, sampleRate: Int = SAMPLE_RATE, fftSize: Int = 16_384): Double {
        require(samples.size >= fftSize) { "need at least $fftSize samples, had ${samples.size}" }
        val fft = Fft(fftSize)
        val window = Window.hann(fftSize)
        // Analyse from the middle, away from any windowing transient at the edges.
        val offset = (samples.size - fftSize) / 2
        val re = FloatArray(fftSize) { samples[offset + it] * window[it] }
        val im = FloatArray(fftSize)
        fft.forward(re, im)
        val magnitudes = FloatArray(fftSize / 2 + 1)
        fft.magnitudes(re, im, magnitudes)

        var peak = 1
        for (k in 1 until magnitudes.size) if (magnitudes[k] > magnitudes[peak]) peak = k
        if (peak == 0 || peak == magnitudes.size - 1) {
            return peak.toDouble() * sampleRate / fftSize
        }
        val left = magnitudes[peak - 1].toDouble()
        val centre = magnitudes[peak].toDouble()
        val right = magnitudes[peak + 1].toDouble()
        val denominator = left - 2 * centre + right
        val delta = if (denominator == 0.0) 0.0 else 0.5 * (left - right) / denominator
        return (peak + delta) * sampleRate / fftSize
    }
}
