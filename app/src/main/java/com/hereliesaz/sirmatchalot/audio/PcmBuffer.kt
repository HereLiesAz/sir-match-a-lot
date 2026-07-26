package com.hereliesaz.sirmatchalot.audio

import kotlin.math.abs

/**
 * Fully decoded audio, held in memory as planar PCM at the source's own depth.
 *
 * Three storage decisions matter here.
 *
 * **Planar, not interleaved.** Each channel is a separate array, so
 * [com.hereliesaz.sirmatchalot.dsp.Resampler] can read a channel directly
 * without stride arithmetic, and a fractional playhead never lands between two
 * channels of the same frame.
 *
 * **16-bit for 16-bit sources.** A five-minute stereo track is 26 MB as `Short`
 * and 53 MB as `Float`. With two decks holding several clips each, storing
 * everything as float runs into hundreds of megabytes. For a CD rip or anything
 * lossy-decoded, 16-bit storage is *lossless* — the source carries no more than
 * that — so the extra memory buys nothing at all.
 *
 * **Float for sources that carry more.** A 24-bit FLAC, or any codec whose
 * output `MediaCodec` reports as `ENCODING_PCM_FLOAT`, holds information 16 bits
 * cannot. Narrowing it at decode discards eight bits of a hi-res master before
 * playback has even begun, which is precisely what an audiophile source exists
 * to avoid. So the depth travels with the audio.
 *
 * The two storages are exclusive: exactly one holds the samples, and [isFloat]
 * says which. A buffer never holds both, because that would cost the float
 * memory *and* the quantisation.
 *
 * Instances are immutable and safe to share with the render thread.
 */
class PcmBuffer private constructor(
    private val shorts: Array<ShortArray>?,
    private val floats: Array<FloatArray>?,
    val sampleRate: Int,
) {
    /** A 16-bit buffer. The common case: CD rips and lossy-decoded material. */
    constructor(channels: Array<ShortArray>, sampleRate: Int) :
        this(channels, null, sampleRate)

    init {
        require((shorts == null) != (floats == null)) {
            "a buffer holds exactly one storage"
        }
        require(channelCount > 0) { "need at least one channel" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        val length = frameCount
        val sameLength = floats?.all { it.size == length }
            ?: shorts!!.all { it.size == length }
        require(sameLength) { "all channels must be the same length" }
    }

    /** True when samples are stored as float, because the source carried more than 16 bits. */
    val isFloat: Boolean get() = floats != null

    /** Frames per channel. */
    val frameCount: Int get() = floats?.get(0)?.size ?: shorts!![0].size

    val channelCount: Int get() = floats?.size ?: shorts!!.size

    val durationSeconds: Double get() = frameCount.toDouble() / sampleRate

    /** Bytes of heap the samples occupy — float costs twice what 16-bit does. */
    val byteCount: Long
        get() = frameCount.toLong() * channelCount * if (isFloat) 4 else 2

    /**
     * The 16-bit channel arrays.
     *
     * @throws IllegalStateException on a float buffer. Deliberately loud rather
     *   than converting on the fly: a caller reaching for `channels` on a hi-res
     *   buffer would otherwise silently allocate and quantise a whole track,
     *   which is exactly what this class now exists to prevent.
     */
    val channels: Array<ShortArray>
        get() = shorts ?: error("this buffer is float; use floatChannels or toMonoFloat()")

    /** The float channel arrays. @throws IllegalStateException on a 16-bit buffer. */
    val floatChannels: Array<FloatArray>
        get() = floats ?: error("this buffer is 16-bit; use channels")

    /**
     * 16-bit channel [index], or the last available channel when a mono buffer
     * is asked for its right channel — so a mono clip plays centred rather than
     * only on the left.
     */
    fun channel(index: Int): ShortArray = channels[index.coerceIn(0, channelCount - 1)]

    /** Float channel [index], with the same mono-centring rule. */
    fun floatChannel(index: Int): FloatArray = floatChannels[index.coerceIn(0, channelCount - 1)]

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
        var first = -1
        var last = -1
        var i = 0
        while (i < frameCount) {
            val end = minOf(i + windowSize, frameCount)
            var peak = 0f
            for (frame in i until end) {
                var sum = 0f
                if (floats != null) {
                    val scale = 1f / channelCount
                    for (channel in floats) sum += channel[frame] * scale
                } else {
                    val scale = SHORT_SCALE / channelCount
                    for (channel in shorts!!) sum += channel[frame] * scale
                }
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
        return if (floats != null) {
            float(Array(channelCount) { floats[it].copyOfRange(from, to) }, sampleRate)
        } else {
            PcmBuffer(Array(channelCount) { shorts!![it].copyOfRange(from, to) }, sampleRate)
        }
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
     * A float buffer stays float across the conversion. Rounding a hi-res source
     * to 16 bits here would undo the point of having decoded it at depth, and
     * the resampler works in float either way.
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
        // Converting the stored channels rather than `channel(i)` preserves
        // that, so a mono clip stays centred instead of silently becoming
        // hard-left stereo.
        //
        // One channel at a time, and each intermediate dropped before the next
        // begins: converting both channels in a single `Array(...)` expression
        // keeps every intermediate of every channel live at once, which on a
        // long track is several times the size of the track itself.
        if (floats != null) {
            val converted = arrayOfNulls<FloatArray>(channelCount)
            for (c in 0 until channelCount) {
                converted[c] = resampler.resample(floats[c], sampleRate, targetRate)
            }
            @Suppress("UNCHECKED_CAST")
            return float(converted as Array<FloatArray>, targetRate)
        }

        val converted = arrayOfNulls<ShortArray>(channelCount)
        for (c in 0 until channelCount) {
            val out = resampler.resample(shorts!![c], sampleRate, targetRate)
            converted[c] = ShortArray(out.size) { i -> toShort(out[i]) }
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
     * A float buffer stays float, and skips the widening the 16-bit path needs —
     * so a hi-res track is shifted without ever being quantised.
     *
     * @return this buffer when [semitones] is zero.
     */
    fun pitchShifted(
        semitones: Double,
        shifter: com.hereliesaz.sirmatchalot.dsp.PitchShifter =
            com.hereliesaz.sirmatchalot.dsp.PitchShifter(),
    ): PcmBuffer {
        if (abs(semitones) < 1e-6) return this
        return transformed { source -> shifter.shift(source, semitones) }
    }

    /**
     * This buffer time-stretched by [ratio], keeping its pitch.
     *
     * A ratio above 1 makes the clip longer and therefore slower — its BPM falls
     * — and below 1 makes it shorter and faster. Pitch does not follow, which is
     * the whole difference between this and changing a deck's rate: stretching a
     * clip to fit a tempo must not move its key, or a harmonic match made a
     * moment ago stops being one.
     *
     * Rendered offline, like [pitchShifted], because a pinch sets a length once
     * and the result is then played many times. Always derive it from the
     * pristine decoded buffer: stretching an already-stretched clip compounds
     * both the ratio and the smearing WSOLA leaves on transients.
     *
     * @return this buffer when [ratio] is 1.
     */
    fun timeStretched(
        ratio: Double,
        stretcher: com.hereliesaz.sirmatchalot.dsp.TimeStretcher =
            com.hereliesaz.sirmatchalot.dsp.TimeStretcher(),
    ): PcmBuffer {
        require(ratio > 0.0) { "ratio must be positive, was $ratio" }
        if (abs(ratio - 1.0) < 1e-9) return this
        // [ratio] here is a **length** ratio and `TimeStretcher.stretch` takes a
        // **speed** ratio — its output length is `input.size / ratio`. They are
        // reciprocals, and this method forwarded the value unchanged, so every
        // stretch went the wrong way.
        //
        // Both callers were written against the contract documented above, and
        // both were therefore inverted: conforming a 140 BPM track to a 120 BPM
        // session sped it up to 163 instead of slowing it to 120, and pinching a
        // clip outward made it shorter. Fixed here rather than at the call
        // sites, because a length ratio is the right contract for this class —
        // the callers are about how much of the circle a clip covers — and
        // fixing it once makes both of them correct.
        return transformed { source -> stretcher.stretch(source, 1.0 / ratio) }
    }

    /**
     * Runs [process] over each channel and rebuilds a buffer of the same depth.
     *
     * One channel at a time, so a channel's float intermediates are collectable
     * before the next allocates its own. Channels are trimmed to the shortest
     * result, since a shifter's or stretcher's output length can differ by a
     * frame or two from rounding.
     */
    private fun transformed(process: (FloatArray) -> FloatArray): PcmBuffer {
        val results = arrayOfNulls<FloatArray>(channelCount)
        for (c in 0 until channelCount) {
            // A float buffer's channel is already what the processor wants, so
            // there is nothing to widen and nothing to copy.
            val source = floats?.get(c)
                ?: FloatArray(frameCount) { i -> shorts!![c][i] * SHORT_SCALE }
            results[c] = process(source)
        }
        val length = results.minOf { it!!.size }
        if (length <= 0) return this

        return if (floats != null) {
            float(
                Array(channelCount) { c ->
                    val channel = results[c]!!
                    if (channel.size == length) channel else channel.copyOf(length)
                },
                sampleRate,
            )
        } else {
            PcmBuffer(
                Array(channelCount) { c ->
                    val channel = results[c]!!
                    ShortArray(length) { i -> toShort(channel[i]) }
                },
                sampleRate,
            )
        }
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
        if (floats != null) {
            val scale = 1f / channelCount
            for (channel in floats) {
                for (i in 0 until frameCount) out[i] += channel[i] * scale
            }
            return out
        }
        val scale = 1f / (Short.MAX_VALUE.toFloat() * channelCount)
        for (channel in shorts!!) {
            for (i in 0 until frameCount) out[i] += channel[i] * scale
        }
        return out
    }

    private fun toShort(sample: Float): Short =
        (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()

    companion object {
        const val SHORT_SCALE = 1f / 32768f

        /**
         * A float buffer, for a source carrying more than 16 bits.
         *
         * Samples are expected normalised to [-1, 1], as `MediaCodec` emits them
         * for `ENCODING_PCM_FLOAT`. They are deliberately **not** clamped here: a
         * hi-res master can legitimately exceed full scale between samples, and
         * the limiter at the end of the chain is where that is dealt with.
         * Clamping at storage would bake the clipping in permanently.
         */
        fun float(channels: Array<FloatArray>, sampleRate: Int): PcmBuffer =
            PcmBuffer(null, channels, sampleRate)

        /** Builds a 16-bit buffer from normalised float channels, for tests and synthesis. */
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
