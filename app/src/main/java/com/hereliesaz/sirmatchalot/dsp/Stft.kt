package com.hereliesaz.sirmatchalot.dsp

/**
 * Short-time Fourier transform over a mono buffer.
 *
 * Frames are streamed to a callback rather than materialised into a
 * spectrogram, because a full-length track at a 512-sample hop would otherwise
 * cost tens of megabytes that every consumer here reads exactly once.
 *
 * @param frameSize window length; must be a power of two.
 * @param hop advance between frames in samples.
 */
class Stft(
    val frameSize: Int = 2048,
    val hop: Int = 512,
) {
    init {
        require(hop in 1..frameSize) { "hop must be in 1..frameSize" }
    }

    private val fft = Fft(frameSize)
    private val window = Window.hann(frameSize)
    private val re = FloatArray(frameSize)
    private val im = FloatArray(frameSize)
    private val magnitudes = FloatArray(frameSize / 2 + 1)

    /** Number of non-redundant magnitude bins per frame. */
    val bins: Int get() = frameSize / 2 + 1

    /** Frames per second of the resulting envelope at [sampleRate]. */
    fun frameRate(sampleRate: Int): Double = sampleRate.toDouble() / hop

    /** How many whole frames [length] samples yields. */
    fun frameCount(length: Int): Int =
        if (length < frameSize) 0 else (length - frameSize) / hop + 1

    /** Centre frequency of bin [index] at [sampleRate]. */
    fun binFrequency(index: Int, sampleRate: Int): Double =
        index.toDouble() * sampleRate / frameSize

    /**
     * Invokes [action] once per frame with the magnitude spectrum.
     *
     * The array passed to [action] is **reused between frames** — copy it if you
     * need to retain it.
     */
    inline fun forEachFrame(samples: FloatArray, action: (frameIndex: Int, magnitudes: FloatArray) -> Unit) {
        val total = frameCount(samples.size)
        for (frame in 0 until total) {
            action(frame, magnitudesOf(samples, frame * hop))
        }
    }

    /**
     * Computes the windowed magnitude spectrum of the frame beginning at
     * [offset]. The returned array is reused across calls.
     */
    fun magnitudesOf(samples: FloatArray, offset: Int): FloatArray {
        for (i in 0 until frameSize) {
            val s = offset + i
            re[i] = if (s < samples.size) samples[s] * window[i] else 0f
            im[i] = 0f
        }
        fft.forward(re, im)
        fft.magnitudes(re, im, magnitudes)
        return magnitudes
    }
}
