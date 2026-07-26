package com.hereliesaz.sirmatchalot.sync

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The room server, driven over a real socket.
 *
 * `WebSocketProtocolTest` covers the bytes; this covers the conversation — that
 * a handshake completes, that a joiner is told the room as it stands, that an
 * update is merged rather than replacing everything, and that the merged result
 * comes back to the device that sent it. Those are the properties that make
 * several devices one instrument, and none of them is visible from the frame
 * codec alone.
 *
 * `ServerSocket` and `Socket` are plain JVM classes, so this runs as an ordinary
 * unit test rather than needing a device. Ports are taken from the OS rather
 * than hardcoded, so a test never fails because something else holds 8890.
 */
class SyncServerTest {

    private var server: SyncServer? = null
    private val peers = ArrayList<Peer>()

    @After
    fun tearDown() {
        peers.forEach { it.close() }
        server?.stop()
    }

    /** A port nothing is currently using. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startServer(code: String = "TEST"): SyncServer {
        // A discovery port of its own: two tests binding 8888 at once would
        // make one of them fail for reasons that have nothing to do with it.
        val instance = SyncServer(port = freePort(), discoveryPort = freePort())
        assertTrue("server should start", instance.start(code))
        server = instance
        return instance
    }

    /** One connected client, with a background reader so nothing deadlocks. */
    private inner class Peer(port: Int) {
        private val socket = Socket("127.0.0.1", port)
        private val out: OutputStream = socket.getOutputStream()
        private val input: InputStream = socket.getInputStream().buffered()
        private val received = ArrayBlockingQueue<JSONObject>(32)

        init {
            out.write(
                buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: 127.0.0.1\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }.toByteArray(),
            )
            out.flush()

            val response = readHttpResponse()
            assertTrue("expected a 101, got:\n$response", response.startsWith("HTTP/1.1 101"))
            assertTrue(
                "accept key must be the RFC 6455 §1.3 value",
                response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="),
            )

            Thread {
                runCatching {
                    while (true) {
                        val frame = WebSocketProtocol.decode(input) ?: break
                        if (frame.opcode == WebSocketProtocol.OPCODE_TEXT) {
                            received.offer(JSONObject(frame.text))
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun readHttpResponse(): String {
            val builder = StringBuilder()
            while (!builder.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) break
                builder.append(byte.toChar())
            }
            return builder.toString()
        }

        /** Sends [message], masked, as a client must. */
        fun send(message: JSONObject) {
            val payload = message.toString().toByteArray(Charsets.UTF_8)
            val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)
            val header = ArrayList<Byte>()
            header.add((0x80 or WebSocketProtocol.OPCODE_TEXT).toByte())
            if (payload.size < 126) {
                header.add((0x80 or payload.size).toByte())
            } else {
                header.add((0x80 or 126).toByte())
                header.add((payload.size shr 8 and 0xFF).toByte())
                header.add((payload.size and 0xFF).toByte())
            }
            mask.forEach { header.add(it) }
            val masked = ByteArray(payload.size) { i ->
                (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            out.write(header.toByteArray() + masked)
            out.flush()
        }

        /** The next message, or null if none arrives in time. */
        fun next(timeoutMs: Long = 2_000): JSONObject? =
            received.poll(timeoutMs, TimeUnit.MILLISECONDS)

        /** The next message of [type], skipping others. */
        fun nextOfType(type: String, timeoutMs: Long = 2_000): JSONObject? {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val message = next(200) ?: continue
                if (message.optString("type") == type) return message
            }
            return null
        }

        fun close() = runCatching { socket.close() }
    }

    private fun connect(server: SyncServer): Peer = Peer(server.port).also { peers.add(it) }

    @Test
    fun `a joining peer is sent the room as it stands`() {
        val server = startServer()
        server.updateState(JSONObject().put("crossfader", 40))

        val peer = connect(server)
        val init = peer.nextOfType("init_state")

        assertNotNull("a joiner must be told the current room state", init)
        assertEquals(40, init!!.getJSONObject("roomState").getInt("crossfader"))
    }

    @Test
    fun `an update is merged rather than replacing the room`() {
        val server = startServer()
        server.updateState(JSONObject().put("crossfader", 40))
        val peer = connect(server)
        peer.nextOfType("init_state")

        // A device sending only isPlaying must not erase the crossfader.
        peer.send(
            JSONObject()
                .put("type", "update_state")
                .put("roomCode", "TEST")
                .put("state", JSONObject().put("isPlaying", true)),
        )

        val synced = peer.nextOfType("state_synced")
        assertNotNull("an update must be echoed back", synced)
        val state = synced!!.getJSONObject("state")
        assertTrue(state.getBoolean("isPlaying"))
        assertEquals("the untouched key must survive the merge", 40, state.getInt("crossfader"))
    }

    @Test
    fun `an update reaches every device including the one that sent it`() {
        val server = startServer()
        val sender = connect(server)
        val other = connect(server)
        sender.nextOfType("init_state")
        other.nextOfType("init_state")

        sender.send(
            JSONObject()
                .put("type", "update_state")
                .put("roomCode", "TEST")
                .put("state", JSONObject().put("crossfader", -70)),
        )

        // Converging on the host's value, rather than each device trusting its
        // own, is the whole difference between linked and merely notified.
        assertEquals(-70, sender.nextOfType("state_synced")!!.getJSONObject("state").getInt("crossfader"))
        assertEquals(-70, other.nextOfType("state_synced")!!.getJSONObject("state").getInt("crossfader"))
    }

    @Test
    fun `an event is acted on by the host and relayed to peers`() {
        val server = startServer()
        val acted = ArrayBlockingQueue<Pair<String, JSONObject>>(4)
        server.onEvent = { event, payload -> acted.offer(event to payload) }

        val sender = connect(server)
        val other = connect(server)
        sender.nextOfType("init_state")
        other.nextOfType("init_state")

        sender.send(
            JSONObject()
                .put("type", "trigger_event")
                .put("roomCode", "TEST")
                .put("event", "play_sampler_pad")
                .put("payload", JSONObject().put("padId", 3)),
        )

        val handled = acted.poll(2, TimeUnit.SECONDS)
        assertNotNull("the host must play the pad, not merely forward it", handled)
        assertEquals("play_sampler_pad", handled!!.first)
        assertEquals(3, handled.second.getInt("padId"))

        val relayed = other.nextOfType("event_triggered")
        assertNotNull("other devices must hear about it too", relayed)
        assertEquals("play_sampler_pad", relayed!!.getString("event"))
        assertEquals(3, relayed.getJSONObject("payload").getInt("padId"))
    }

    @Test
    fun `a wrong room code is refused rather than ignored`() {
        val server = startServer(code = "ABCD")
        val peer = connect(server)
        peer.nextOfType("init_state")

        peer.send(JSONObject().put("type", "join").put("roomCode", "WXYZ"))

        val refusal = peer.nextOfType("join_refused")
        assertNotNull("a peer that typed the code wrong must be told", refusal)
    }

    @Test
    fun `a correct room code is accepted, whatever its case`() {
        val server = startServer(code = "ABCD")
        val peer = connect(server)
        peer.nextOfType("init_state")

        peer.send(JSONObject().put("type", "join").put("roomCode", "abcd"))

        assertNull("a matching code must not be refused", peer.nextOfType("join_refused", 500))
    }

    @Test
    fun `the roster follows connections`() {
        val server = startServer()
        val counts = ArrayBlockingQueue<Int>(8)
        server.onPeersChanged = { counts.offer(it) }

        val peer = connect(server)
        peer.nextOfType("init_state")
        assertEquals(1, counts.poll(2, TimeUnit.SECONDS))

        peer.close()
        // The reader thread notices the close and drops the connection.
        assertEquals(0, counts.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun `discovery answers the broadcast the client has always sent`() {
        val discoveryPort = freePort()
        val instance = SyncServer(port = freePort(), discoveryPort = discoveryPort)
        assertTrue(instance.start("TEST"))
        server = instance

        // The host only answers when it has an address to give; a machine with
        // no non-loopback IPv4 address has nothing to advertise, and testing
        // that it invents one anyway would be testing the wrong thing.
        if (SyncServer.localAddress() == null) return

        DatagramSocket().use { socket ->
            socket.soTimeout = 3_000
            val request = SyncServer.DISCOVERY_REQUEST.toByteArray()
            socket.send(
                DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName("127.0.0.1"),
                    discoveryPort,
                ),
            )

            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val reply = JSONObject(String(packet.data, 0, packet.length))

            // These two field names are what SyncClient.startLanDiscovery reads.
            assertTrue(reply.has("serverIp"))
            assertTrue(reply.getString("wsUrl").startsWith("ws://"))
            assertEquals("TEST", reply.getString("roomCode"))
        }
    }

    @Test
    fun `a request that is not an upgrade is refused`() {
        val server = startServer()
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()

            val response = socket.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 400 Bad Request", response)
        }
    }

    @Test
    fun `room codes avoid characters people confuse when reading them aloud`() {
        val random = kotlin.random.Random(7)
        repeat(200) {
            val code = SyncServer.generateRoomCode(random)
            assertEquals(4, code.length)
            for (character in code) {
                assertTrue(
                    "'$character' is too easy to mishear or mistype",
                    character !in "IO01",
                )
            }
        }
    }
}
