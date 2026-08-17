package com.hereliesaz.sirmatchalot.session

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sixteen-bit PCM WAV, written and read.
 *
 * Takes are the only thing in a session that exists nowhere else. Every track
 * can be found again from its title and duration; a recording somebody made
 * over the top of their own mix cannot, so it travels inside the file as audio.
 *
 * WAV rather than a compressed format, for two reasons that both come down to
 * not losing what was captured. Encoding to a lossy format on the way out means
 * a take that has been round-tripped is not the take any more, and a session
 * saved and opened repeatedly would degrade a little each time. And WAV opens in
 * anything, which matters for a file the user may want to pull a recording out
 * of without this app.
 *
 * Only the subset needed here: 16-bit, interleaved, any channel count and rate.
 * The reader skips unknown chunks rather than assuming `fmt ` and `data` sit
 * where a minimal writer would put them, because plenty of encoders write
 * `LIST` metadata in between.
 */
object WavCodec {

    private const val HEADER_BYTES = 44
    private const val BITS = 16
    private const val PCM_FORMAT = 1.toShort()

    /** Interleaved 16-bit samples as a complete WAV file. */
    fun encode(interleaved: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }

        val dataBytes = interleaved.size * 2
        val buffer = ByteBuffer
            .allocate(HEADER_BYTES + dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        // Everything after this field: the header minus the eight bytes already
        // written, plus the audio.
        buffer.putInt(HEADER_BYTES - 8 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(PCM_FORMAT)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * BITS / 8)
        buffer.putShort((channels * BITS / 8).toShort())
        buffer.putShort(BITS.toShort())

        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        for (sample in interleaved) buffer.putShort(sample)

        return buffer.array()
    }

    /** What [encode] wrote, read back. Null for anything that is not one. */
    fun decode(bytes: ByteArray): Wav? {
        if (bytes.size < 12) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (tag(buffer) != "RIFF") return null
        buffer.int
        if (tag(buffer) != "WAVE") return null

        var channels = 0
        var sampleRate = 0
        var bits = 0

        while (buffer.remaining() >= 8) {
            val id = tag(buffer)
            val size = buffer.int
            if (size < 0 || size > buffer.remaining()) return null
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    val format = buffer.short
                    channels = buffer.short.toInt()
                    sampleRate = buffer.int
                    buffer.int
                    buffer.short
                    bits = buffer.short.toInt()
                    if (format != PCM_FORMAT || bits != BITS) return null
                    if (channels <= 0 || sampleRate <= 0) return null
                    // Anything the writer added beyond the 16 bytes just read.
                    buffer.position(buffer.position() + (size - 16))
                }

                "data" -> {
                    if (channels == 0 || sampleRate == 0) return null
                    val samples = ShortArray(size / 2) { buffer.short }
                    return Wav(samples, sampleRate, channels)
                }

                // A chunk this does not care about — LIST, fact, whatever else the
                // encoder felt like. Stepped over rather than treated as an error.
                // The pad byte that follows an odd-sized chunk may not actually be
                // present if the chunk is the last thing in the buffer, so the skip
                // is clamped to what remains instead of trusting the pad exists.
                else -> buffer.position(
                    (buffer.position() + size + (size % 2)).coerceAtMost(buffer.limit()),
                )
            }
        }
        return null
    }

    /** Reads a whole stream, then decodes it. */
    fun decode(stream: InputStream): Wav? {
        val out = ByteArrayOutputStream()
        stream.copyTo(out)
        return decode(out.toByteArray())
    }

    private fun tag(buffer: ByteBuffer): String {
        if (buffer.remaining() < 4) return ""
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    data class Wav(val samples: ShortArray, val sampleRate: Int, val channels: Int) {
        val frameCount: Int get() = if (channels == 0) 0 else samples.size / channels

        /** Deinterleaved, one array per channel — the shape a pad wants. */
        fun channels(): Array<ShortArray> = Array(channels) { channel ->
            ShortArray(frameCount) { frame -> samples[frame * channels + channel] }
        }

        // Generated equals on a ShortArray compares by identity, which makes a
        // round-trip test pass or fail on where the array happens to live.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Wav) return false
            return sampleRate == other.sampleRate &&
                channels == other.channels &&
                samples.contentEquals(other.samples)
        }

        override fun hashCode(): Int =
            samples.contentHashCode() * 31 * 31 + sampleRate * 31 + channels
    }
}
