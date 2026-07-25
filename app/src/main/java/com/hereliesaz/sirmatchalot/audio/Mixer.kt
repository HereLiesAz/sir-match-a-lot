package com.hereliesaz.sirmatchalot.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Crossfader gain laws. */
object Crossfade {

    /**
     * Equal-power crossfade gains for [position], where 0 is full Deck A and 1
     * is full Deck B.
     *
     * The sum of squares is 1 at every position, so perceived loudness holds
     * steady across the sweep. The previous implementation gave **full** gain to
     * both decks at centre, making the middle of every transition about 6 dB
     * louder than either end — audible as a lurch on any crossfade.
     *
     * @return gain for A and gain for B.
     */
    fun equalPower(position: Float): Pair<Float, Float> {
        val x = position.coerceIn(0f, 1f).toDouble()
        val angle = x * Math.PI / 2
        return cos(angle).toFloat() to sin(angle).toFloat()
    }
}

/**
 * Safety limiter on the master bus.
 *
 * **Threshold placement is a sound-quality decision, not a detail.** Any
 * always-on nonlinearity colours everything passing through it, so this curve is
 * exactly linear — bit-transparent — up to [THRESHOLD] at -1 dBFS, and only
 * compresses above that. Material mixed with sane gain staging never touches the
 * curve at all; it exists solely to stop a hot two-deck sum from wrapping into
 * hard clipping, which produces far worse artefacts than gentle compression.
 *
 * An earlier revision of this file put the threshold at 0.5 (-6 dBFS), which
 * applied audible compression to ordinary listening levels. That is fine for a
 * toy and wrong for anyone listening critically.
 */
object Limiter {

    /** -1 dBFS. Below this magnitude the transfer curve is exactly linear. */
    const val THRESHOLD = 0.891f

    /**
     * Applies the curve to [count] values of [buffer] in place.
     *
     * Bounded output: |y| < 1 for every finite input.
     */
    fun processInPlace(buffer: FloatArray, count: Int = buffer.size) {
        for (i in 0 until count) {
            buffer[i] = softClip(buffer[i])
        }
    }

    /**
     * Linear below the threshold, asymptotic to full scale above it. Continuous,
     * monotonic, and odd-symmetric, so it adds neither DC nor even-order
     * distortion.
     */
    fun softClip(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= THRESHOLD) return x
        val sign = if (x < 0) -1f else 1f
        val headroom = 1f - THRESHOLD
        val over = magnitude - THRESHOLD
        return sign * (THRESHOLD + headroom * (over / (over + headroom)))
    }
}

/**
 * A snapshot of output level for the UI.
 *
 * Published by whole-object replacement so the UI never reads a half-updated
 * pair, and read without locking so the render thread is never blocked by a
 * frame draw.
 *
 * @param peak largest absolute sample in the block, 0..1.
 * @param rms root-mean-square level of the block, 0..1.
 */
data class OutputLevel(val peak: Float, val rms: Float) {
    companion object {
        val SILENT = OutputLevel(0f, 0f)
    }
}

/**
 * Sums both decks through the crossfader into the master bus.
 *
 * This is the single output path the whole app shares — as opposed to the
 * previous design, which created one `ExoPlayer` per clip and had no mixing
 * stage at all, so crossfading, EQ, and level metering had nowhere to happen.
 *
 * Allocates nothing during rendering.
 */
class Mixer(
    val deckA: Deck,
    val deckB: Deck,
    val sampleRate: Int = 44_100,
    maxFrames: Int = 2048,
) {
    /** 0 = full Deck A, 1 = full Deck B. */
    @Volatile
    var crossfade: Float = 0.5f

    @Volatile
    var masterGain: Float = 0.8f

    /** Most recent output level, for the waveform glow and background visualiser. */
    @Volatile
    var level: OutputLevel = OutputLevel.SILENT
        private set

    /**
     * The XY pad's filter, on the master bus.
     *
     * Applied to the summed mix *before* the limiter, so a resonant sweep is
     * caught by the limiter like any other loud signal rather than clipping past
     * it.
     */
    val filter = MasterFilter(sampleRate)

    private val bufferA = FloatArray(maxFrames * Deck.CHANNELS)
    private val bufferB = FloatArray(maxFrames * Deck.CHANNELS)
    private val busBuffer = FloatArray(maxFrames * Deck.CHANNELS)

    /** Smoothed gains, so a fast crossfade sweep does not step and zipper. */
    private var smoothedGainA = 0f
    private var smoothedGainB = 0f
    private var smoothedMaster = 0f
    private var initialised = false

    /**
     * Renders [frames] frames of interleaved stereo into [out].
     *
     * @throws IllegalArgumentException if [frames] exceeds the configured maximum.
     */
    fun render(out: FloatArray, frames: Int) {
        val samples = frames * Deck.CHANNELS
        require(out.size >= samples) { "output buffer too small" }
        require(samples <= bufferA.size) { "frames ($frames) exceeds mixer capacity" }

        deckA.render(bufferA, frames)
        deckB.render(bufferB, frames)

        val (targetA, targetB) = Crossfade.equalPower(crossfade)
        val targetMaster = masterGain.coerceIn(0f, 1f)

        if (!initialised) {
            smoothedGainA = targetA
            smoothedGainB = targetB
            smoothedMaster = targetMaster
            initialised = true
        }

        // Sum and gain into the bus, then filter, then limit. The order matters:
        // the limiter has to be last so it sees the resonant peaks the filter
        // creates, and the filter has to come after the crossfade so it acts on
        // the mix a listener hears rather than on one deck.
        for (frame in 0 until frames) {
            // One-pole smoothing per frame; ~5 ms to settle at 44.1 kHz.
            smoothedGainA += (targetA - smoothedGainA) * SMOOTHING
            smoothedGainB += (targetB - smoothedGainB) * SMOOTHING
            smoothedMaster += (targetMaster - smoothedMaster) * SMOOTHING

            val base = frame * Deck.CHANNELS
            for (channel in 0 until Deck.CHANNELS) {
                val index = base + channel
                busBuffer[index] =
                    (bufferA[index] * smoothedGainA + bufferB[index] * smoothedGainB) * smoothedMaster
            }
        }

        filter.process(busBuffer, frames)

        var peak = 0f
        var sumOfSquares = 0.0

        for (i in 0 until samples) {
            val limited = Limiter.softClip(busBuffer[i])
            out[i] = limited

            val magnitude = abs(limited)
            if (magnitude > peak) peak = magnitude
            sumOfSquares += limited.toDouble() * limited
        }

        level = OutputLevel(
            peak = peak,
            rms = sqrt(sumOfSquares / samples).toFloat(),
        )
    }

    fun reset() {
        deckA.reset()
        deckB.reset()
        initialised = false
        level = OutputLevel.SILENT
    }

    private companion object {
        const val SMOOTHING = 0.005f
    }
}
