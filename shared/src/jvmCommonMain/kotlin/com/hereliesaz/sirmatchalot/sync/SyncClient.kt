package com.hereliesaz.sirmatchalot.sync

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SyncClient(
    private val listener: SyncListener,
    /** This device's long-term identity, or null to be a stranger every time. */
    private val identity: DeviceIdentity? = null,
    /** Hosts this device has paired with before, or null to remember none. */
    private val known: KnownDevices? = null,
) {

    interface SyncListener {
        fun onServerDiscovered(serverIp: String, wsUrl: String)
        fun onConnected()
        fun onDisconnected()

        /** The room turned this device away — a wrong code, or no code at all. */
        fun onJoinRefused(reason: String)

        /**
         * A key has been agreed with the host and both users must now check it.
         *
         * [pairingCode] is shown on this device and, at the same moment, on the
         * host's. Identical digits mean the two devices agreed a key with each
         * other and nothing is sitting between them; a device in the middle
         * holds two different exchanges and cannot make both screens match.
         *
         * Nothing is sent to the room until [approvePairing] is called.
         */
        fun onPairingCode(pairingCode: String)

        /**
         * A host this device has paired with before, and which proved it,
         * accepted the connection without asking anybody.
         *
         * For telling the user, not for asking them — a device that silently
         * joins a room without ever saying so is one you cannot audit.
         */
        fun onKnownHostRejoined(name: String)
        fun onRoomStateReceived(json: JSONObject)
        fun onKaossMoveEvent(x: Float, y: Float, padId: Int)
        fun onSamplerTriggerEvent(padId: Int)
        fun onAutoSyncEvent()
        fun onLoadTrackEvent(deck: String, trackId: String)
        fun onSeekEvent(deck: String, time: Float)
        fun onNudgeEvent(deck: String, direction: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    /** This connection's ephemeral keys, made fresh for every connect. */
    private var keys: java.security.KeyPair? = null

    /** Agreed when `hello_ack` arrives; null until then. */
    @Volatile
    private var session: RoomCrypto.Session? = null

    /** Set when this device's own user approves the digits. */
    @Volatile
    private var approvedByUser = false

    /** The room code this device is trying to join, for the key derivation. */
    @Volatile
    private var pendingRoomCode: String = ""

    /** What this device joins as, once its user — or its memory — approves. */
    @Volatile
    private var pendingRole: String = ""

    @Volatile
    private var pendingName: String = "A device"

    /** This exchange's transcript, which both identities sign. */
    @Volatile
    private var transcript: ByteArray? = null

    /** The host's identity fingerprint, once it has presented a verifiable one. */
    @Volatile
    private var hostFingerprint: String? = null

    /** The remembered name of this host, if it proved a known identity. */
    private fun recogniseHost(message: JSONObject): String? {
        val trusted = known ?: return null
        val signed = transcript ?: return null
        val identityBytes =
            WebSocketProtocol.decodeBase64(message.optString("identity")) ?: return null
        val signature =
            WebSocketProtocol.decodeBase64(message.optString("signature")) ?: return null
        val key = DeviceIdentity.decodePublicKey(identityBytes) ?: return null

        // The fingerprint is derived but not yet trusted: it is only assigned
        // to `hostFingerprint` — and so only eligible to be remembered by
        // `approvePairing` — once the signature over this exchange's
        // transcript has actually verified. Setting it any earlier would let
        // an unverifiable identity be written down as if it had proved
        // itself.
        val candidateFingerprint = DeviceIdentity.fingerprintOf(key)
        if (!DeviceIdentity.verify(key, signed, signature)) return null
        hostFingerprint = candidateFingerprint
        if (!trusted.isKnown(candidateFingerprint)) return null
        return trusted.all()[candidateFingerprint] ?: "the host"
    }

    /**
     * This device's user approves the pairing.
     *
     * Until this is called nothing at all is sent to the room, which is the
     * whole point: the host's approval says the host recognises this device, and
     * this says this device recognises the host. Either one alone leaves a
     * device in the middle somewhere to stand.
     */
    fun approvePairing(roomCode: String, role: String, name: String) {
        if (session == null) return
        approvedByUser = true
        // Remembered on approval, and only for a host whose signature has
        // already been checked — `recogniseHost` sets the fingerprint after
        // verifying, never before, so an unverifiable identity is not written
        // down and cannot be trusted next time.
        hostFingerprint?.let { known?.remember(it, "the host") }
        sendSealed(
            JSONObject().apply {
                put("type", "join")
                put("roomCode", roomCode.uppercase())
                put("role", role)
                put("name", name)
                // This device's proof, inside the sealed frame: the identity it
                // claims, and a signature over this exchange. The host checks it
                // against what it remembers before letting the join through
                // without asking anybody.
                val signer = identity
                val signed = transcript
                if (signer != null && signed != null) {
                    put("identity", WebSocketProtocol.base64(signer.publicKey.encoded))
                    put("signature", WebSocketProtocol.base64(signer.sign(signed)))
                }
            },
        )
    }

    /** This device's user rejects the pairing, and the connection with it. */
    fun refusePairing() {
        approvedByUser = false
        session = null
        disconnect()
    }

    /**
     * Sends [message] sealed under the agreed key.
     *
     * Every room message goes this way. A message sent before a key is agreed,
     * or before this device's user has approved it, is dropped rather than sent
     * in the clear — there is no path here that falls back to plaintext, because
     * a fallback is what an attacker would arrange.
     */
    private fun sendSealed(message: JSONObject) {
        val current = session ?: return
        val sealed = current.seal(message.toString().toByteArray(Charsets.UTF_8))
        webSocket?.send(
            JSONObject().apply {
                put("type", "sealed")
                put("data", WebSocketProtocol.base64(sealed))
            }.toString(),
        )
    }
    private var udpSocket: DatagramSocket? = null
    private val isDiscovering = AtomicBoolean(false)

    fun startLanDiscovery() {
        if (isDiscovering.get()) return
        isDiscovering.set(true)

        Thread {
            try {
                udpSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 4000
                }
                
                val discoverMsg = "SIR_MATCH_A_LOT_DISCOVER"
                val sendBuffer = discoverMsg.toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(sendBuffer, sendBuffer.size, broadcastAddress, 8888)

                var attempts = 0
                val receiveBuffer = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

                while (isDiscovering.get() && attempts < 10) {
                    attempts++
                    debugLog("SyncClient", "Sending UDP broadcast query attempt $attempts...")
                    udpSocket?.send(sendPacket)

                    try {
                        udpSocket?.receive(receivePacket)
                        val responseText = String(receivePacket.data, 0, receivePacket.length)
                        debugLog("SyncClient", "UDP response received: $responseText")
                        
                        val json = JSONObject(responseText)
                        val serverIp = json.getString("serverIp")
                        val wsUrl = json.getString("wsUrl")

                        listener.onServerDiscovered(serverIp, wsUrl)
                        break
                    } catch (e: Exception) {
                        // Read timeout
                    }
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopLanDiscovery()
            }
        }.start()
    }

    fun stopLanDiscovery() {
        isDiscovering.set(false)
        udpSocket?.close()
        udpSocket = null
    }

    /**
     * Opens a connection and starts the key exchange.
     *
     * @param roomCode mixed into the derivation, so a device that typed the
     *   wrong code cannot reach the host's session even if the exchange itself
     *   succeeds — it fails as a mismatched code rather than as a wrong key.
     * @param deviceName shown on the host's approval prompt.
     */
    @JvmOverloads
    fun connect(
        wsUrl: String,
        roomCode: String = "",
        deviceName: String = "A device",
        role: String = "",
    ) {
        disconnect()

        pendingRoomCode = roomCode.uppercase()
        pendingName = deviceName
        pendingRole = role
        approvedByUser = false
        session = null
        transcript = null
        hostFingerprint = null
        val ownKeys = RoomCrypto.newKeyPair()
        keys = ownKeys

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
                // The first thing on the wire, and the only thing this device
                // sends in the clear: an ephemeral public key nobody can do
                // anything with on its own. This device's name and its
                // long-term identity key used to ride along here too — which
                // meant anybody passively listening on the network learned
                // both on every connection attempt, approved or not. They now
                // go out sealed, once `hello_ack` gives this device a session
                // to seal them under — see the sealed `identify` sent in
                // `hello_ack`'s handler below.
                webSocket.send(
                    JSONObject().apply {
                        put("type", "hello")
                        put("key", WebSocketProtocol.base64(RoomCrypto.encodePublicKey(ownKeys.public)))
                    }.toString(),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val outer = JSONObject(text)
                    when (outer.optString("type")) {
                        "hello_ack" -> {
                            val hostKeyBytes =
                                WebSocketProtocol.decodeBase64(outer.optString("key")) ?: return
                            val hostKey = RoomCrypto.decodePublicKey(hostKeyBytes) ?: return
                            val agreed = RoomCrypto.agree(ownKeys, hostKey, pendingRoomCode) ?: return
                            session = agreed
                            transcript = RoomCrypto.transcriptOf(
                                RoomCrypto.encodePublicKey(ownKeys.public),
                                hostKeyBytes,
                                pendingRoomCode,
                            )

                            // This device's name and its claimed identity used
                            // to ride the plaintext `hello`. They go out sealed
                            // now that a session exists to seal them under —
                            // the host cannot show its approval prompt until
                            // this arrives, same as before, just one round trip
                            // later and off a plaintext wire.
                            sendSealed(
                                JSONObject().apply {
                                    put("type", "identify")
                                    put("name", pendingName)
                                    identity?.let {
                                        put("identity", WebSocketProtocol.base64(it.publicKey.encoded))
                                    }
                                },
                            )
                            // Both users now hold the same six digits — but
                            // whether to show them, or skip straight to a
                            // silent rejoin, is decided once the host's own
                            // sealed `identify` arrives (handled below via the
                            // generic "sealed" branch), not here.
                            return
                        }
                        // Sent in the clear, because a refused device may have no
                        // session to open anything with.
                        "join_refused" -> {
                            listener.onJoinRefused(
                                outer.optString("reason").ifBlank { "the room refused the join" },
                            )
                            return
                        }
                    }

                    val current = session ?: return
                    if (outer.optString("type") != "sealed") return
                    val sealed = WebSocketProtocol.decodeBase64(outer.optString("data")) ?: return
                    val opened = current.open(sealed) ?: return

                    val data = JSONObject(opened.toString(Charsets.UTF_8))
                    val type = data.optString("type")

                    when (type) {
                        // The host's identity and its signature over this
                        // exchange's transcript, sealed — see the note on the
                        // `hello` send above for why these no longer arrive
                        // with `hello_ack` in the clear.
                        "identify" -> {
                            val recognised = recogniseHost(data)
                            if (recognised != null) {
                                listener.onKnownHostRejoined(recognised)
                                approvePairing(pendingRoomCode, pendingRole, pendingName)
                            } else {
                                session?.let { listener.onPairingCode(it.sas) }
                            }
                        }
                        // The server has always sent this on a wrong room code,
                        // and nothing read it — so "a peer that typed it wrong
                        // learns that rather than sitting in silence", as the
                        // server puts it, was true of the wire and false of the
                        // app. A refused join looked exactly like a working one
                        // until nothing responded.
                        "init_state" -> {
                            val roomState = data.getJSONObject("roomState")
                            listener.onRoomStateReceived(roomState)
                        }
                        "state_synced" -> {
                            val state = data.getJSONObject("state")
                            listener.onRoomStateReceived(state)
                        }
                        "event_triggered" -> {
                            val event = data.optString("event")
                            val payload = data.optJSONObject("payload") ?: JSONObject()
                            when (event) {
                                "play_sampler_pad" -> {
                                    listener.onSamplerTriggerEvent(payload.optInt("padId"))
                                }
                                "kaoss_move" -> {
                                    listener.onKaossMoveEvent(
                                        payload.optDouble("x").toFloat(),
                                        payload.optDouble("y").toFloat(),
                                        payload.optInt("padId")
                                    )
                                }
                                "sync_click" -> {
                                    listener.onAutoSyncEvent()
                                }
                                "load_track_direct" -> {
                                    listener.onLoadTrackEvent(
                                        payload.optString("deck"),
                                        payload.optString("trackId")
                                    )
                                }
                                "nudge_deck_direct" -> {
                                    listener.onNudgeEvent(
                                        payload.optString("deck"),
                                        payload.optString("direction")
                                    )
                                }
                                // `onSeekEvent` was declared on the listener and
                                // implemented, but no message ever reached it —
                                // half an API, which is worse than none, because
                                // it reads as working.
                                "seek_deck" -> {
                                    // `optDouble` returns NaN for a missing or
                                    // non-numeric key, and a NaN reaches
                                    // `Deck.seekToSeconds`, where every bound
                                    // check is false and the playhead is set to
                                    // NaN — a deck that renders silence until
                                    // something reloads it.
                                    val time = payload.optDouble("time", Double.NaN)
                                    if (!time.isNaN()) {
                                        listener.onSeekEvent(
                                            payload.optString("deck"),
                                            time.toFloat()
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                t.printStackTrace()
                listener.onDisconnected()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        stopLanDiscovery()
    }

    /**
     * Kept for the discovery flow, which knows the code before a key exists.
     *
     * The join itself is now sent by [approvePairing], because it must not go
     * out until this device's user has seen the digits — sending it on connect
     * would mean joining a host nobody had confirmed.
     */
    fun joinRoom(roomCode: String, role: String, name: String) {
        val payload = JSONObject().apply {
            put("type", "join")
            put("roomCode", roomCode.uppercase())
            put("role", role)
            put("name", name)
        }
        sendSealed(payload)
    }

    fun updateCrossfader(crossfaderVal: Int, roomCode: String) {
        val state = JSONObject().apply {
            put("crossfader", crossfaderVal)
        }
        sendUpdateState(state, roomCode)
    }

    fun updateDeck(deckName: String, isPlaying: Boolean, bpm: Int, pitch: Float, currentTime: Float, cues: List<Float?>, roomCode: String) {
        val deckKey = if (deckName == "A") "deckA" else "deckB"
        val cuesArray = JSONArray()
        cues.forEach { cuesArray.put(it ?: JSONObject.NULL) }

        val deckState = JSONObject().apply {
            put("isPlaying", isPlaying)
            put("bpm", bpm)
            put("pitch", pitch)
            put("currentTime", currentTime)
            put("cues", cuesArray)
        }
        val state = JSONObject().apply {
            put(deckKey, deckState)
        }
        sendUpdateState(state, roomCode)
    }

    fun triggerSamplerPad(padId: Int, roomCode: String) {
        val payload = JSONObject().apply {
            put("padId", padId)
        }
        sendTriggerEvent("play_sampler_pad", payload, roomCode)
    }

    fun triggerKaossMove(x: Float, y: Float, padId: Int, roomCode: String) {
        val payload = JSONObject().apply {
            put("x", x)
            put("y", y)
            put("padId", padId)
        }
        sendTriggerEvent("kaoss_move", payload, roomCode)
    }

    fun triggerAutoSync(roomCode: String) {
        sendTriggerEvent("sync_click", JSONObject(), roomCode)
    }

    fun triggerLoadTrack(deck: String, trackId: String, roomCode: String) {
        val payload = JSONObject().apply {
            put("deck", deck)
            put("trackId", trackId)
        }
        sendTriggerEvent("load_track_direct", payload, roomCode)
    }

    fun triggerNudge(deck: String, direction: String, roomCode: String) {
        val payload = JSONObject().apply {
            put("deck", deck)
            put("direction", direction)
        }
        sendTriggerEvent("nudge_deck_direct", payload, roomCode)
    }

    fun triggerSeek(deck: String, timeSeconds: Float, roomCode: String) {
        val payload = JSONObject().apply {
            put("deck", deck)
            put("time", timeSeconds.toDouble())
        }
        sendTriggerEvent("seek_deck", payload, roomCode)
    }

    private fun sendUpdateState(stateJson: JSONObject, roomCode: String) {
        val msg = JSONObject().apply {
            put("type", "update_state")
            put("roomCode", roomCode.uppercase())
            put("state", stateJson)
        }
        sendSealed(msg)
    }

    private fun sendTriggerEvent(event: String, payload: JSONObject, roomCode: String) {
        val msg = JSONObject().apply {
            put("type", "trigger_event")
            put("roomCode", roomCode.uppercase())
            put("event", event)
            put("payload", payload)
        }
        sendSealed(msg)
    }
}
