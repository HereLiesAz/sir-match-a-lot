package com.hereliesaz.sirmatchalot.audio

/**
 * Fully decoded audio, held in memory as planar 16-bit PCM.
 *
 * Two storage decisions matter here.
 *
 * **16-bit, not float.** A five-minute stereo track is 26 MB as `Short` and
 * 53 MB as `Float`. With two decks holding several clips each, float storage
 * runs into hundreds of megabytes. Samples are widened to float on read, which
 * costs one multiply and loses nothing audible — the source was 16-bit anyway.
 *
 * **Planar, not interleaved.** Each channel is a separate array, so
 * [com.hereliesaz.sirmatchalot.dsp.Resampler] can read a channel directly
 * without stride arithmetic, and a fractional playhead never lands between two
 * channels of the same frame.
 *
 * Instances are immutable and safe to share with the render thread.
 *
 * @param channels one array per channel; all must be the same length.
 * @param sampleRate sample rate of the decoded audio.
 */
class PcmBuffer(
    val channels: Array<ShortArray>,
    val sampleRate: Int,
) {
    init {
        require(channels.isNotEmpty()) { "need at least one channel" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        val length = channels[0].size
        require(channels.all { it.size == length }) { "all channels must be the same length" }
    }

    /** Frames per channel. */
    val frameCount: Int get() = channels[0].size

    val channelCount: Int get() = channels.size

    val durationSeconds: Double get() = frameCount.toDouble() / sampleRate

    /**
     * Channel [index], or the last available channel when a mono buffer is asked
     * for its right channel — so a mono clip plays centred rather than only on
     * the left.
     */
    fun channel(index: Int): ShortArray = channels[index.coerceIn(0, channelCount - 1)]

    /**
     * A view of this buffer covering only `[startFrame, endFrame)`.
     *
     * Used to apply silence trimming without a second decode. Copies, because
     * the render thread must never see a range that can change under it.
     */
    fun slice(startFrame: Int, endFrame: Int): PcmBuffer {
        val from = startFrame.coerceIn(0, frameCount)
        val to = endFrame.coerceIn(from, frameCount)
        return PcmBuffer(
            channels = Array(channelCount) { channels[it].copyOfRange(from, to) },
            sampleRate = sampleRate,
        )
    }

    /**
     * Sums all channels to mono as normalised floats.
     *
     * Analysis works on mono — tempo, key, and onset detection gain nothing from
     * stereo and cost twice as much — so this is the bridge from playback
     * storage to the `dsp` package.
     */
    fun toMonoFloat(): FloatArray {
        val out = FloatArray(frameCount)
        val scale = 1f / (Short.MAX_VALUE.toFloat() * channelCount)
        for (channel in channels) {
            for (i in 0 until frameCount) {
                out[i] += channel[i] * scale
            }
        }
        return out
    }

    companion object {
        const val SHORT_SCALE = 1f / 32768f

        /** Builds a buffer from normalised float channels, for tests and synthesis. */
        fun fromFloat(channels: Array<FloatArray>, sampleRate: Int): PcmBuffer =
            PcmBuffer(
                channels = Array(channels.size) { c ->
                    ShortArray(channels[c].size) { i ->
                        (channels[c][i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    }
                },
                sampleRate = sampleRate,
            )

        fun monoFromFloat(samples: FloatArray, sampleRate: Int): PcmBuffer =
            fromFloat(arrayOf(samples), sampleRate)

        /** Silence of [frameCount] frames. */
        fun silence(frameCount: Int, channelCount: Int = 2, sampleRate: Int = 44_100): PcmBuffer =
            PcmBuffer(Array(channelCount) { ShortArray(frameCount) }, sampleRate)
    }
}
