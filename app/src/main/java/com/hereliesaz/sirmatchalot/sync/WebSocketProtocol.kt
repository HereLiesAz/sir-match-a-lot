package com.hereliesaz.sirmatchalot.sync

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The wire format of RFC 6455, enough of it to host a room.
 *
 * A device has to be able to *be* the server — one-click connection on the same
 * Wi-Fi means one of the phones is hosting, and until now nothing could. OkHttp
 * speaks WebSocket as a client only, so the server half is here.
 *
 * Written against raw sockets rather than pulled in as a library because the
 * risky parts — the handshake accept key, and frame decoding with its three
 * length forms and its masking — are pure functions of bytes, and pure functions
 * of bytes can be tested exactly. A library would move the risk somewhere it
 * could not be checked from here.
 *
 * Only what a room needs is implemented: text frames, close, ping and pong.
 * Extensions, and fragmentation across frames, are not.
 */
object WebSocketProtocol {

    /**
     * The constant every WebSocket handshake is salted with, from RFC 6455 §1.3.
     * It exists so a server cannot accidentally accept a non-WebSocket request.
     */
    const val HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    // Opcodes, RFC 6455 §5.2.
    const val OPCODE_CONTINUATION = 0x0
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    /** A frame read off the wire. */
    data class Frame(
        val opcode: Int,
        val payload: ByteArray,
        val fin: Boolean = true,
    ) {
        val text: String get() = String(payload, Charsets.UTF_8)

        // Data class equality on a ByteArray compares references, which makes
        // every assertion about a frame's contents silently pass.
        override fun equals(other: Any?): Boolean =
            other is Frame && opcode == other.opcode && fin == other.fin &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (opcode * 31 + payload.contentHashCode()) * 31 + fin.hashCode()
    }

    /**
     * The `Sec-WebSocket-Accept` value for a client's `Sec-WebSocket-Key`.
     *
     * SHA-1 of the key concatenated with [HANDSHAKE_GUID], base64-encoded. The
     * client checks it, so getting this wrong means every connection is refused
     * by the *client* with no error visible on the server.
     */
    fun acceptFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key.trim() + HANDSHAKE_GUID).toByteArray(Charsets.UTF_8))
        return base64(digest)
    }

    /**
     * Parses the headers of an HTTP upgrade request.
     *
     * @return header names lowercased, so lookups do not depend on how the
     *   client happened to capitalise them — HTTP header names are
     *   case-insensitive and clients genuinely differ.
     */
    fun parseHeaders(request: String): Map<String, String> =
        request.lineSequence()
            .drop(1)
            .takeWhile { it.isNotBlank() }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim().lowercase() to
                        line.substring(separator + 1).trim()
                }
            }
            .toMap()

    /** True when [headers] describe a WebSocket upgrade this server can accept. */
    fun isUpgrade(headers: Map<String, String>): Boolean =
        headers["upgrade"]?.equals("websocket", ignoreCase = true) == true &&
            headers["connection"]?.contains("upgrade", ignoreCase = true) == true &&
            !headers["sec-websocket-key"].isNullOrBlank()

    /** The 101 response completing a handshake for [key]. */
    fun handshakeResponse(key: String): String = buildString {
        append("HTTP/1.1 101 Switching Protocols\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Accept: ${acceptFor(key)}\r\n")
        append("\r\n")
    }

    /**
     * Encodes a server-to-client frame.
     *
     * Never masked: RFC 6455 §5.1 requires that a server does not mask, and a
     * client that receives a masked frame must close the connection.
     */
    fun encode(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        val header = ArrayList<Byte>(10)
        header.add((((if (fin) 0x80 else 0x00) or (opcode and 0x0F))).toByte())

        when {
            payload.size < 126 -> header.add(payload.size.toByte())
            payload.size <= 0xFFFF -> {
                header.add(126.toByte())
                header.add((payload.size shr 8 and 0xFF).toByte())
                header.add((payload.size and 0xFF).toByte())
            }
            else -> {
                header.add(127.toByte())
                val length = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header.add((length shr shift and 0xFF).toByte())
                }
            }
        }
        return header.toByteArray() + payload
    }

    fun encodeText(text: String): ByteArray =
        encode(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    /**
     * Reads one frame from [input], unmasking it.
     *
     * @return the frame, or null at end of stream.
     * @throws java.io.IOException if the frame is malformed or larger than
     *   [maxPayload]. A length is attacker-controlled and a 64-bit one can ask
     *   for eight exabytes, so it is bounded rather than trusted.
     */
    fun decode(input: InputStream, maxPayload: Int = MAX_PAYLOAD): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null

        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()

        if (length == 126L) {
            length = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }

        if (length < 0 || length > maxPayload) {
            throw java.io.IOException("frame payload of $length exceeds the $maxPayload limit")
        }

        val mask = if (masked) ByteArray(4) { readByte(input).toByte() } else null
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < payload.size) {
            val count = input.read(payload, read, payload.size - read)
            if (count < 0) return null
            read += count
        }
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(opcode, payload, fin)
    }

    /** Writes a text frame to [output] and flushes it. */
    fun sendText(output: OutputStream, text: String) {
        output.write(encodeText(text))
        output.flush()
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw java.io.IOException("unexpected end of frame")
        return value
    }

    /**
     * Base64 without `java.util.Base64` or `android.util.Base64`.
     *
     * The former needs API 26 and this app supports 24; the latter is an Android
     * stub under unit test, returning empty strings and making every handshake
     * assertion vacuously pass. Sixteen lines is cheaper than either problem.
     */
    internal fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2

            out.append(alphabet[triple shr 18 and 0x3F])
            out.append(alphabet[triple shr 12 and 0x3F])
            out.append(if (i + 1 < bytes.size) alphabet[triple shr 6 and 0x3F] else '=')
            out.append(if (i + 2 < bytes.size) alphabet[triple and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Room messages are small JSON; anything larger is a mistake or an attack. */
    const val MAX_PAYLOAD = 1 shl 20
}
