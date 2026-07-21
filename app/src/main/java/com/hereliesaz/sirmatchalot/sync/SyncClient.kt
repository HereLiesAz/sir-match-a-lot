package com.hereliesaz.sirmatchalot.sync

import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SyncClient(private val listener: SyncListener) {

    interface SyncListener {
        fun onServerDiscovered(serverIp: String, wsUrl: String)
        fun onConnected()
        fun onDisconnected()
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
                    Log.d("SyncClient", "Sending UDP broadcast query attempt $attempts...")
                    udpSocket?.send(sendPacket)

                    try {
                        udpSocket?.receive(receivePacket)
                        val responseText = String(receivePacket.data, 0, receivePacket.length)
                        Log.d("SyncClient", "UDP response received: $responseText")
                        
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

    fun connect(wsUrl: String) {
        disconnect()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    val type = data.optString("type")

                    when (type) {
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

    fun joinRoom(roomCode: String, role: String, name: String) {
        val payload = JSONObject().apply {
            put("type", "join")
            put("roomCode", roomCode.uppercase())
            put("role", role)
            put("name", name)
        }
        webSocket?.send(payload.toString())
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

    private fun sendUpdateState(stateJson: JSONObject, roomCode: String) {
        val msg = JSONObject().apply {
            put("type", "update_state")
            put("roomCode", roomCode.uppercase())
            put("state", stateJson)
        }
        webSocket?.send(msg.toString())
    }

    private fun sendTriggerEvent(event: String, payload: JSONObject, roomCode: String) {
        val msg = JSONObject().apply {
            put("type", "trigger_event")
            put("roomCode", roomCode.uppercase())
            put("event", event)
            put("payload", payload)
        }
        webSocket?.send(msg.toString())
    }
}
