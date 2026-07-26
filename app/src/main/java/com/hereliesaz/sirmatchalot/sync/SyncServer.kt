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
) {
    /** Called when the room's state changes, so the host's own UI can follow. */
    var onStateChanged: ((JSONObject) -> Unit)? = null

    /** Called when a client sends an event, so the host acts on it too. */
    var onEvent: ((event: String, payload: JSONObject) -> Unit)? = null

    /** Called when the roster changes. */
    var onPeersChanged: ((Int) -> Unit)? = null

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

    /** Room code peers must present to join. */
    var roomCode: String = ""
        private set

    val isRunning: Boolean get() = running.get()

    val peerCount: Int get() = clients.size

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
        running.set(true)

        Thread({ acceptLoop(socket) }, "SirMatchALot-SyncAccept").apply {
            isDaemon = true
            start()
        }
        Thread({ discoveryLoop() }, "SirMatchALot-Discovery").apply {
            isDaemon = true
            start()
        }
        return true
    }

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
    private fun discoveryLoop() {
        val socket = runCatching {
            DatagramSocket(discoveryPort).apply { broadcast = true }
        }.getOrNull() ?: return
        discoverySocket = socket

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
            onPeersChanged?.invoke(clients.size)
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

        fun send(text: String) {
            runCatching { synchronized(output) { WebSocketProtocol.sendText(output, text) } }
                .onFailure { close() }
        }

        fun run() {
            try {
                if (!handshake()) return
                // A joiner needs the room as it stands, not only what changes
                // after it arrives.
                send(
                    JSONObject().apply {
                        put("type", "init_state")
                        put("roomState", roomState)
                    }.toString(),
                )
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

        private fun handle(text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "join" -> {
                    // A wrong code is refused rather than ignored, so a peer that
                    // typed it wrong learns that rather than sitting in silence.
                    if (roomCode.isNotEmpty() &&
                        !message.optString("roomCode").equals(roomCode, ignoreCase = true)
                    ) {
                        send(
                            JSONObject().apply {
                                put("type", "join_refused")
                                put("reason", "room code does not match")
                            }.toString(),
                        )
                        close()
                    }
                }
                "update_state" -> {
                    val state = message.optJSONObject("state") ?: return
                    updateState(state)
                }
                "trigger_event" -> {
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
