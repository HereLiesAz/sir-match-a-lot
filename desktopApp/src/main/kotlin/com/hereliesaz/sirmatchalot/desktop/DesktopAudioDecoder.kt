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
 * rather than the whole container/codec matrix `MediaCodec` gives Android for
 * free. That is a real gap, not a hidden one: MP3/FLAC/AAC support needs an
 * SPI provider (`mp3spi`, `jflac`) or a bundled decoder this phase doesn't
 * add. WAV alone is not a token gesture, though — it is what
 * [com.hereliesaz.sirmatchalot.session.WavCodec] already reads and writes
 * for pad takes, so a desktop build can load and play back its own captures
 * end to end before broader format support exists.
 */
object DesktopAudioDecoder {

    /** Decodes [file], or null when it can't be read as audio at all. */
    fun decode(file: File): PcmBuffer? {
        val original = runCatching { AudioSystem.getAudioInputStream(file) }.getOrNull() ?: return null
        original.use { source ->
            val base = source.format
            // Normalised to signed 16-bit little-endian PCM regardless of the
            // file's own encoding (8-bit, big-endian, mu-law, ...), so the
            // frame-reading loop below has exactly one shape to handle.
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                base.sampleRate,
                BITS_PER_SAMPLE,
                base.channels,
                base.channels * BYTES_PER_SAMPLE,
                base.sampleRate,
                false,
            )
            val pcm = if (base.matches(target)) source else {
                runCatching { AudioSystem.getAudioInputStream(target, source) }.getOrNull() ?: return null
            }
            val bytes = pcm.readBytes()
            val channelCount = pcm.format.channels.coerceAtLeast(1)
            val frameCount = bytes.size / (BYTES_PER_SAMPLE * channelCount)
            if (frameCount <= 0) return null

            val channels = Array(channelCount) { ShortArray(frameCount) }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (frame in 0 until frameCount) {
                for (channel in 0 until channelCount) {
                    channels[channel][frame] = buffer.short
                }
            }
            return PcmBuffer(channels, pcm.format.sampleRate.toInt())
        }
    }

    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2
}
