package com.hereliesaz.sirmatchalot.sync

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun startServer(
        code: String = "TEST",
        identity: DeviceIdentity? = null,
        known: KnownDevices? = null,
    ): SyncServer {
        // A discovery port of its own: two tests binding 8888 at once would
        // make one of them fail for reasons that have nothing to do with it.
        val instance = SyncServer(
            port = freePort(),
            discoveryPort = freePort(),
            identity = identity,
            known = known,
        )
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

        /** This connection's own keys, and the session once one is agreed. */
        private val keys = RoomCrypto.newKeyPair()
        private var session: RoomCrypto.Session? = null

        /** Set by [pair], so a test can approve the right connection. */
        var peerId: String? = null

        val pairingCode: String? get() = session?.sas

        /** A long-term identity to present, when a test is about being known. */
        var identity: DeviceIdentity? = null

        /** An identity to *claim* while signing with another — the attack. */
        var claimedIdentity: DeviceIdentity? = null

        private var transcript: ByteArray? = null

        /** Says hello and waits for the host's key, exactly as the app does. */
        fun startPairing(roomCode: String) {
            send(
                JSONObject()
                    .put("type", "hello")
                    .put("key", WebSocketProtocol.base64(RoomCrypto.encodePublicKey(keys.public)))
                    .put("name", "A test device")
                    .apply {
                        val claimed = claimedIdentity ?: identity
                        claimed?.let {
                            put("identity", WebSocketProtocol.base64(it.publicKey.encoded))
                        }
                    },
            )
            val ack = nextOfType("hello_ack")
            assertNotNull("the host must answer hello", ack)
            val hostKey = RoomCrypto.decodePublicKey(
                WebSocketProtocol.decodeBase64(ack!!.getString("key"))!!,
            )
            assertNotNull(hostKey)
            session = RoomCrypto.agree(keys, hostKey!!, roomCode)
            assertNotNull("the exchange must produce a session", session)
            transcript = RoomCrypto.transcriptOf(
                RoomCrypto.encodePublicKey(keys.public),
                WebSocketProtocol.decodeBase64(ack.getString("key"))!!,
                roomCode,
            )
        }

        /** A join carrying this peer's proof, as the real client sends it. */
        fun sendSignedJoin(roomCode: String) {
            val signed = transcript ?: throw AssertionError("no transcript")
            sendSealed(
                JSONObject()
                    .put("type", "join")
                    .put("roomCode", roomCode)
                    .apply {
                        // Claimed identity, signed by whoever `identity` is —
                        // the same when honest, different when impersonating.
                        val claimed = claimedIdentity ?: identity
                        val signer = identity
                        if (claimed != null && signer != null) {
                            put("identity", WebSocketProtocol.base64(claimed.publicKey.encoded))
                            put("signature", WebSocketProtocol.base64(signer.sign(signed)))
                        }
                    },
            )
        }

        /** Sends [message] sealed, which is the only way the room accepts one. */
        fun sendSealed(message: JSONObject) {
            val current = session ?: throw AssertionError("no session; call startPairing first")
            send(
                JSONObject()
                    .put("type", "sealed")
                    .put(
                        "data",
                        WebSocketProtocol.base64(
                            current.seal(message.toString().toByteArray(Charsets.UTF_8)),
                        ),
                    ),
            )
        }

        /** The next sealed message of [type], opened. */
        fun nextSealed(type: String, timeoutMs: Long = 2_000): JSONObject? {
            val current = session ?: return null
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val envelope = next(200) ?: continue
                if (envelope.optString("type") != "sealed") continue
                val opened = current.open(
                    WebSocketProtocol.decodeBase64(envelope.getString("data")) ?: continue,
                ) ?: continue
                val message = JSONObject(opened.toString(Charsets.UTF_8))
                if (message.optString("type") == type) return message
            }
            return null
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

    /**
     * Connects, agrees a key, gets the host's approval, and joins.
     *
     * What a real client does on open, in order. These tests used to drive the
     * server with a bare `join` — and before that with no join at all, which was
     * the hole stated as a fixture. Now nothing reaches the room without a key
     * exchange, this host's user approving the digits, and a sealed join.
     */
    private fun join(server: SyncServer, code: String = "TEST"): Peer {
        val peer = pair(server, code)
        server.approvePeer(peer.peerId!!)
        peer.sendSealed(JSONObject().put("type", "join").put("roomCode", code))
        return peer
    }

    /** Connects and completes the key exchange, stopping short of approval. */
    private fun pair(server: SyncServer, code: String = "TEST"): Peer {
        val waiting = java.util.concurrent.ArrayBlockingQueue<SyncServer.PendingPeer>(4)
        server.onPairingRequested = { waiting.offer(it) }

        val peer = connect(server)
        peer.startPairing(code)

        val pending = waiting.poll(2, TimeUnit.SECONDS)
        assertNotNull("the host must be asked about a joining device", pending)
        peer.peerId = pending!!.id
        assertEquals(
            "both sides must derive the same code, or no user can compare them",
            pending.pairingCode,
            peer.pairingCode,
        )
        return peer
    }

    @Test
    fun `a joining peer is sent the room as it stands`() {
        val server = startServer()
        server.updateState(JSONObject().put("crossfader", 40))

        val peer = join(server)
        val init = peer.nextSealed("init_state")

        assertNotNull("a joiner must be told the current room state", init)
        assertEquals(40, init!!.getJSONObject("roomState").getInt("crossfader"))
    }

    @Test
    fun `an update is merged rather than replacing the room`() {
        val server = startServer()
        server.updateState(JSONObject().put("crossfader", 40))
        val peer = join(server)
        peer.nextSealed("init_state")

        // A device sending only isPlaying must not erase the crossfader.
        peer.sendSealed(
            JSONObject()
                .put("type", "update_state")
                .put("roomCode", "TEST")
                .put("state", JSONObject().put("isPlaying", true)),
        )

        val synced = peer.nextSealed("state_synced")
        assertNotNull("an update must be echoed back", synced)
        val state = synced!!.getJSONObject("state")
        assertTrue(state.getBoolean("isPlaying"))
        assertEquals("the untouched key must survive the merge", 40, state.getInt("crossfader"))
    }

    @Test
    fun `an update reaches every device including the one that sent it`() {
        val server = startServer()
        val sender = join(server)
        val other = join(server)
        sender.nextSealed("init_state")
        other.nextSealed("init_state")

        sender.sendSealed(
            JSONObject()
                .put("type", "update_state")
                .put("roomCode", "TEST")
                .put("state", JSONObject().put("crossfader", -70)),
        )

        // Converging on the host's value, rather than each device trusting its
        // own, is the whole difference between linked and merely notified.
        assertEquals(-70, sender.nextSealed("state_synced")!!.getJSONObject("state").getInt("crossfader"))
        assertEquals(-70, other.nextSealed("state_synced")!!.getJSONObject("state").getInt("crossfader"))
    }

    @Test
    fun `an event is acted on by the host and relayed to peers`() {
        val server = startServer()
        val acted = ArrayBlockingQueue<Pair<String, JSONObject>>(4)
        server.onEvent = { event, payload -> acted.offer(event to payload) }

        val sender = join(server)
        val other = join(server)
        sender.nextSealed("init_state")
        other.nextSealed("init_state")

        sender.sendSealed(
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

        val relayed = other.nextSealed("event_triggered")
        assertNotNull("other devices must hear about it too", relayed)
        assertEquals("play_sampler_pad", relayed!!.getString("event"))
        assertEquals(3, relayed.getJSONObject("payload").getInt("padId"))
    }

    @Test
    fun `a peer that never joined commands nothing`() {
        // The hole this closes: `handle` dispatched update_state and
        // trigger_event unconditionally, and the room state went out on
        // handshake, so a socket that simply never sent `join` was never
        // checked against the room code at all. Anything on the Wi-Fi could
        // load tracks and drive the filter on somebody else's instrument.
        val server = startServer(code = "ABCD")
        val acted = ArrayBlockingQueue<Pair<String, JSONObject>>(4)
        server.onEvent = { event, payload -> acted.offer(event to payload) }

        val stranger = connect(server)
        assertNull("nothing is handed out before a join", stranger.next(500))

        stranger.send(
            JSONObject()
                .put("type", "trigger_event")
                .put("event", "play_sampler_pad")
                .put("payload", JSONObject().put("padId", 3)),
        )
        stranger.send(
            JSONObject()
                .put("type", "update_state")
                .put("state", JSONObject().put("crossfader", -70)),
        )

        assertNull("an unjoined peer must not reach the engine", acted.poll(1, TimeUnit.SECONDS))
        assertNull("nor change the room", stranger.next(500))
    }

    // --- Being remembered ---

    @Test
    fun `a device paired with before rejoins without asking anybody`() {
        // The whole point of remembering one: two people who have already
        // compared six digits should not be asked to compare them again every
        // time the app opens.
        val peerIdentity = DeviceIdentity.load(MemoryStore())
        val known = KnownDevices(MemoryStore())
        known.remember(peerIdentity.fingerprint, "Pixel 8")

        val server = startServer(
            code = "ABCD",
            identity = DeviceIdentity.load(MemoryStore()),
            known = known,
        )
        val asked = ArrayBlockingQueue<SyncServer.PendingPeer>(4)
        server.onPairingRequested = { asked.offer(it) }

        val peer = connect(server).also { it.identity = peerIdentity }
        peer.startPairing("ABCD")
        peer.sendSignedJoin("ABCD")

        assertNotNull("a remembered device must be let in", peer.nextSealed("init_state"))
        assertNull("and nobody must be asked about it", asked.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `claiming a remembered fingerprint without the key proves nothing`() {
        // The attack the trust list would otherwise invite. The fingerprint is
        // public — it is on the wire every time that device connects — so if
        // remembering one meant trusting whoever claims it, the memory would be
        // worse than useless. The signature is what is actually checked.
        val trusted = DeviceIdentity.load(MemoryStore())
        val impostor = DeviceIdentity.load(MemoryStore())
        val known = KnownDevices(MemoryStore())
        known.remember(trusted.fingerprint, "Pixel 8")

        val server = startServer(
            code = "ABCD",
            identity = DeviceIdentity.load(MemoryStore()),
            known = known,
        )
        val asked = ArrayBlockingQueue<SyncServer.PendingPeer>(4)
        server.onPairingRequested = { asked.offer(it) }

        val peer = connect(server).also {
            // Claims the remembered device's identity, signs with its own.
            it.claimedIdentity = trusted
            it.identity = impostor
        }
        peer.startPairing("ABCD")
        peer.sendSignedJoin("ABCD")

        assertNull("an impostor must not be admitted", peer.nextSealed("init_state", 800))
        assertNotNull(
            "and must be put in front of the user like any stranger",
            asked.poll(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `a device that has never paired is still asked about`() {
        val known = KnownDevices(MemoryStore())
        val server = startServer(
            code = "ABCD",
            identity = DeviceIdentity.load(MemoryStore()),
            known = known,
        )
        val asked = ArrayBlockingQueue<SyncServer.PendingPeer>(4)
        server.onPairingRequested = { asked.offer(it) }

        val peer = connect(server).also { it.identity = DeviceIdentity.load(MemoryStore()) }
        peer.startPairing("ABCD")
        peer.sendSignedJoin("ABCD")

        assertNotNull("a stranger must be asked about", asked.poll(1, TimeUnit.SECONDS))
        assertNull("and admitted by nothing else", peer.nextSealed("init_state", 500))
    }

    @Test
    fun `approving a device remembers it, and only then`() {
        val peerIdentity = DeviceIdentity.load(MemoryStore())
        val known = KnownDevices(MemoryStore())
        val waiting = java.util.concurrent.ArrayBlockingQueue<SyncServer.PendingPeer>(4)
        val server = startServer(
            code = "ABCD",
            identity = DeviceIdentity.load(MemoryStore()),
            known = known,
        )
        server.onPairingRequested = { waiting.offer(it) }

        val peer = connect(server).also { it.identity = peerIdentity }
        peer.startPairing("ABCD")
        val pending = waiting.poll(2, TimeUnit.SECONDS)
        assertNotNull("a stranger must be asked about", pending)
        peer.sendSignedJoin("ABCD")

        assertTrue(
            "nothing may be remembered before a person decides",
            known.all().isEmpty(),
        )

        server.approvePeer(pending!!.id)
        assertNotNull("approval admits", peer.nextSealed("init_state"))
        assertTrue(
            "and writes the device down for next time",
            known.isKnown(peerIdentity.fingerprint),
        )
    }

    @Test
    fun `either user may approve first`() {
        // Both prompts appear at the same moment and either person may tap
        // first. The join used to be *refused* when it arrived before the
        // host's approval, so whenever the joining user was quicker — a coin
        // toss on two phones in a booth — the connection closed before the host
        // had decided, and the host's approval then appeared to do nothing.
        val server = startServer(code = "ABCD")

        // Joining device first.
        val eager = pair(server, "ABCD")
        eager.sendSealed(JSONObject().put("type", "join").put("roomCode", "ABCD"))
        assertNull("nothing before the host answers", eager.nextSealed("init_state", 400))
        server.approvePeer(eager.peerId!!)
        assertNotNull("the host's approval must complete it", eager.nextSealed("init_state"))

        // Host first, which was the only order that used to work.
        val patient = pair(server, "ABCD")
        server.approvePeer(patient.peerId!!)
        patient.sendSealed(JSONObject().put("type", "join").put("roomCode", "ABCD"))
        assertNotNull("and this order must still work", patient.nextSealed("init_state"))
    }

    @Test
    fun `a device the host has not approved reaches nothing`() {
        // The approval is not decoration on top of the key exchange; it is the
        // thing the key exchange cannot do for itself. A device that agrees a
        // perfectly good key and is never approved must stay outside.
        val server = startServer(code = "ABCD")
        val acted = ArrayBlockingQueue<Pair<String, JSONObject>>(4)
        server.onEvent = { event, payload -> acted.offer(event to payload) }

        val peer = pair(server, "ABCD")
        peer.sendSealed(JSONObject().put("type", "join").put("roomCode", "ABCD"))

        // Waiting, not refused — the host may still be looking at the digits.
        // What matters is that waiting admits nothing.
        assertNull("an unapproved device must not be given the room", peer.nextSealed("init_state", 800))

        peer.sendSealed(
            JSONObject()
                .put("type", "trigger_event")
                .put("event", "play_sampler_pad")
                .put("payload", JSONObject().put("padId", 3)),
        )
        assertNull("nor reach the engine", acted.poll(1, TimeUnit.SECONDS))
    }

    @Test
    fun `both devices show the same pairing code`() {
        // The property the whole scheme rests on: the digits agree exactly when
        // the two devices agreed a key with each other. `pair` asserts it for a
        // good exchange; this states it as the test it is.
        val server = startServer(code = "ABCD")
        val peer = pair(server, "ABCD")
        assertEquals(RoomCrypto.SAS_DIGITS, peer.pairingCode!!.length)
    }

    @Test
    fun `a wrong room code is refused rather than ignored`() {
        val server = startServer(code = "ABCD")
        // The code is mixed into the key derivation, so a peer that typed it
        // wrong does not merely fail a comparison — it holds a different
        // session, and its join does not open. The refusal it gets is for the
        // code, because the host checks that first.
        val peer = pair(server, "ABCD")
        server.approvePeer(peer.peerId!!)
        peer.sendSealed(JSONObject().put("type", "join").put("roomCode", "WXYZ"))

        val refusal = peer.nextOfType("join_refused")
        assertNotNull("a peer that typed the code wrong must be told", refusal)
        assertNull(
            "and must not be handed the room it was refused from",
            peer.nextSealed("init_state", 500),
        )
    }

    @Test
    fun `a correct room code is accepted, whatever its case`() {
        val server = startServer(code = "ABCD")
        val peer = pair(server, "abcd")
        server.approvePeer(peer.peerId!!)
        peer.sendSealed(JSONObject().put("type", "join").put("roomCode", "abcd"))

        // Asserting only the absence of a refusal would also pass against a
        // server that ignores `join` entirely — which is what this one used to
        // do. The room coming back is the evidence.
        assertNotNull("a matching code must not be refused", peer.nextSealed("init_state"))
    }

    @Test
    fun `nothing the room sends is readable on the wire`() {
        // Everything after the handshake is sealed. A listener on the network
        // sees the two public keys and then frames it cannot open — not
        // crossfader positions, not track ids, not what anybody is playing.
        val server = startServer()
        val peer = join(server)
        peer.nextSealed("init_state")

        server.updateState(JSONObject().put("crossfader", 40))

        val envelope = peer.next()
        assertNotNull(envelope)
        assertEquals("sealed", envelope!!.optString("type"))
        assertTrue(
            "the room state must not be legible in the frame",
            !envelope.toString().contains("crossfader"),
        )
    }

    @Test
    fun `the roster follows connections`() {
        val server = startServer()
        val counts = ArrayBlockingQueue<Int>(8)
        server.onPeersChanged = { counts.offer(it) }

        val peer = join(server)
        peer.nextSealed("init_state")
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
    fun `the discovery port is listening before start returns`() {
        // The race that made the test above fail on CI and pass everywhere
        // else. The discovery socket used to be bound *inside* the thread that
        // reads it, so `start` could return true with nothing yet listening —
        // and UDP does not queue for an unbound port, it drops. A device that
        // broadcast in that window got silence, and one-click connection failed
        // for no reason anyone could see.
        //
        // Asserting on the flag rather than by sending a packet and hoping:
        // this is about what is true at the instant `start` returns.
        val discoveryPort = freePort()
        val instance = SyncServer(port = freePort(), discoveryPort = discoveryPort)
        assertTrue(instance.start("TEST"))
        server = instance

        assertTrue("start returned before the discovery port was bound", instance.isDiscoverable)
        // Bound means taken: a second bind of the same port must now fail.
        assertFalse(
            "the discovery port was not actually held",
            runCatching { DatagramSocket(discoveryPort).close() }.isSuccess,
        )
    }

    @Test
    fun `a busy discovery port still hosts, and says it is not discoverable`() {
        // Hosting and being findable are different things. A port clash used to
        // fail silently inside the discovery thread, so hosting reported success
        // and was quietly undiscoverable — the worst of both, since the symptom
        // appears on somebody else's device.
        val discoveryPort = freePort()
        DatagramSocket(discoveryPort).use {
            val instance = SyncServer(port = freePort(), discoveryPort = discoveryPort)
            assertTrue("hosting should still work", instance.start("TEST"))
            server = instance
            assertFalse(instance.isDiscoverable)
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
