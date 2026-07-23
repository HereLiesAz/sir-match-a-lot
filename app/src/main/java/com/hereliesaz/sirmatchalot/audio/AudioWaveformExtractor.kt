package com.hereliesaz.sirmatchalot.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import kotlin.math.abs

data class WaveformData(
    val peaks: FloatArray,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val durationMs: Long
)

object AudioWaveformExtractor {

    private const val SILENCE_THRESHOLD = 0.02f // 2% of max volume
    private const val TIMEOUT_US = 10000L

    fun extract(context: Context, uri: Uri): WaveformData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        var audioTrackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            return null
        }

        extractor.selectTrack(audioTrackIndex)

        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        val durationMs = durationUs / 1000L
        
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val peaksList = mutableListOf<Float>()
        var isEOS = false
        var decodeEOS = false

        var trimStartMs = -1L
        var lastNonSilentMs = 0L

        // We will process audio in chunks to get peaks
        var currentChunkMax = 0f
        var currentChunkSamples = 0
        // Approx 100ms per peak assuming 44.1kHz, adjust as needed. 
        // We'll calculate it based on bytes processed instead to be simpler.
        val samplesPerPeak = 4410

        val info = MediaCodec.BufferInfo()

        try {
            while (!decodeEOS) {
                if (!isEOS) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val sampleSize = extractor.readSampleData(buffer!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // format changed
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            // Read as 16-bit PCM
                            val shortBuffer = buffer.asShortBuffer()
                            while (shortBuffer.hasRemaining()) {
                                val sample = shortBuffer.get()
                                val normalized = abs(sample.toFloat() / Short.MAX_VALUE)
                                
                                if (normalized > currentChunkMax) {
                                    currentChunkMax = normalized
                                }
                                
                                currentChunkSamples++
                                
                                if (currentChunkSamples >= samplesPerPeak) {
                                    peaksList.add(currentChunkMax)
                                    val currentMs = (info.presentationTimeUs / 1000L)
                                    
                                    if (currentChunkMax > SILENCE_THRESHOLD) {
                                        if (trimStartMs == -1L) {
                                            trimStartMs = currentMs
                                        }
                                        lastNonSilentMs = currentMs
                                    }
                                    
                                    currentChunkMax = 0f
                                    currentChunkSamples = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decodeEOS = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        if (trimStartMs == -1L) trimStartMs = 0L

        return WaveformData(
            peaks = peaksList.toFloatArray(),
            trimStartMs = trimStartMs,
            trimEndMs = lastNonSilentMs,
            durationMs = durationMs
        )
    }
}
