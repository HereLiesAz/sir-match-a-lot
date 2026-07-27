package com.hereliesaz.sirmatchalot.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A fully decoded track, plus where its actual content starts and ends.
 *
 * @param pcm decoded audio, already trimmed to [contentRange] if trimming applied.
 * @param originalFrameCount frames before trimming, for reporting.
 * @param trimmedStartFrames how many leading frames of silence were removed.
 */
data class DecodedAudio(
    val pcm: PcmBuffer,
    val originalFrameCount: Int,
    val trimmedStartFrames: Int,
)

/**
 * Decodes a container to PCM with [MediaExtractor] and [MediaCodec].
 *
 * This is the evolution of the previous `AudioWaveformExtractor`, whose decode
 * loop was structurally right and is kept. Four things it got wrong are fixed:
 *
 * - It assumed the decoder emitted 16-bit PCM without checking
 *   `KEY_PCM_ENCODING`, so a device or codec producing float output was
 *   misinterpreted as garbage.
 * - It treated interleaved stereo samples as consecutive mono samples, so peak
 *   windows covered half the intended duration and the two channels were
 *   interleaved into one envelope.
 * - It could not be cancelled, so importing a long playlist could not be stopped.
 * - It discarded the PCM, keeping only peaks — forcing a second decode for
 *   playback and a third for analysis. Now one decode serves all three.
 */
/**
 * [MediaFormat.getInteger] with a default, on every version this app supports.
 *
 * The two-argument `getInteger` was added in API 29, and this app's `minSdk` is
 * 24 — so calling it directly is a `NoSuchMethodError` on Android 7 through 9,
 * thrown the first time anything is decoded. Since decoding is how audio gets
 * into the app at all, that is the whole app, not one feature.
 */
private fun MediaFormat.intOr(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

/**
 * Why a decode did not produce audio.
 *
 * Every failure used to arrive at the UI as the same sentence — "Could not
 * decode X" — for at least five unrelated causes, of which only one is actually
 * about decoding. A file the app has lost permission to open, a file that has
 * been moved or deleted, a video with no audio in it, a codec the device does
 * not have, and a genuinely corrupt stream all read identically, and each has a
 * different remedy. Distinguishing them is the difference between "re-import
 * the folder" and "this file is broken".
 */
sealed interface DecodeOutcome {

    data class Success(val audio: DecodedAudio) : DecodeOutcome

    data class Failure(val reason: Reason, val detail: String? = null) : DecodeOutcome

    enum class Reason {
        /**
         * The app is not allowed to read the file.
         *
         * Overwhelmingly the common one, and the least like a decoding problem.
         * A document picked with `OpenDocument` is readable only until the
         * process dies unless the grant was explicitly persisted, so the library
         * row outlives the permission: the track sits there looking fine and
         * fails the moment it is loaded, from the next app start onward.
         */
        NO_PERMISSION,

        /** Gone, renamed, on unmounted storage, or otherwise unopenable. */
        UNREADABLE,

        /** Opened, but there is no audio track in it. */
        NO_AUDIO_TRACK,

        /** There is audio, but this device has no decoder for that format. */
        NO_CODEC,

        /** Decoded without error and produced no frames. */
        EMPTY,
    }
}

object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * What a container says about its audio, without decoding any of it.
     *
     * @param durationSeconds as declared by the container; approximate, and zero
     *   when it declares nothing.
     */
    data class AudioProbe(
        val durationSeconds: Double,
        val sampleRate: Int,
        val channelCount: Int,
    ) {
        /**
         * Heap the decoded audio will occupy at [atRate].
         *
         * Assumes 16-bit storage, which is what everything but a hi-res source
         * decodes to. A float source costs twice this — so a caller using it as
         * an admission test is being optimistic, and should leave headroom.
         */
        fun decodedBytes(atRate: Int, bytesPerSample: Int = 2): Long =
            (durationSeconds * atRate).toLong() *
                channelCount.coerceAtLeast(1) * bytesPerSample
    }

    /**
     * Reads [uri]'s audio format without decoding it.
     *
     * The point is to be able to refuse a track *before* committing to holding
     * it. Asking the allocator instead means finding out by `OutOfMemoryError`,
     * thrown at the worst possible moment — while the decoder is holding a whole
     * track's accumulation buffers — and an app that dies is a worse answer than
     * one that says the track is too long to load at this sample rate.
     *
     * @return null when the source has no readable audio track.
     */
    suspend fun probe(context: Context, uri: Uri): AudioProbe? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull() ?: 0L
                return@withContext AudioProbe(
                    durationSeconds = durationUs / 1_000_000.0,
                    sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100),
                    channelCount = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2),
                )
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Silence threshold for trimming, as a fraction of full scale. */
    const val SILENCE_THRESHOLD = 0.02f

    /**
     * Decodes [uri] to PCM.
     *
     * @param trimSilence remove leading and trailing silence, as requested for
     *   every imported song.
     * @return the decoded audio, or null if the source has no decodable audio track.
     * @throws CancellationException if the calling coroutine is cancelled.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        trimSilence: Boolean = true,
    ): DecodedAudio? =
        (decodeDetailed(context, uri, trimSilence) as? DecodeOutcome.Success)?.audio

    /**
     * Decodes [uri], saying what went wrong when it does.
     *
     * @see DecodeOutcome.Reason for why the distinction is worth carrying.
     */
    suspend fun decodeDetailed(
        context: Context,
        uri: Uri,
        trimSilence: Boolean = true,
    ): DecodeOutcome = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: SecurityException) {
            // The library row outlived the permission to read the file. This is
            // the one that used to be reported as a decoding failure, and it is
            // the one that is nothing of the kind.
            extractor.release()
            return@withContext DecodeOutcome.Failure(
                DecodeOutcome.Reason.NO_PERMISSION,
                e.message,
            )
        } catch (e: Exception) {
            extractor.release()
            return@withContext DecodeOutcome.Failure(
                DecodeOutcome.Reason.UNREADABLE,
                e.message,
            )
        }

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                inputFormat = format
                break
            }
        }
        if (trackIndex < 0 || inputFormat == null) {
            extractor.release()
            return@withContext DecodeOutcome.Failure(DecodeOutcome.Reason.NO_AUDIO_TRACK)
        }

        extractor.selectTrack(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrElse { error ->
            extractor.release()
            return@withContext DecodeOutcome.Failure(DecodeOutcome.Reason.NO_CODEC, mime)
        }

        // Channel count and sample rate can change when the decoder reports its
        // real output format, so they are read from the output format below
        // rather than trusted from the container.
        var channelCount = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
        var sampleRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
        var pcmEncoding = AudioFormatEncoding.PCM_16BIT

        // Growable planar accumulation. Sized from the container duration when
        // available so the common case does no reallocation.
        // Container durations are approximate and decoders commonly emit a little
        // more than they imply (encoder priming and padding). Allocating exactly
        // the estimate therefore guarantees one reallocation, at the worst
        // possible moment — when the buffer is already whole-track sized. A few
        // per cent of slack makes the common case allocate once and never grow.
        val estimatedFrames = runCatching {
            (inputFormat.getLong(MediaFormat.KEY_DURATION) * sampleRate / 1_000_000L).toInt()
        }.getOrNull()?.coerceIn(0, 60 * 60 * 192_000) ?: 0
        val initialFrames = if (estimatedFrames > 0) {
            (estimatedFrames + estimatedFrames / 32 + sampleRate).coerceAtMost(60 * 60 * 192_000)
        } else {
            sampleRate
        }

        // Two accumulators, exactly one live. Which one is decided by the
        // codec's reported encoding at INFO_OUTPUT_FORMAT_CHANGED, which always
        // arrives before any audio does — so nothing is ever accumulated at the
        // wrong depth and then converted.
        var planar: Array<ShortArray>? = Array(channelCount) { ShortArray(initialFrames) }
        var planarFloat: Array<FloatArray>? = null
        var frames = 0

        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!currentCoroutineContext().isActive) throw CancellationException()

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        channelCount = output.intOr(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        sampleRate = output.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        pcmEncoding = AudioFormatEncoding.of(output)

                        // A source the codec reports as float carries more than
                        // 16 bits. Narrowing it here would discard a hi-res
                        // master's extra depth before playback had begun, which
                        // is the whole thing B14 exists to stop.
                        if (pcmEncoding == AudioFormatEncoding.PCM_FLOAT && planarFloat == null) {
                            val capacity = planar?.get(0)?.size ?: initialFrames
                            planarFloat = Array(channelCount) { FloatArray(capacity) }
                            // Released immediately: holding both accumulators
                            // for a whole track is exactly the shape that ran
                            // the heap out before.
                            planar = null
                        }

                        val existing = planar
                        if (existing != null && existing.size != channelCount) {
                            planar = Array(channelCount) { channel ->
                                existing.getOrNull(channel) ?: ShortArray(existing[0].size)
                            }
                        }
                        val existingFloat = planarFloat
                        if (existingFloat != null && existingFloat.size != channelCount) {
                            planarFloat = Array(channelCount) { channel ->
                                existingFloat.getOrNull(channel) ?: FloatArray(existingFloat[0].size)
                            }
                        }
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val produced = framesIn(info.size, channelCount, pcmEncoding)
                            val floatTarget = planarFloat
                            if (floatTarget != null) {
                                planarFloat = ensureFloatCapacity(floatTarget, frames + produced)
                                appendFloatFrames(
                                    buffer, planarFloat!!, frames, produced, channelCount,
                                )
                            } else {
                                planar = ensureCapacity(planar!!, frames + produced)
                                appendFrames(
                                    buffer, planar!!, frames, produced, channelCount, pcmEncoding,
                                )
                            }
                            frames += produced
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        if (frames == 0) return@withContext DecodeOutcome.Failure(DecodeOutcome.Reason.EMPTY)

        // copyOf is what trims the over-allocated tail; when the estimate
        // landed exactly there is nothing to trim and the copy would be a whole
        // extra track in memory for no change.
        val floatResult = planarFloat
        val exact = if (floatResult != null) {
            PcmBuffer.float(
                channels = Array(channelCount) {
                    if (floatResult[it].size == frames) floatResult[it]
                    else floatResult[it].copyOf(frames)
                },
                sampleRate = sampleRate,
            )
        } else {
            val shortResult = planar!!
            PcmBuffer(
                channels = Array(channelCount) {
                    if (shortResult[it].size == frames) shortResult[it]
                    else shortResult[it].copyOf(frames)
                },
                sampleRate = sampleRate,
            )
        }

        if (!trimSilence) {
            return@withContext DecodeOutcome.Success(DecodedAudio(exact, frames, 0))
        }

        // Bounds are taken from the summed channels, so a channel that is silent
        // alone does not make the whole track look silent — but scanned in place
        // rather than through toMonoFloat(), which would materialise a
        // whole-track FloatArray purely to find two indices.
        val bounds = exact.contentBounds(SILENCE_THRESHOLD)
            ?: return@withContext DecodeOutcome.Success(DecodedAudio(exact, frames, 0))

        DecodeOutcome.Success(
            DecodedAudio(
                pcm = exact.slice(bounds.first, bounds.last + 1),
                originalFrameCount = frames,
                trimmedStartFrames = bounds.first,
            ),
        )
    }

    /** PCM encodings a decoder may emit. */
    enum class AudioFormatEncoding {
        PCM_8BIT,
        PCM_16BIT,
        PCM_FLOAT,
        ;

        companion object {
            fun of(format: MediaFormat): AudioFormatEncoding {
                // KEY_PCM_ENCODING is absent on many devices, where 16-bit is implied.
                val encoding = runCatching {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }.getOrNull() ?: android.media.AudioFormat.ENCODING_PCM_16BIT
                return when (encoding) {
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> PCM_8BIT
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> PCM_FLOAT
                    else -> PCM_16BIT
                }
            }
        }
    }

    @VisibleForTesting
    fun framesIn(byteCount: Int, channelCount: Int, encoding: AudioFormatEncoding): Int {
        val bytesPerSample = when (encoding) {
            AudioFormatEncoding.PCM_8BIT -> 1
            AudioFormatEncoding.PCM_16BIT -> 2
            AudioFormatEncoding.PCM_FLOAT -> 4
        }
        return byteCount / (bytesPerSample * channelCount)
    }

    /**
     * Grows [planar] in place to hold at least [required] frames.
     *
     * Two things here are deliberate, and the previous version got both wrong in
     * the same line — `Array(planar.size) { planar[it].copyOf(capacity) }`:
     *
     * **Per channel, not all at once.** That expression builds every new channel
     * before assigning, so for stereo the two old arrays and the two new
     * (double-sized) ones are all live at the same instant: six times the current
     * PCM. Replacing one channel at a time drops each old array immediately, so
     * the peak is the old total plus one new channel.
     *
     * **Grow by half, not by double.** The buffer is pre-sized from the
     * container's duration, so the only growth that normally happens is one small
     * overshoot at the very end — decoders routinely emit a little more than the
     * duration implies. Doubling there asks for a second whole track's worth of
     * memory at the precise moment the heap is fullest, which is what threw
     * OutOfMemoryError on a 256 MB device.
     */
    /** [ensureCapacity] for the float accumulator, with the same two rules. */
    private fun ensureFloatCapacity(planar: Array<FloatArray>, required: Int): Array<FloatArray> {
        if (planar[0].size >= required) return planar
        var capacity = planar[0].size.coerceAtLeast(1024)
        while (capacity < required) capacity += capacity / 2 + 1
        for (channel in planar.indices) {
            planar[channel] = planar[channel].copyOf(capacity)
        }
        return planar
    }

    /**
     * De-interleaves float output into [planar] at [offset].
     *
     * Only ever called for `ENCODING_PCM_FLOAT`, so there is no encoding branch:
     * the codec emits normalised floats and they are stored as they arrive. No
     * scaling, no clamping — a hi-res master can legitimately exceed full scale
     * between samples, and the limiter at the end of the chain is where that
     * belongs. Clamping here would bake the clipping in permanently.
     */
    @VisibleForTesting
    fun appendFloatFrames(
        buffer: ByteBuffer,
        planar: Array<FloatArray>,
        offset: Int,
        frameCount: Int,
        channelCount: Int,
    ) {
        buffer.order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        for (frame in 0 until frameCount) {
            for (channel in 0 until channelCount) {
                val value = floats.get()
                if (channel < planar.size) planar[channel][offset + frame] = value
            }
        }
    }

    private fun ensureCapacity(planar: Array<ShortArray>, required: Int): Array<ShortArray> {
        if (planar[0].size >= required) return planar
        var capacity = planar[0].size.coerceAtLeast(1024)
        while (capacity < required) capacity += capacity / 2 + 1
        for (channel in planar.indices) {
            planar[channel] = planar[channel].copyOf(capacity)
        }
        return planar
    }

    /**
     * De-interleaves [frameCount] frames from [buffer] into [planar] at [offset].
     *
     * De-interleaving here is what keeps a fractional playhead from ever landing
     * between two channels of the same frame, and what lets analysis read a
     * single channel without stride arithmetic.
     */
    @VisibleForTesting
    fun appendFrames(
        buffer: ByteBuffer,
        planar: Array<ShortArray>,
        offset: Int,
        frameCount: Int,
        channelCount: Int,
        encoding: AudioFormatEncoding,
    ) {
        buffer.order(ByteOrder.nativeOrder())
        when (encoding) {
            AudioFormatEncoding.PCM_16BIT -> {
                val shorts = buffer.asShortBuffer()
                for (frame in 0 until frameCount) {
                    for (channel in 0 until channelCount) {
                        val value = shorts.get()
                        if (channel < planar.size) planar[channel][offset + frame] = value
                    }
                }
            }

            AudioFormatEncoding.PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                for (frame in 0 until frameCount) {
                    for (channel in 0 until channelCount) {
                        val value = floats.get().coerceIn(-1f, 1f)
                        if (channel < planar.size) {
                            planar[channel][offset + frame] =
                                (value * Short.MAX_VALUE).toInt().toShort()
                        }
                    }
                }
            }

            AudioFormatEncoding.PCM_8BIT -> {
                for (frame in 0 until frameCount) {
                    for (channel in 0 until channelCount) {
                        // 8-bit PCM is unsigned with 128 as zero.
                        val value = ((buffer.get().toInt() and 0xFF) - 128) shl 8
                        if (channel < planar.size) {
                            planar[channel][offset + frame] = value.toShort()
                        }
                    }
                }
            }
        }
    }
}
