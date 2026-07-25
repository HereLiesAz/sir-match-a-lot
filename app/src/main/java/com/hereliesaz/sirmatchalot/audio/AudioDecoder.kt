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
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

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
    ): DecodedAudio? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            return@withContext null
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
            return@withContext null
        }

        extractor.selectTrack(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)

        // Channel count and sample rate can change when the decoder reports its
        // real output format, so they are read from the output format below
        // rather than trusted from the container.
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44_100)
        var pcmEncoding = AudioFormatEncoding.PCM_16BIT

        // Growable planar accumulation. Sized from the container duration when
        // available so the common case does no reallocation.
        val estimatedFrames = runCatching {
            (inputFormat.getLong(MediaFormat.KEY_DURATION) * sampleRate / 1_000_000L).toInt()
        }.getOrNull()?.coerceIn(0, 60 * 60 * 192_000) ?: 0

        var planar = Array(channelCount) { ShortArray(estimatedFrames.coerceAtLeast(sampleRate)) }
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
                        channelCount = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        pcmEncoding = AudioFormatEncoding.of(output)
                        if (planar.size != channelCount) {
                            planar = Array(channelCount) { channel ->
                                planar.getOrNull(channel) ?: ShortArray(planar[0].size)
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
                            planar = ensureCapacity(planar, frames + produced)
                            appendFrames(buffer, planar, frames, produced, channelCount, pcmEncoding)
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

        if (frames == 0) return@withContext null

        val exact = PcmBuffer(
            channels = Array(channelCount) { planar[it].copyOf(frames) },
            sampleRate = sampleRate,
        )

        if (!trimSilence) {
            return@withContext DecodedAudio(exact, frames, 0)
        }

        // Trim from the mono sum, so a channel that is silent alone does not
        // make the whole track look silent.
        val bounds = PeakEnvelope.contentBounds(exact.toMonoFloat(), SILENCE_THRESHOLD)
            ?: return@withContext DecodedAudio(exact, frames, 0)

        DecodedAudio(
            pcm = exact.slice(bounds.first, bounds.last + 1),
            originalFrameCount = frames,
            trimmedStartFrames = bounds.first,
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

    private fun ensureCapacity(planar: Array<ShortArray>, required: Int): Array<ShortArray> {
        if (planar[0].size >= required) return planar
        var capacity = planar[0].size.coerceAtLeast(1)
        while (capacity < required) capacity *= 2
        return Array(planar.size) { planar[it].copyOf(capacity) }
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
