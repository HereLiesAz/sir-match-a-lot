package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-quality fixed-ratio sample-rate conversion: a Kaiser-windowed-sinc
 * polyphase filter.
 *
 * ## Why this exists
 *
 * [Resampler] reads at a fractional position with a 4-point Catmull-Rom spline.
 * That is the right tool for the render loop, where the rate changes
 * continuously — a scratch sweeping through zero into reverse cannot be served
 * by a fixed filter bank, and the spline is cheap and stays continuous.
 *
 * It is the wrong tool for sample-rate conversion, and until this class existed
 * it was doing that job too. `Deck.rateScale` divides the clip's rate by the
 * output rate, so a 44.1 kHz track on a 48 kHz device played at rate 0.919
 * *permanently* — every track, every moment, not just while scratching. A
 * 4-point spline has no stopband: its images fold back as aliases, and its
 * passband droops well before Nyquist. That was the largest of the four gaps in
 * `docs/AUDIT.md`'s quality section.
 *
 * Converting once at load time instead means the render loop runs at rate 1.0,
 * where the spline is an exact pass-through (an integer position returns the
 * stored sample untouched). The conversion cost is paid once per track, so it
 * can afford a filter that would be far too expensive per-sample.
 *
 * ## How
 *
 * Output sample `m` reads the source at `m * from / to`. Reduce `from/to` to
 * lowest terms `num/den` and the fractional part of that position takes exactly
 * `den` distinct values — so a table of `den` phases is not an approximation of
 * the phases needed, it is *all* of them, computed exactly. For the common
 * 44.1↔48 kHz pair `den` is 160 or 147.
 *
 * When `den` is impractically large (an unusual rate pair), the table is capped
 * and neighbouring phases are interpolated; [phaseInterpolated] reports which
 * happened, because "exact" and "very nearly exact" should not be indis­tin­guish­able
 * from outside.
 *
 * When downsampling, the cutoff moves towards the *destination* Nyquist so that
 * content which cannot be represented at the lower rate is removed before it can
 * fold. Skipping that step is precisely how a resampler produces aliases.
 *
 * ## Why the kernel is long
 *
 * The cutoff for 48→44.1 kHz sits at 22.05 kHz, and the transition band has to
 * fit between the top of the audio band and there — about 2 kHz. Kaiser's
 * estimate puts that at over a hundred taps for 100 dB, so 64 taps is not
 * "slightly soft", it is 20 dB of alias rejection instead of 100. That was
 * measured, not assumed: a 23 kHz tone came back only 19.7 dB down through a
 * 64-tap kernel. See [DEFAULT_TAPS] for the length actually chosen and the
 * measurements behind it. Kernels this long are affordable precisely because the
 * conversion happens once per track rather than once per sample.
 *
 * The cutoff is pulled back by half the transition width rather than placed at
 * the destination Nyquist, so the *stopband* — not the middle of the skirt —
 * begins where folding begins.
 *
 * @param taps kernel length. More taps buy a narrower transition band.
 * @param stopbandDb target attenuation of the filter's stopband, which sets the
 *   Kaiser window's beta.
 */
class SincResampler(
    private val taps: Int = DEFAULT_TAPS,
    private val stopbandDb: Double = DEFAULT_STOPBAND_DB,
) {
    init {
        require(taps >= 4 && taps % 2 == 0) { "taps must be even and at least 4, was $taps" }
        require(stopbandDb > 0) { "stopbandDb must be positive, was $stopbandDb" }
    }

    /** True if the last [resample] had to interpolate between table phases. */
    var phaseInterpolated: Boolean = false
        private set

    /**
     * Converts [input] from [fromRate] to [toRate].
     *
     * Returns [input] itself when the rates match, so the common case costs
     * nothing. An empty input yields an empty output.
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "rates must be positive, were $fromRate and $toRate" }
        if (fromRate == toRate || input.isEmpty()) {
            phaseInterpolated = false
            return input
        }

        val divisor = gcd(fromRate, toRate)
        val num = fromRate / divisor
        val den = toRate / divisor

        val ratio = fromRate.toDouble() / toRate
        // Downsampling must band-limit to the destination Nyquist; upsampling
        // keeps the source's own band. Expressed as a fraction of the source
        // Nyquist, which is what the kernel is written in terms of. Pulling back
        // by half the transition width puts the stopband, rather than the middle
        // of the skirt, at the frequency where folding starts.
        val edge = min(1.0, 1.0 / ratio)
        val cutoff = (edge - transitionWidth() / 2).coerceAtLeast(edge * 0.5)

        val phases = if (den <= MAX_PHASES) den else MAX_PHASES
        phaseInterpolated = den > MAX_PHASES
        val table = buildTable(phases, cutoff)

        val outputLength = ceil(input.size / ratio).toInt()
        val out = FloatArray(outputLength)
        val half = taps / 2

        for (m in 0 until outputLength) {
            // Exact position arithmetic in integers where possible: the whole
            // and fractional parts of m * num / den.
            val scaled = m.toLong() * num
            val base = (scaled / den).toInt()
            val remainder = (scaled % den).toInt()
            val from = base - half + 1

            out[m] = if (phaseInterpolated) {
                val exact = remainder.toDouble() / den * phases
                val low = exact.toInt().coerceAtMost(phases - 1)
                val blend = (exact - low).toFloat()
                val high = (low + 1) % phases
                // The wrapped top phase belongs to the next source sample, so
                // shift the tap window with it.
                val shift = if (low + 1 >= phases) 1 else 0
                convolveChecked(input, from, table[low]) * (1f - blend) +
                    convolveChecked(input, from + shift, table[high]) * blend
            } else if (from >= 0 && from + taps <= input.size) {
                // The overwhelmingly common case: the whole kernel is inside the
                // buffer, so the inner loop needs no bounds test at all.
                convolveInterior(input, from, table[remainder])
            } else {
                convolveChecked(input, from, table[remainder])
            }
        }
        return out
    }

    /**
     * As [resample], but reading 16-bit storage directly.
     *
     * Playback buffers are `ShortArray`
     * ([com.hereliesaz.sirmatchalot.audio.PcmBuffer]). Widening a whole track to
     * float just to feed the float overload allocates four bytes per frame —
     * twice the size of the storage being converted — on top of the output. On a
     * five-minute stereo track that is an extra 57 MB per channel, live at the
     * same moment as everything else in the load path, and it contributed to
     * running out of heap on a 256 MB device. Reading the shorts in the
     * convolution costs one multiply per tap and allocates nothing.
     */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "rates must be positive, were $fromRate and $toRate" }
        if (input.isEmpty()) {
            phaseInterpolated = false
            return FloatArray(0)
        }
        if (fromRate == toRate) {
            phaseInterpolated = false
            return FloatArray(input.size) { input[it] * SHORT_SCALE }
        }

        val divisor = gcd(fromRate, toRate)
        val num = fromRate / divisor
        val den = toRate / divisor
        val ratio = fromRate.toDouble() / toRate
        val edge = min(1.0, 1.0 / ratio)
        val cutoff = (edge - transitionWidth() / 2).coerceAtLeast(edge * 0.5)

        val phases = if (den <= MAX_PHASES) den else MAX_PHASES
        phaseInterpolated = den > MAX_PHASES
        val table = buildTable(phases, cutoff)

        val outputLength = ceil(input.size / ratio).toInt()
        val out = FloatArray(outputLength)
        val half = taps / 2

        for (m in 0 until outputLength) {
            val scaled = m.toLong() * num
            val base = (scaled / den).toInt()
            val remainder = (scaled % den).toInt()
            val from = base - half + 1

            out[m] = if (phaseInterpolated) {
                val exact = remainder.toDouble() / den * phases
                val low = exact.toInt().coerceAtMost(phases - 1)
                val blend = (exact - low).toFloat()
                val high = (low + 1) % phases
                val shift = if (low + 1 >= phases) 1 else 0
                convolveChecked(input, from, table[low]) * (1f - blend) +
                    convolveChecked(input, from + shift, table[high]) * blend
            } else if (from >= 0 && from + taps <= input.size) {
                convolveInterior(input, from, table[remainder])
            } else {
                convolveChecked(input, from, table[remainder])
            }
        }
        return out
    }

    private fun convolveInterior(input: ShortArray, from: Int, row: FloatArray): Float {
        var sum = 0f
        for (k in 0 until taps) sum += input[from + k] * SHORT_SCALE * row[k]
        return sum
    }

    private fun convolveChecked(input: ShortArray, from: Int, row: FloatArray): Float {
        var sum = 0f
        var index = from
        for (k in 0 until taps) {
            if (index >= 0 && index < input.size) sum += input[index] * SHORT_SCALE * row[k]
            index++
        }
        return sum
    }

    /** Dot product with the kernel wholly inside [input]. No bounds checks. */
    private fun convolveInterior(input: FloatArray, from: Int, row: FloatArray): Float {
        var sum = 0f
        for (k in 0 until taps) sum += input[from + k] * row[k]
        return sum
    }

    /**
     * Dot product for kernels that overhang either end of [input]. Reads outside
     * the buffer contribute zero, which is the correct boundary for a finite
     * signal.
     */
    private fun convolveChecked(input: FloatArray, from: Int, row: FloatArray): Float {
        var sum = 0f
        var index = from
        for (k in 0 until taps) {
            if (index >= 0 && index < input.size) sum += input[index] * row[k]
            index++
        }
        return sum
    }

    /**
     * Kaiser's estimate of the transition band width, as a fraction of Nyquist
     * on the source sample grid.
     */
    private fun transitionWidth(): Double =
        (stopbandDb - 8.0) / (2.285 * (taps - 1)) / PI

    /**
     * Kernel rows, one per phase, each normalised to unity DC gain.
     *
     * Normalising per row rather than trusting the analytic kernel removes the
     * small level ripple that otherwise varies from phase to phase — audible as
     * a faint whine at the beat frequency between the two rates, which is worse
     * than a fraction of a dB of overall gain error.
     */
    private fun buildTable(phases: Int, cutoff: Double): Array<FloatArray> {
        val beta = kaiserBeta(stopbandDb)
        val half = taps / 2
        val i0Beta = besselI0(beta)

        return Array(phases) { phase ->
            val fraction = phase.toDouble() / phases
            val row = FloatArray(taps)
            var sum = 0.0
            for (k in 0 until taps) {
                // Distance from the interpolation point to tap k, in source samples.
                val t = (k - half + 1) - fraction
                val value = cutoff * sinc(cutoff * t) * kaiser(t, half.toDouble(), beta, i0Beta)
                row[k] = value.toFloat()
                sum += value
            }
            if (abs(sum) > 1e-12) {
                val scale = (1.0 / sum).toFloat()
                for (k in 0 until taps) row[k] *= scale
            }
            row
        }
    }

    /** Normalised sinc, `sin(pi x) / (pi x)`, with the removable singularity filled in. */
    private fun sinc(x: Double): Double {
        if (abs(x) < 1e-12) return 1.0
        val pix = PI * x
        return sin(pix) / pix
    }

    /**
     * Kaiser window sampled at [t] over a half-width of [half] samples.
     *
     * Zero outside the window, so a tap that falls off the end contributes
     * nothing rather than wrapping.
     */
    private fun kaiser(t: Double, half: Double, beta: Double, i0Beta: Double): Double {
        val ratio = t / half
        if (abs(ratio) >= 1.0) return 0.0
        return besselI0(beta * sqrt(1.0 - ratio * ratio)) / i0Beta
    }

    /** Zeroth-order modified Bessel function of the first kind, by its power series. */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val halfX = x / 2.0
        var k = 1
        while (k < 64) {
            term *= (halfX / k) * (halfX / k)
            sum += term
            if (term < sum * 1e-17) break
            k++
        }
        return sum
    }

    /** Kaiser's empirical beta for a target stopband attenuation in dB. */
    private fun kaiserBeta(attenuationDb: Double): Double = when {
        attenuationDb > 50.0 -> 0.1102 * (attenuationDb - 8.7)
        attenuationDb >= 21.0 -> 0.5842 * Math.pow(attenuationDb - 21.0, 0.4) + 0.07886 * (attenuationDb - 21.0)
        else -> 0.0
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }

    companion object {
        /**
         * Chosen by measuring the 48→44.1 kHz conversion at several lengths
         * (alias rejection of a 23 kHz tone, and gain at 20 kHz):
         *
         * | taps | alias | 20 kHz | 60 s mono |
         * | ---: | ----: | -----: | --------: |
         * |   64 |  -20 dB |      — |     — |
         * |   96 | -103 dB | -1.91 dB | 223 ms |
         * |  128 | -108 dB | -0.08 dB | 279 ms |
         * |  256 | -115 dB | -0.00 dB | 509 ms |
         *
         * 128 is the knee. It is flat to 20 kHz and rejects aliases by more than
         * 100 dB — already past the -96 dB floor of the 16-bit buffers this
         * writes into — for roughly half the cost of 256. 64 taps is not a
         * cheaper version of this filter; it is a broken one.
         */
        const val DEFAULT_TAPS = 128

        /** Well below 16-bit storage noise, so the filter is not the limiting factor. */
        const val DEFAULT_STOPBAND_DB = 100.0

        /**
         * Phase-table ceiling. Every ordinary rate pair reduces to far fewer
         * phases than this (44.1↔48 needs 160), so the exact path is the
         * normal one and interpolation is the rare fallback.
         */
        const val MAX_PHASES = 4096

        /** Normalisation applied when reading 16-bit storage. */
        const val SHORT_SCALE = 1f / 32768f
    }
}
