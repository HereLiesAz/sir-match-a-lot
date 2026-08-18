package com.hereliesaz.sirmatchalot.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The WebSocket wire format, server side.
 *
 * A device has to be able to host a room, and OkHttp speaks WebSocket as a
 * client only. The risky parts of writing the server half are the handshake key
 * and frame decoding — both pure functions of bytes, so both are pinned exactly
 * here rather than discovered on a phone that silently refuses to connect.
 */
class WebSocketProtocolTest {

    // --- Handshake ---

    @Test
    fun `the accept key matches the example in RFC 6455`() {
        // Section 1.3 gives this exact pair. If it is wrong every client refuses
        // the connection and the server sees nothing at all.
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            WebSocketProtocol.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun `surrounding whitespace in the key is ignored`() {
        assertEquals(
            WebSocketProtocol.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="),
            WebSocketProtocol.acceptFor("  dGhlIHNhbXBsZSBub25jZQ==  "),
        )
    }

    @Test
    fun `base64 encodes the lengths that need padding`() {
        assertEquals("", WebSocketProtocol.base64(ByteArray(0)))
        assertEquals("AA==", WebSocketProtocol.base64(byteArrayOf(0)))
        assertEquals("AAA=", WebSocketProtocol.base64(byteArrayOf(0, 0)))
        assertEquals("AAAA", WebSocketProtocol.base64(byteArrayOf(0, 0, 0)))
        assertEquals("TWFu", WebSocketProtocol.base64("Man".toByteArray()))
        assertEquals("cGxlYXN1cmUu", WebSocketProtocol.base64("pleasure.".toByteArray()))
    }

    @Test
    fun `header names are matched regardless of how the client capitalised them`() {
        // HTTP header names are case-insensitive and clients genuinely differ.
        val request = "GET / HTTP/1.1\r\nHost: x\r\nUPGRADE: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Key: abc\r\n\r\n"
        val headers = WebSocketProtocol.parseHeaders(request)
        assertEquals("websocket", headers["upgrade"])
        assertEquals("abc", headers["sec-websocket-key"])
        assertTrue(WebSocketProtocol.isUpgrade(headers))
    }

    @Test
    fun `a request missing the upgrade parts is not accepted`() {
        val plain = WebSocketProtocol.parseHeaders("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        assertFalse(WebSocketProtocol.isUpgrade(plain))

        val noKey = WebSocketProtocol.parseHeaders(
            "GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n",
        )
        assertFalse(WebSocketProtocol.isUpgrade(noKey))
    }

    @Test
    fun `a Connection header listing several tokens still counts`() {
        // Browsers send "keep-alive, Upgrade".
        val headers = WebSocketProtocol.parseHeaders(
            "GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n" +
                "Sec-WebSocket-Key: abc\r\n\r\n",
        )
        assertTrue(WebSocketProtocol.isUpgrade(headers))
    }

    @Test
    fun `the handshake response carries the computed accept`() {
        val response = WebSocketProtocol.handshakeResponse("dGhlIHNhbXBsZSBub25jZQ==")
        assertTrue(response.startsWith("HTTP/1.1 101 "))
        assertTrue(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
        assertTrue("the response must end with a blank line", response.endsWith("\r\n\r\n"))
    }

    // --- Framing ---

    /** Masks a payload the way a client must, so decode sees a realistic frame. */
    private fun clientFrame(opcode: Int, payload: ByteArray, mask: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray {
        val header = ArrayList<Byte>()
        header.add((0x80 or opcode).toByte())
        when {
            payload.size < 126 -> header.add((0x80 or payload.size).toByte())
            payload.size <= 0xFFFF -> {
                header.add((0x80 or 126).toByte())
                header.add((payload.size shr 8 and 0xFF).toByte())
                header.add((payload.size and 0xFF).toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                for (shift in 56 downTo 0 step 8) header.add((payload.size.toLong() shr shift and 0xFF).toByte())
            }
        }
        header.addAll(mask.toList())
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        return header.toByteArray() + masked
    }

    @Test
    fun `a masked client text frame decodes to its text`() {
        val bytes = clientFrame(WebSocketProtocol.OPCODE_TEXT, "hello room".toByteArray())
        val frame = WebSocketProtocol.decode(ByteArrayInputStream(bytes))!!
        assertEquals(WebSocketProtocol.OPCODE_TEXT, frame.opcode)
        assertEquals("hello room", frame.text)
    }

    @Test
    fun `the two extended length forms decode`() {
        // 126 switches to a 16-bit length, 65536 to a 64-bit one. Both are easy
        // to get wrong and neither shows up in small test messages.
        for (size in listOf(125, 126, 200, 0xFFFF, 0x10000)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val frame = WebSocketProtocol.decode(
                ByteArrayInputStream(clientFrame(WebSocketProtocol.OPCODE_BINARY, payload)),
            )!!
            assertEquals("size $size", size, frame.payload.size)
            assertTrue("size $size payload corrupted", payload.contentEquals(frame.payload))
        }
    }

    @Test
    fun `an empty payload decodes`() {
        val frame = WebSocketProtocol.decode(
            ByteArrayInputStream(clientFrame(WebSocketProtocol.OPCODE_CLOSE, ByteArray(0))),
        )!!
        assertEquals(WebSocketProtocol.OPCODE_CLOSE, frame.opcode)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `end of stream decodes to null rather than throwing`() {
        assertNull(WebSocketProtocol.decode(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `an oversized frame is refused rather than allocated`() {
        // A length is attacker-controlled and the 64-bit form can ask for eight
        // exabytes. Trusting it is an out-of-memory crash on request.
        val header = byteArrayOf(
            (0x80 or WebSocketProtocol.OPCODE_TEXT).toByte(),
            (0x80 or 127).toByte(),
            0, 0, 0, 0, 0x7F, -1, -1, -1,
        )
        try {
            WebSocketProtocol.decode(ByteArrayInputStream(header + ByteArray(4)))
            throw AssertionError("an enormous length was accepted")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("exceeds"))
        }
    }

    @Test
    fun `server frames are never masked`() {
        // RFC 6455 section 5.1: a client receiving a masked frame must close the
        // connection, so masking here would break every peer.
        val encoded = WebSocketProtocol.encodeText("hi")
        assertEquals(0, encoded[1].toInt() and 0x80)
    }

    @Test
    fun `encoded frames use the shortest length form that fits`() {
        assertEquals(2, WebSocketProtocol.encode(WebSocketProtocol.OPCODE_TEXT, ByteArray(10)).size - 10)
        assertEquals(4, WebSocketProtocol.encode(WebSocketProtocol.OPCODE_TEXT, ByteArray(200)).size - 200)
        assertEquals(10, WebSocketProtocol.encode(WebSocketProtocol.OPCODE_TEXT, ByteArray(70_000)).size - 70_000)
    }

    @Test
    fun `text survives a round trip through encode and decode`() {
        // Encoding is server-to-client (unmasked) and decoding is
        // client-to-server (masked), so this also proves decode tolerates an
        // unmasked frame rather than insisting on a mask.
        val text = "{\"type\":\"state_synced\",\"state\":{\"crossfader\":40}}"
        val frame = WebSocketProtocol.decode(
            ByteArrayInputStream(WebSocketProtocol.encodeText(text)),
        )!!
        assertEquals(text, frame.text)
    }

    @Test
    fun `non-ascii text survives the round trip`() {
        val text = "Röyksopp — Eple ✦"
        val frame = WebSocketProtocol.decode(
            ByteArrayInputStream(clientFrame(WebSocketProtocol.OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))),
        )!!
        assertEquals(text, frame.text)
    }

    @Test
    fun `frames compare by contents, not by array identity`() {
        // A data class on a ByteArray compares references, which would make
        // every assertion about a frame's payload pass regardless.
        val a = WebSocketProtocol.Frame(1, "x".toByteArray())
        val b = WebSocketProtocol.Frame(1, "x".toByteArray())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // --- Room codes ---

    @Test
    fun `room codes avoid characters people confuse when reading aloud`() {
        val random = kotlin.random.Random(7)
        repeat(200) {
            val code = SyncServer.generateRoomCode(random)
            assertEquals(4, code.length)
            for (character in code) {
                assertTrue("'$character' is ambiguous spoken", character !in "IO01")
            }
        }
    }
}
