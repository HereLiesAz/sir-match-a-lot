package com.hereliesaz.sirmatchalot.sync

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The device that hosts a room.
 *
 * `SyncClient` has always broadcast `SIR_MATCH_A_LOT_DISCOVER` on UDP 8888 and
 * waited for a server to answer with a WebSocket URL. Nothing ever answered:
 * `docs/REQUIREMENTS.md` recorded H2 as "requires a device to host, which does
 * not exist yet". This is that device.
 *
 * One phone hosts and the others join it, which is what makes "one-click
 * auto-connect on the same Wi-Fi" possible without any infrastructure — no
 * server to run, no account, nothing outside the room.
 *
 * ## The two halves
 *
 * A **discovery responder** on UDP 8888 answers the broadcast with this host's
 * address, so joining is a tap rather than typing an IP.
 *
 * A **WebSocket server** on [port] carries the room. It keeps the authoritative
 * room state: a client's update is merged and then broadcast to everyone,
 * including back to the sender, so every device converges on the same values
 * rather than each believing its own. That is the difference between devices
 * that are linked and devices that merely both received a message.
 *
 * Threads, not coroutines: a blocking `accept` loop and one reader thread per
 * client is the shape the socket API actually has, and a room is a handful of
 * devices, not a server workload.
 */
class SyncServer(
    val port: Int = DEFAULT_PORT,
    private val discoveryPort: Int = DISCOVERY_PORT,
    /**
     * This host's long-term identity, or null to pair afresh every time.
     *
     * Null is the honest default for a constructor used by tests: without an
     * identity nothing is signed, nothing can be recognised, and every
     * connection is a stranger — which is exactly the behaviour that existed
     * before this parameter did.
     */
    private val identity: DeviceIdentity? = null,
    /** Devices this host has paired with before, or null to remember none. */
    private val known: KnownDevices? = null,
) {
    /** Called when the room's state changes, so the host's own UI can follow. */
    var onStateChanged: ((JSONObject) -> Unit)? = null

    /** Called when a client sends an event, so the host acts on it too. */
    var onEvent: ((event: String, payload: JSONObject) -> Unit)? = null

    /** Called when the roster changes. */
    var onPeersChanged: ((Int) -> Unit)? = null

    /**
     * Called when a device has agreed a key and wants in.
     *
     * The host's user is expected to compare [PendingPeer.pairingCode] against
     * the digits on the other device's screen and then call [approvePeer] or
     * [refusePeer]. Nothing admits itself: a connection that is never approved
     * reaches nothing, and one that is refused is told so and closed.
     */
    var onPairingRequested: ((PendingPeer) -> Unit)? = null

    /**
     * Called when a device that has paired before rejoins, having proved it.
     *
     * For telling the user it happened, not for asking them: this is the case
     * where no dialog appears, and a room that silently admits a device without
     * ever saying so is a room you cannot audit.
     */
    var onKnownPeerRejoined: ((String) -> Unit)? = null

    /**
     * Called when a pending prompt no longer needs an answer.
     *
     * Raised with the peer's id when a device turns out to be one this host
     * already knows — the prompt went up on the exchange, and the proof arrives
     * with the join a moment later.
     */
    var onPairingWithdrawn: ((String) -> Unit)? = null

    /**
     * A device waiting at the door.
     *
     * @param pairingCode the six digits both users compare. Equal on the two
     *   screens exactly when the two devices agreed a key with each other and
     *   nothing sits between them.
     */
    data class PendingPeer(val id: String, val name: String, val pairingCode: String)

    /** Admits the peer with [id], if it is still connected. */
    fun approvePeer(id: String) {
        clients.firstOrNull { it.id == id }?.approve()
    }

    /** Turns away the peer with [id], telling it why. */
    fun refusePeer(id: String, reason: String = "the host did not approve this device") {
        clients.firstOrNull { it.id == id }?.refuse(reason)
    }

    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null

    /**
     * The room's authoritative state.
     *
     * Held here rather than on whichever device happens to have spoken last,
     * because two devices that each trust their own copy are not one instrument.
     */
    private val roomState = JSONObject()

    /**
     * Room code peers must present to join.
     *
     * Still not a credential on its own — it travels in the clear on the
     * discovery reply, so anyone on the network can read it. It is one of three
     * things a peer needs, and the weakest: it names the room, the key exchange
     * proves who agreed it, and the host's user pressing approve on six digits
     * is what actually admits anybody. It is also mixed into the key
     * derivation, so two devices holding different codes cannot reach the same
     * session even if their exchange otherwise succeeds.
     */
    var roomCode: String = ""
        private set

    val isRunning: Boolean get() = running.get()

    // Raw accepted sockets, not approved peers. Anyone on the network can open
    // a TCP connection to this port for free, before any pairing has
    // happened — counting them here would let an attacker inflate the "N
    // devices joined" readout with connections that joined nothing.
    val peerCount: Int get() = clients.count { it.isJoined }

    /** This host's address on the local network, or null when not on one. */
    fun hostAddress(): String? = localAddress()

    /** The URL a peer should connect to, or null when not hosting. */
    fun websocketUrl(): String? = hostAddress()?.let { "ws://$it:$port" }

    /**
     * Starts hosting under [code].
     *
     * @return true if hosting started, false when a socket could not be bound —
     *   most often because another instance already holds the port.
     */
    fun start(code: String): Boolean {
        if (running.get()) return true
        roomCode = code.uppercase()

        val socket = runCatching { ServerSocket(port) }.getOrNull() ?: return false
        serverSocket = socket

        // Bound here, before returning, rather than inside the thread that
        // reads it.
        //
        // Binding in the thread meant `start` returned true while nothing was
        // yet listening on the discovery port, and UDP does not queue for an
        // unbound port — it drops. So a device that broadcast in that window got
        // silence, and "one-click auto-connect" failed for no reason the user
        // could see or act on. The window is short, which makes it worse: it
        // fails occasionally, on a loaded device, and looks like a flaky network.
        //
        // The same race is why the discovery test failed on CI while passing on
        // every developer machine.
        val discovery = runCatching {
            DatagramSocket(discoveryPort).apply { broadcast = true }
        }.getOrNull()
        discoverySocket = discovery
        running.set(true)

        Thread({ acceptLoop(socket) }, "SirMatchALot-SyncAccept").apply {
            isDaemon = true
            start()
        }
        if (discovery != null) {
            Thread({ discoveryLoop(discovery) }, "SirMatchALot-Discovery").apply {
                isDaemon = true
                start()
            }
        }
        return true
    }

    /**
     * Whether other devices can *find* this room, as opposed to join it.
     *
     * False when the discovery port was already taken — by another copy of this
     * app, or anything else on 8888. Hosting still works and a peer that is told
     * the address can still connect, so this is not a failure to host; it is a
     * failure to be found, and the difference is worth reporting rather than
     * swallowing. It used to be swallowed entirely: the bind happened inside a
     * thread whose failure path was `return`, so hosting reported success and
     * was quietly undiscoverable.
     */
    val isDiscoverable: Boolean get() = discoverySocket != null

    fun stop() {
        if (!running.getAndSet(false)) return
        clients.forEach { it.close() }
        clients.clear()
        runCatching { serverSocket?.close() }
        runCatching { discoverySocket?.close() }
        serverSocket = null
        discoverySocket = null
        onPeersChanged?.invoke(0)
    }

    // --- Discovery ---

    /**
     * Answers discovery broadcasts.
     *
     * The reply carries this host's address and WebSocket URL in the shape
     * `SyncClient.startLanDiscovery` already parses — `serverIp` and `wsUrl` —
     * because the client half was written against a server that was never
     * built, and the sensible thing is to match it rather than change both.
     */
    private fun discoveryLoop(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val message = String(packet.data, 0, packet.length).trim()
                if (message != DISCOVERY_REQUEST) continue

                val reply = JSONObject().apply {
                    put("serverIp", hostAddress() ?: continue)
                    put("wsUrl", websocketUrl() ?: continue)
                    put("roomCode", roomCode)
                }.toString().toByteArray()

                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            } catch (e: Exception) {
                if (!running.get()) return
            }
        }
    }

    // --- Connections ---

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val accepted = runCatching { socket.accept() }.getOrNull() ?: continue
            val connection = ClientConnection(accepted)
            clients.add(connection)
            // Not yet counted in onPeersChanged: an accepted socket has not
            // joined anything until admitIfReady() says so.
            Thread({ connection.run() }, "SirMatchALot-SyncPeer").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Merges [update] into the room state and tells everyone.
     *
     * Shallow merge by design: a client sending `{"crossfader": 40}` must not
     * erase the decks, and a client sending a whole deck must replace that deck
     * wholesale rather than having its fields merged one at a time with stale
     * ones.
     */
    fun updateState(update: JSONObject, broadcast: Boolean = true) {
        for (key in update.keys()) roomState.put(key, update.get(key))
        onStateChanged?.invoke(roomState)
        if (broadcast) {
            send(
                JSONObject().apply {
                    put("type", "state_synced")
                    put("state", roomState)
                }.toString(),
            )
        }
    }

    /** Relays an event to every peer. */
    fun broadcastEvent(event: String, payload: JSONObject) {
        send(
            JSONObject().apply {
                put("type", "event_triggered")
                put("event", event)
                put("payload", payload)
            }.toString(),
        )
    }

    private fun send(text: String) {
        for (client in clients) client.send(text)
    }

    private inner class ClientConnection(private val socket: Socket) {
        private val output = socket.getOutputStream()
        private val input = socket.getInputStream().buffered()

        /**
         * Whether this peer has completed a `join`.
         *
         * Nothing recorded this before, and nothing outside the `join` branch
         * consulted the room code — so a peer that simply never sent `join` was
         * never checked at all, and could load tracks, seek decks and drive the
         * filter pad on somebody else's instrument from anywhere on the Wi-Fi.
         */
        @Volatile
        private var joined = false

        /** Whether this peer has completed a `join` — see [joined]. */
        val isJoined: Boolean get() = joined

        /** This connection's own ephemeral keys, never reused. */
        private val keys = RoomCrypto.newKeyPair()

        /** Agreed once `hello` arrives. Null before that, and after a failure. */
        @Volatile
        private var session: RoomCrypto.Session? = null

        /** Set when this device's own user approves the pairing. */
        @Volatile
        private var approvedByHost = false

        /** Set when a valid sealed `join` has arrived, approved or not yet. */
        @Volatile
        private var pendingJoin = false

        /** The peer's identity fingerprint, once it has *proved* one. */
        @Volatile
        private var peerFingerprint: String? = null

        /** What both identities sign, kept from the exchange until the join. */
        @Volatile
        private var signedTranscript: ByteArray? = null

        /** Set when the prompt is being held back pending a claimed identity. */
        @Volatile
        private var promptDeferred = false

        /** What the roster calls this peer, and what the approval prompt shows. */
        @Volatile
        private var peerName: String = "A device"

        val id: String = java.util.UUID.randomUUID().toString()

        /** The six digits this connection's user is asked to compare, if any. */
        val pairingCode: String? get() = session?.sas

        /**
         * The host's user approved this peer.
         *
         * Approval alone admits nothing: the peer still has to present a `join`
         * sealed under the agreed key and carrying the right room code, which is
         * what ties the approval to the device the digits were shown for.
         */
        fun approve() {
            approvedByHost = true
            admitIfReady()
        }

        /**
         * Admits the peer once both halves have arrived, in either order.
         *
         * The two halves are this host's user approving and the peer's sealed
         * join, and nothing orders them: they are a person tapping a dialog and
         * a message crossing a network.
         */
        @Synchronized
        private fun admitIfReady() {
            if (joined || !pendingJoin || !approvedByHost) return
            joined = true
            onPeersChanged?.invoke(clients.count { it.isJoined })

            // Remembered here rather than in `approve`, because the fingerprint
            // is only known once the join has arrived and its signature has
            // verified — and the host's user may well tap approve first. Doing
            // it on approval meant that in that ordering, which is a coin toss,
            // the device was admitted and then forgotten, and turned up as a
            // stranger the next time. Only a *proved* identity is written down:
            // `recogniseIdentity` sets the fingerprint after checking the
            // signature and never before.
            peerFingerprint?.let { known?.remember(it, peerName) }
            // A joiner needs the room as it stands, not only what changes after
            // it arrives.
            send(
                JSONObject().apply {
                    put("type", "init_state")
                    put("roomState", roomState)
                }.toString(),
            )
        }

        fun refuse(reason: String) {
            sendPlain(
                JSONObject().apply {
                    put("type", "join_refused")
                    put("reason", reason)
                }.toString(),
            )
            close()
        }

        /** Sealed under the session, which is the only way anything is sent. */
        fun send(text: String) {
            val current = session ?: return
            val sealed = current.seal(text.toByteArray(Charsets.UTF_8))
            sendPlain(
                JSONObject().apply {
                    put("type", "sealed")
                    put("data", WebSocketProtocol.base64(sealed))
                }.toString(),
            )
        }

        /**
         * Unencrypted, for the handshake and for refusals.
         *
         * Exactly three messages travel this way — `hello_ack`, `join_refused`
         * and nothing else — and none of them says anything a listener does not
         * already know: the host's ephemeral public key, and that somebody was
         * turned away.
         */
        private fun sendPlain(text: String) {
            runCatching { synchronized(output) { WebSocketProtocol.sendText(output, text) } }
                .onFailure { close() }
        }

        fun run() {
            try {
                if (!handshake()) return
                // The room state used to go out here, before any `join` could
                // have been sent — so refusing a join was a courtesy message
                // attached to a connection that had already been handed
                // everything. It now waits for the join.
                readLoop()
            } catch (e: Exception) {
                // A peer disconnecting is ordinary.
            } finally {
                close()
            }
        }

        private fun handshake(): Boolean {
            val request = readHttpRequest() ?: return false
            val headers = WebSocketProtocol.parseHeaders(request)
            if (!WebSocketProtocol.isUpgrade(headers)) {
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                output.flush()
                return false
            }
            val key = headers.getValue("sec-websocket-key")
            output.write(WebSocketProtocol.handshakeResponse(key).toByteArray())
            output.flush()
            return true
        }

        private fun readHttpRequest(): String? {
            val builder = StringBuilder()
            while (!builder.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) return null
                builder.append(byte.toChar())
                if (builder.length > MAX_REQUEST_BYTES) return null
            }
            return builder.toString()
        }

        private fun readLoop() {
            while (running.get()) {
                val frame = WebSocketProtocol.decode(input) ?: return
                when (frame.opcode) {
                    WebSocketProtocol.OPCODE_TEXT -> handle(frame.text)
                    WebSocketProtocol.OPCODE_PING ->
                        synchronized(output) {
                            output.write(
                                WebSocketProtocol.encode(WebSocketProtocol.OPCODE_PONG, frame.payload),
                            )
                            output.flush()
                        }
                    WebSocketProtocol.OPCODE_CLOSE -> return
                }
            }
        }

        /**
         * Everything arriving on this connection.
         *
         * Exactly one message is accepted in the clear — `hello`, which carries
         * the peer's ephemeral public key. After that the only thing this reads
         * is `sealed`, and what is inside a sealed frame is the protocol as it
         * always was.
         */
        private fun handle(text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "hello" -> beginPairing(message)
                "sealed" -> {
                    val current = session ?: return
                    val bytes = runCatching {
                        WebSocketProtocol.decodeBase64(message.optString("data"))
                    }.getOrNull() ?: return
                    val opened = current.open(bytes) ?: return
                    handleSealed(opened.toString(Charsets.UTF_8))
                }
            }
        }

        /**
         * Answers `hello`: agrees a key, and asks this device's user about it.
         *
         * The reply goes out before either user has approved anything, and has
         * to: until the peer has this host's public key it cannot derive the
         * code, and until it can derive the code there is nothing for its user
         * to compare. What the reply does not do is admit anybody.
         */
        private fun beginPairing(message: JSONObject) {
            if (session != null) return
            val peerKeyBytes = WebSocketProtocol.decodeBase64(message.optString("key")) ?: return
            val peerKey = RoomCrypto.decodePublicKey(peerKeyBytes) ?: return
            val agreed = RoomCrypto.agree(keys, peerKey, roomCode) ?: return

            peerName = message.optString("name").ifBlank { "A device" }
            session = agreed

            val transcript = RoomCrypto.transcriptOf(
                RoomCrypto.encodePublicKey(keys.public),
                peerKeyBytes,
                roomCode,
            )

            sendPlain(
                JSONObject().apply {
                    put("type", "hello_ack")
                    put("key", WebSocketProtocol.base64(RoomCrypto.encodePublicKey(keys.public)))
                    identity?.let {
                        put("identity", WebSocketProtocol.base64(it.publicKey.encoded))
                        put("signature", WebSocketProtocol.base64(it.sign(transcript)))
                    }
                }.toString(),
            )

            signedTranscript = transcript

            // The prompt goes up now, on the exchange, so both users are looking
            // at the same digits at the same moment — *unless* this device
            // claims an identity this host already knows, in which case it waits
            // for the join to make good on the claim.
            //
            // Waiting rather than raising-and-withdrawing, because a dialog that
            // appears for a third of a second and vanishes is worse than either:
            // it is read as a glitch, and the one time it matters — a device
            // whose claim does not check out — it has already trained the user
            // to ignore it. A claim that fails simply gets the prompt a moment
            // later, which is the same prompt a stranger gets.
            if (claimsKnownIdentity(message)) {
                promptDeferred = true
                return
            }
            onPairingRequested?.invoke(PendingPeer(id, peerName, agreed.sas))
        }

        /**
         * Whether this peer says it is a device this host remembers.
         *
         * A claim and nothing more — no signature has been seen at this point,
         * and none could have been: the peer cannot sign the transcript until
         * `hello_ack` gives it this host's ephemeral key. So this decides only
         * whether to *wait* before asking the user, never whether to admit.
         */
        private fun claimsKnownIdentity(message: JSONObject): Boolean {
            val trusted = known ?: return false
            val identityBytes =
                WebSocketProtocol.decodeBase64(message.optString("identity")) ?: return false
            val key = DeviceIdentity.decodePublicKey(identityBytes) ?: return false
            return trusted.isKnown(DeviceIdentity.fingerprintOf(key))
        }

        /**
         * Checks the proof in a `join`, and says whether it is one we know.
         *
         * Recognition happens here rather than at `hello` for a plain reason:
         * the peer cannot sign the transcript until it has this host's
         * ephemeral key, which `hello_ack` is what gives it. So the identity
         * arrives with the hello and the signature with the join.
         *
         * The signature is verified *first*, and the fingerprint is only
         * recorded once it has. Recording a fingerprint the peer merely claimed
         * would mean that approving an impostor writes down somebody else's
         * name — and the fingerprint is public, since it is on the wire every
         * time that device connects. Trusting a claim to one would make "we have
         * paired before" mean "anyone who has watched us pair before".
         */
        private fun recogniseIdentity(message: JSONObject): String? {
            val transcript = signedTranscript ?: return null
            val identityBytes =
                WebSocketProtocol.decodeBase64(message.optString("identity")) ?: return null
            val signature =
                WebSocketProtocol.decodeBase64(message.optString("signature")) ?: return null
            val key = DeviceIdentity.decodePublicKey(identityBytes) ?: return null
            if (!DeviceIdentity.verify(key, transcript, signature)) return null

            val fingerprint = DeviceIdentity.fingerprintOf(key)
            peerFingerprint = fingerprint
            val trusted = known ?: return null
            if (!trusted.isKnown(fingerprint)) return null
            return trusted.all()[fingerprint] ?: peerName
        }

        private fun handleSealed(text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "join" -> {
                    // Three things have to hold, and each covers what the others
                    // cannot: the frame opened, so it came from whoever agreed
                    // this key; the code matches, so it is this room; and this
                    // device's own user pressed approve on the digits, so it is
                    // the device they meant.
                    if (roomCode.isNotEmpty() &&
                        !message.optString("roomCode").equals(roomCode, ignoreCase = true)
                    ) {
                        refuse("room code does not match")
                        return
                    }
                    // Held, not refused, when the host has not answered yet.
                    //
                    // Both prompts appear at the same moment and either user may
                    // tap first. Refusing here — which is what this did — meant
                    // that whenever the joining user was the quicker of the two,
                    // the connection was closed before the host had even
                    // decided. That is not a rare ordering; on two phones in a
                    // booth it is a coin toss, and the failure looks like the
                    // host's approval doing nothing.
                    pendingJoin = true

                    // A device this host has paired with before, and which has
                    // just proved it, is let in without anybody being asked —
                    // and its prompt is taken back down, because a dialog for a
                    // decision that has already been made is a dialog whose
                    // buttons do nothing.
                    val recognised = recogniseIdentity(message)
                    if (recognised != null && !approvedByHost) {
                        approvedByHost = true
                        onPairingWithdrawn?.invoke(id)
                        onKnownPeerRejoined?.invoke(recognised)
                    } else if (recognised == null && promptDeferred) {
                        // It claimed to be somebody we know and could not prove
                        // it. That is exactly a stranger, and gets what a
                        // stranger gets.
                        promptDeferred = false
                        session?.let { onPairingRequested?.invoke(PendingPeer(id, peerName, it.sas)) }
                    }
                    admitIfReady()
                }
                "update_state" -> {
                    if (!joined) return
                    val state = message.optJSONObject("state") ?: return
                    updateState(state)
                }
                "trigger_event" -> {
                    if (!joined) return
                    val event = message.optString("event")
                    val payload = message.optJSONObject("payload") ?: JSONObject()
                    onEvent?.invoke(event, payload)
                    broadcastEvent(event, payload)
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
            if (clients.remove(this)) onPeersChanged?.invoke(clients.size)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8890
        const val DISCOVERY_PORT = 8888
        const val DISCOVERY_REQUEST = "SIR_MATCH_A_LOT_DISCOVER"
        private const val MAX_REQUEST_BYTES = 8 * 1024

        /**
         * This device's address on the local network.
         *
         * Loopback is skipped — a peer cannot reach 127.0.0.1 — and so is IPv6,
         * because the URL is shown to a person and an IPv6 literal in a
         * `ws://[…]` URL is not something anyone should have to read out.
         */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        }.getOrNull()

        /** Room codes people read aloud: no ambiguous characters. */
        fun generateRoomCode(random: kotlin.random.Random = kotlin.random.Random.Default): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
