package com.hereliesaz.sirmatchalot.audio

import kotlin.math.abs

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
     * First and last frame whose local peak exceeds [threshold], scanning the
     * stored samples directly.
     *
     * The equivalent used to be `PeakEnvelope.contentBounds(toMonoFloat())`,
     * which materialises a whole-track `FloatArray` — four bytes per frame, so
     * *twice* the size of the 16-bit storage it is summarising — purely to find
     * two indices. On a five-minute stereo track that is an extra 53 MB held
     * live at the exact moment the decoder is already holding its accumulation
     * buffers, and it was one of several contributors to running out of heap.
     *
     * @return the inclusive range, or null when the whole buffer is silent.
     */
    fun contentBounds(threshold: Float = 0.02f, windowSize: Int = 1024): IntRange? {
        if (frameCount == 0) return null
        val scale = SHORT_SCALE / channelCount
        var first = -1
        var last = -1
        var i = 0
        while (i < frameCount) {
            val end = minOf(i + windowSize, frameCount)
            var peak = 0f
            for (frame in i until end) {
                var sum = 0f
                for (channel in channels) sum += channel[frame] * scale
                val magnitude = if (sum < 0f) -sum else sum
                if (magnitude > peak) peak = magnitude
            }
            if (peak > threshold) {
                if (first < 0) first = i
                last = end - 1
            }
            i = end
        }
        return if (first < 0) null else first..last
    }

    /**
     * A view of this buffer covering only `[startFrame, endFrame)`.
     *
     * Used to apply silence trimming without a second decode. Copies, because
     * the render thread must never see a range that can change under it.
     */
    fun slice(startFrame: Int, endFrame: Int): PcmBuffer {
        val from = startFrame.coerceIn(0, frameCount)
        val to = endFrame.coerceIn(from, frameCount)
        // A slice covering the whole buffer is this buffer. Copying it anyway
        // doubles a whole track's memory for no change — which matters because
        // the common case, a track with no silence to trim, hits exactly that.
        if (from == 0 && to == frameCount) return this
        return PcmBuffer(
            channels = Array(channelCount) { channels[it].copyOfRange(from, to) },
            sampleRate = sampleRate,
        )
    }

    /**
     * This buffer converted to [targetRate] with a windowed-sinc polyphase
     * filter, or this buffer unchanged when the rate already matches.
     *
     * Doing this once at load time is what lets the render loop run at rate 1.0,
     * where the interpolating read is an exact pass-through. Leaving it undone
     * means every sample of every track goes through a 4-point spline
     * indefinitely: measured at -26 dB THD+N for a 10 kHz tone, and -5 dB alias
     * rejection at 23 kHz. See [com.hereliesaz.sirmatchalot.dsp.SincResampler].
     *
     * Conversion is per channel, so it costs twice as much for stereo; it runs
     * off the UI thread at load, alongside the decode and analysis passes that
     * are more expensive still.
     */
    fun resampledTo(
        targetRate: Int,
        resampler: com.hereliesaz.sirmatchalot.dsp.SincResampler =
            com.hereliesaz.sirmatchalot.dsp.SincResampler(),
    ): PcmBuffer {
        require(targetRate > 0) { "targetRate must be positive, was $targetRate" }
        if (targetRate == sampleRate) return this

        // A mono buffer stores one array and hands it out for both channels.
        // Converting `channels` rather than `channel(i)` preserves that, so a
        // mono clip stays centred instead of silently becoming hard-left stereo.
        //
        // One channel at a time, and each intermediate dropped before the next
        // begins: converting both channels in a single `Array(...)` expression
        // keeps every intermediate of every channel live at once, which on a
        // long track is several times the size of the track itself.
        val converted = arrayOfNulls<ShortArray>(channelCount)
        for (c in 0 until channelCount) {
            val out = resampler.resample(channels[c], sampleRate, targetRate)
            converted[c] = ShortArray(out.size) { i ->
                (out[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        @Suppress("UNCHECKED_CAST")
        return PcmBuffer(converted as Array<ShortArray>, targetRate)
    }

    /**
     * This buffer pitch-shifted by [semitones], keeping its length — and so its
     * tempo — unchanged.
     *
     * Rendered offline rather than in the render loop. Harmonic mixing sets a
     * shift once, when a track is matched to what is already playing, so paying
     * for it once per match buys a far better algorithm than a per-sample budget
     * would allow. It also means the shift cannot be a source of dropouts.
     *
     * Always derive this from the pristine decoded buffer rather than from an
     * already-shifted one: shifting twice compounds both the interval and the
     * artefacts.
     *
     * @return this buffer when [semitones] is zero.
     */
    fun pitchShifted(
        semitones: Double,
        shifter: com.hereliesaz.sirmatchalot.dsp.PitchShifter =
            com.hereliesaz.sirmatchalot.dsp.PitchShifter(),
    ): PcmBuffer {
        if (abs(semitones) < 1e-6) return this

        // As in resampledTo: convert the stored channels, not the accessor, so a
        // mono buffer stays mono and therefore stays centred.
        // One channel at a time, so a channel's float intermediates are
        // collectable before the next channel allocates its own.
        val shifted = arrayOfNulls<ShortArray>(channelCount)
        for (c in 0 until channelCount) {
            val source = FloatArray(frameCount) { i -> channels[c][i] * SHORT_SCALE }
            val out = shifter.shift(source, semitones)
            shifted[c] = ShortArray(out.size) { i ->
                (out[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        // The shifter's output length can differ by a frame or two from rounding;
        // trim to the shortest so the channels stay the same length.
        val length = shifted.minOf { it!!.size }
        return PcmBuffer(
            channels = Array(channelCount) { c ->
                val channel = shifted[c]!!
                if (channel.size == length) channel else channel.copyOf(length)
            },
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
