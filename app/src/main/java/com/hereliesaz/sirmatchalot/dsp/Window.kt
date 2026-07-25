package com.hereliesaz.sirmatchalot.dsp

import kotlin.math.cos

/** Precomputed analysis windows. */
object Window {

    /**
     * Periodic Hann window of [length] samples — the correct variant for
     * spectral analysis and for overlap-add resynthesis (a symmetric window
     * would not sum to a constant at 50% overlap).
     */
    fun hann(length: Int): FloatArray {
        require(length > 0) { "window length must be positive" }
        return FloatArray(length) { n ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * n / length)).toFloat()
        }
    }

    /** Periodic Hamming window of [length] samples. */
    fun hamming(length: Int): FloatArray {
        require(length > 0) { "window length must be positive" }
        return FloatArray(length) { n ->
            (0.54 - 0.46 * cos(2.0 * Math.PI * n / length)).toFloat()
        }
    }

    /** Multiplies [samples] by [window] in place. */
    fun applyInPlace(samples: FloatArray, window: FloatArray) {
        val n = minOf(samples.size, window.size)
        for (i in 0 until n) samples[i] *= window[i]
    }
}
