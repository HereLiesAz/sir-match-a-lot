package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * In-place radix-2 Cooley-Tukey FFT.
 *
 * Twiddle factors and the bit-reversal permutation are precomputed once, and
 * [forward] allocates nothing, so an instance can be reused from the audio
 * thread or from a tight analysis loop.
 *
 * @param size transform length; must be a power of two.
 */
class Fft(val size: Int) {

    init {
        require(size >= 2) { "FFT size must be at least 2, was $size" }
        require(size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
    }

    /** Real part of exp(-2*PI*i*k/size) for k in 0 until size/2. */
    private val twiddleRe = FloatArray(size / 2)
    private val twiddleIm = FloatArray(size / 2)
    private val bitReversed = IntArray(size)

    init {
        for (k in 0 until size / 2) {
            val angle = -2.0 * Math.PI * k / size
            twiddleRe[k] = cos(angle).toFloat()
            twiddleIm[k] = sin(angle).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            bitReversed[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /**
     * Transforms [re]/[im] in place. Both arrays must be exactly [size] long.
     * For real input, fill [im] with zeros.
     */
    fun forward(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size) {
            "buffers must be $size long, were ${re.size}/${im.size}"
        }

        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= size) {
            val half = len shr 1
            val tableStep = size / len
            var base = 0
            while (base < size) {
                var j = base
                var k = 0
                while (j < base + half) {
                    val partner = j + half
                    val wr = twiddleRe[k]
                    val wi = twiddleIm[k]
                    val pr = re[partner]
                    val pi = im[partner]
                    val tr = pr * wr - pi * wi
                    val ti = pr * wi + pi * wr
                    re[partner] = re[j] - tr
                    im[partner] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    j++
                    k += tableStep
                }
                base += len
            }
            len = len shl 1
        }
    }

    /**
     * Writes magnitudes of the first `size/2 + 1` bins (the non-redundant half
     * of a real transform) into [out], which must be at least that long.
     */
    fun magnitudes(re: FloatArray, im: FloatArray, out: FloatArray) {
        val bins = size / 2 + 1
        require(out.size >= bins) { "out must hold at least $bins bins" }
        for (k in 0 until bins) {
            out[k] = hypot(re[k].toDouble(), im[k].toDouble()).toFloat()
        }
    }

    /** Centre frequency of bin [index] at [sampleRate]. */
    fun binFrequency(index: Int, sampleRate: Int): Float =
        index.toFloat() * sampleRate / size
}
