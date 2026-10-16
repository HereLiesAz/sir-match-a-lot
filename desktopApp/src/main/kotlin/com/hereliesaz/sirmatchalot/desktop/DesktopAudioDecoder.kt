package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.audio.PcmBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Decodes a local audio file to [PcmBuffer], the desktop analogue of `:app`'s
 * `AudioDecoder` (`MediaExtractor`/`MediaCodec`).
 *
 * Scoped to whatever `javax.sound.sampled` reads natively — WAV, AIFF, AU —
 * rather than the whole container/codec matrix `MediaCodec` gives Android.
 * MP3/FLAC/AAC are not supported: that would need an SPI provider
 * (`mp3spi`, `jflac`) or a bundled decoder. WAV covers what
 * [com.hereliesaz.sirmatchalot.session.WavCodec] reads and writes for pad
 * takes, so a desktop build can load and play back its own captures.
 *
 * Decoding reads the whole file into memory and blocks; call it off the UI
 * thread.
 */
object DesktopAudioDecoder {

    /** Decodes [file], or null when it can't be read as audio at all. */
    fun decode(file: File): PcmBuffer? {
        val original = runCatching { AudioSystem.getAudioInputStream(file) }.getOrNull() ?: return null
        original.use { source ->
            val base = source.format
            // Some files (and some SPI readers) report AudioSystem.NOT_SPECIFIED
            // (-1) for the rate or channel count. -1 would make the target
            // format below invalid (and a frame size of -2), so fall back to
            // the sample rate the rest of the app assumes and to mono.
            val sampleRate = base.sampleRate.takeIf { it > 0f } ?: DEFAULT_SAMPLE_RATE
            val channelCount = base.channels.takeIf { it > 0 } ?: 1
            // Normalised to signed 16-bit little-endian PCM regardless of the
            // file's own encoding (8-bit, big-endian, mu-law, ...), so the
            // frame-reading loop below has exactly one shape to handle.
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                BITS_PER_SAMPLE,
                channelCount,
                channelCount * BYTES_PER_SAMPLE,
                sampleRate,
                false,
            )
            val pcm = if (base.matches(target)) source else {
                runCatching { AudioSystem.getAudioInputStream(target, source) }.getOrNull() ?: return null
            }
            val bytes = pcm.readBytes()
            val frameCount = bytes.size / (BYTES_PER_SAMPLE * channelCount)
            if (frameCount <= 0) return null

            val channels = Array(channelCount) { ShortArray(frameCount) }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (frame in 0 until frameCount) {
                for (channel in 0 until channelCount) {
                    channels[channel][frame] = buffer.short
                }
            }
            return PcmBuffer(channels, sampleRate.toInt())
        }
    }

    private const val BITS_PER_SAMPLE = 16
    private const val DEFAULT_SAMPLE_RATE = 44_100f
    private const val BYTES_PER_SAMPLE = 2
}
