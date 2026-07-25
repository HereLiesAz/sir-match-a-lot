package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalised second-order IIR coefficients (a0 divided out).
 *
 * Designed with the Robert Bristow-Johnson audio EQ cookbook formulas.
 */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    /** Magnitude of the frequency response at [frequency] Hz. */
    fun magnitudeAt(frequency: Double, sampleRate: Int): Double {
        val w = 2.0 * Math.PI * frequency / sampleRate
        // Evaluate H(z) on the unit circle at z = e^{jw}.
        val numRe = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val numIm = -(b1 * sin(w) + b2 * sin(2 * w))
        val denRe = 1.0 + a1 * cos(w) + a2 * cos(2 * w)
        val denIm = -(a1 * sin(w) + a2 * sin(2 * w))
        return hypot(numRe, numIm) / hypot(denRe, denIm)
    }

    companion object {
        /** Unity-gain pass-through. */
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        private fun normalise(
            b0: Double, b1: Double, b2: Double,
            a0: Double, a1: Double, a2: Double,
        ) = BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun lowShelf(frequency: Double, sampleRate: Int, gainDb: Double, slope: Double = 1.0): BiquadCoefficients {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val twoSqrtAAlpha = 2 * sqrt(a) * alpha
            return normalise(
                b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha),
                b1 = 2 * a * ((a - 1) - (a + 1) * cosW),
                b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha),
                a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha,
                a1 = -2 * ((a - 1) + (a + 1) * cosW),
                a2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha,
            )
        }

        fun highShelf(frequency: Double, sampleRate: Int, gainDb: Double, slope: Double = 1.0): BiquadCoefficients {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val twoSqrtAAlpha = 2 * sqrt(a) * alpha
            return normalise(
                b0 = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha),
                b1 = -2 * a * ((a - 1) + (a + 1) * cosW),
                b2 = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha),
                a0 = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha,
                a1 = 2 * ((a - 1) - (a + 1) * cosW),
                a2 = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha,
            )
        }

        fun peaking(frequency: Double, sampleRate: Int, gainDb: Double, q: Double = 1.0): BiquadCoefficients {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return normalise(
                b0 = 1 + alpha * a,
                b1 = -2 * cosW,
                b2 = 1 - alpha * a,
                a0 = 1 + alpha / a,
                a1 = -2 * cosW,
                a2 = 1 - alpha / a,
            )
        }

        fun lowPass(frequency: Double, sampleRate: Int, q: Double = 0.7071): BiquadCoefficients {
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return normalise(
                b0 = (1 - cosW) / 2,
                b1 = 1 - cosW,
                b2 = (1 - cosW) / 2,
                a0 = 1 + alpha,
                a1 = -2 * cosW,
                a2 = 1 - alpha,
            )
        }

        fun highPass(frequency: Double, sampleRate: Int, q: Double = 0.7071): BiquadCoefficients {
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return normalise(
                b0 = (1 + cosW) / 2,
                b1 = -(1 + cosW),
                b2 = (1 + cosW) / 2,
                a0 = 1 + alpha,
                a1 = -2 * cosW,
                a2 = 1 - alpha,
            )
        }
    }
}

/**
 * A single biquad section in transposed direct form II — two state variables,
 * good numerical behaviour, and no allocation per sample.
 *
 * Not thread-safe; each channel owns its own instance.
 */
class Biquad(coefficients: BiquadCoefficients = BiquadCoefficients.IDENTITY) {

    var coefficients: BiquadCoefficients = coefficients
        set(value) {
            field = value
            // State is intentionally preserved across coefficient changes so
            // that sweeping a filter does not click.
        }

    private var s1 = 0.0
    private var s2 = 0.0

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }

    fun process(x: Float): Float {
        val c = coefficients
        val xd = x.toDouble()
        val y = c.b0 * xd + s1
        s1 = c.b1 * xd - c.a1 * y + s2
        s2 = c.b2 * xd - c.a2 * y
        return y.toFloat()
    }

    /** Filters [count] samples of [buffer] starting at [offset], in place. */
    fun processInPlace(buffer: FloatArray, offset: Int = 0, count: Int = buffer.size - offset) {
        for (i in offset until offset + count) {
            buffer[i] = process(buffer[i])
        }
    }
}
