package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import com.hereliesaz.sirmatchalot.sync.DeviceIdentity
import com.hereliesaz.sirmatchalot.sync.KnownDevices
import com.hereliesaz.sirmatchalot.sync.SyncClient
import com.hereliesaz.sirmatchalot.sync.SyncRole
import com.hereliesaz.sirmatchalot.sync.SyncServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Everything a desktop screen needs to host or join a room, decoupled from
 * Compose so it can be driven by a test without a display.
 *
 * This is the desktop half of what [com.hereliesaz.sirmatchalot.ui]'s
 * `SirMatchALotViewModel` does on Android for the same protocol: both wrap
 * [SyncServer]/[SyncClient] from `:shared` behind [StateFlow]s a screen can
 * observe. Nothing about the room protocol itself is duplicated — this is a
 * thin state holder over code shared between the two platforms, which is
 * the whole point of the module split.
 *
 * Local mixing (`PlaybackSession`) and the library (`DesktopLibrary`) do have
 * a desktop backend now — see `docs/ARCHITECTURE.md` — but neither is wired
 * to *this* class: `RoomSession` only carries what a room needs to exist —
 * hosting, joining, pairing, and the raw room state as JSON. Acting on that
 * state (driving `PlaybackSession` from a room's `roomState` updates) is a
 * later phase.
 */
class RoomSession(
    identityStore: KeyValueStore,
    private val deviceName: String = defaultDeviceName(),
) : SyncClient.SyncListener {

    private val deviceIdentity = DeviceIdentity.load(identityStore)

    /**
     * Devices this machine has paired with before.
     *
     * Named "for the settings list" because that is where the Android app
     * surfaces it (Settings > paired devices, with a "forget all" action).
     * The desktop app has no settings screen yet, so this list exists here
     * and is exercised by tests, but nothing in `:desktopApp`'s UI reads or
     * clears it today.
     */
    val knownDevices = KnownDevices(identityStore)

    private val syncServer = SyncServer(identity = deviceIdentity, known = knownDevices)
    private val syncClient = SyncClient(this, deviceIdentity, knownDevices)

    private val _isHosting = MutableStateFlow(false)
    val isHosting: StateFlow<Boolean> = _isHosting

    private val _isConnected = MutableStateFlow(false)
    /** True once this device has joined someone else's room as a guest. */
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _roomCode = MutableStateFlow("")
    val roomCode: StateFlow<String> = _roomCode

    private val _hostUrl = MutableStateFlow<String?>(null)
    /** This device's own websocket URL, once it is hosting. */
    val hostUrl: StateFlow<String?> = _hostUrl

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _roomState = MutableStateFlow(JSONObject())
    val roomState: StateFlow<JSONObject> = _roomState

    /**
     * Devices waiting for this machine's approval, when it is hosting.
     *
     * Emptied on [stopHosting] — a prompt for a room that no longer exists
     * has nothing behind its buttons.
     */
    private val _pendingPeers = MutableStateFlow<List<SyncServer.PendingPeer>>(emptyList())
    val pendingPeers: StateFlow<List<SyncServer.PendingPeer>> = _pendingPeers

    /**
     * The host this machine is being asked to confirm, or null.
     *
     * Both sides show the same six digits and both users must approve — see
     * [SyncClient.approvePairing]'s doc for why neither side is sufficient
     * alone.
     */
    private val _pendingHostPairing = MutableStateFlow<String?>(null)
    val pendingHostPairing: StateFlow<String?> = _pendingHostPairing

    /** Starts hosting under [code], generating a room code when none is given. */
    fun startHosting(code: String? = null): Boolean {
        if (_isHosting.value) return true
        val roomCode = code?.uppercase()?.takeIf { it.isNotBlank() } ?: SyncServer.generateRoomCode()

        syncServer.onPeersChanged = { _peerCount.value = it }
        syncServer.onPairingRequested = { peer ->
            _pendingPeers.update { waiting -> waiting.filterNot { it.id == peer.id } + peer }
        }
        syncServer.onKnownPeerRejoined = { name -> _statusMessage.value = "$name rejoined — paired before" }
        syncServer.onPairingWithdrawn = { id ->
            _pendingPeers.update { waiting -> waiting.filterNot { it.id == id } }
        }
        syncServer.onStateChanged = { state -> _roomState.value = state }

        if (!syncServer.start(roomCode)) {
            _statusMessage.value = "Could not host — port ${syncServer.port} is in use"
            return false
        }
        _roomCode.value = roomCode
        _isHosting.value = true
        _hostUrl.value = syncServer.websocketUrl()
        _statusMessage.value = when {
            syncServer.websocketUrl() == null -> "Hosting room $roomCode, but this machine is not on a network"
            !syncServer.isDiscoverable ->
                "Hosting room $roomCode — port ${SyncServer.DISCOVERY_PORT} is in use, " +
                    "so others must enter ${syncServer.websocketUrl()} by hand"
            else -> "Hosting room $roomCode — others can join now"
        }
        return true
    }

    fun stopHosting() {
        syncServer.stop()
        _isHosting.value = false
        _hostUrl.value = null
        _peerCount.value = 0
        _pendingPeers.value = emptyList()
    }

    /** The host's user confirms a joining device's digits. */
    fun approvePeer(id: String) {
        syncServer.approvePeer(id)
        _pendingPeers.update { peers -> peers.filterNot { it.id == id } }
    }

    /** The host's user turns a device away. */
    fun refusePeer(id: String) {
        syncServer.refusePeer(id)
        _pendingPeers.update { peers -> peers.filterNot { it.id == id } }
    }

    /** Joins a room at [wsUrl] under [code], as a guest. */
    fun joinRoom(wsUrl: String, code: String) {
        _roomCode.value = code
        syncClient.connect(wsUrl, code, deviceName, SyncRole.ALL.wireName)
    }

    /**
     * Broadcasts on the LAN for a host under [code] and joins whichever one
     * answers. [onServerDiscovered] does the actual connecting once a reply
     * arrives.
     */
    fun findAndJoin(code: String) {
        _roomCode.value = code.uppercase()
        _statusMessage.value = "Broadcasting LAN search..."
        syncClient.startLanDiscovery()
    }

    fun leaveRoom() {
        syncClient.stopLanDiscovery()
        syncClient.disconnect()
        _isConnected.value = false
    }

    /** This machine's user confirms the digits match the host's screen. */
    fun approveHostPairing() {
        _pendingHostPairing.value = null
        syncClient.approvePairing(_roomCode.value, SyncRole.ALL.wireName, deviceName)
    }

    /** This machine's user says the digits do not match. */
    fun refuseHostPairing() {
        _pendingHostPairing.value = null
        syncClient.refusePairing()
        _isConnected.value = false
        _statusMessage.value = "Pairing rejected — the codes did not match"
    }

    // --- SyncClient.SyncListener ---

    override fun onServerDiscovered(serverIp: String, wsUrl: String) {
        _statusMessage.value = "Server found at $serverIp"
        joinRoom(wsUrl, _roomCode.value)
    }

    override fun onConnected() {
        _isConnected.value = true
        _statusMessage.value = "Linked — waiting for the pairing code"
    }

    override fun onDisconnected() {
        _isConnected.value = false
        _statusMessage.value = "Disconnected"
    }

    override fun onJoinRefused(reason: String) {
        _isConnected.value = false
        _pendingHostPairing.value = null
        _statusMessage.value = "Room refused this device — $reason"
    }

    override fun onPairingCode(pairingCode: String) {
        _pendingHostPairing.value = pairingCode
    }

    override fun onKnownHostRejoined(name: String) {
        _pendingHostPairing.value = null
        _statusMessage.value = "Rejoined a room paired with before"
    }

    override fun onRoomStateReceived(json: JSONObject) {
        _roomState.value = json
    }

    override fun onKaossMoveEvent(x: Float, y: Float, padId: Int) = Unit
    override fun onSamplerTriggerEvent(padId: Int) = Unit
    override fun onAutoSyncEvent() = Unit
    override fun onLoadTrackEvent(deck: String, trackId: String) = Unit
    override fun onSeekEvent(deck: String, time: Float) = Unit
    override fun onNudgeEvent(deck: String, direction: String) = Unit

    companion object {
        /** What this machine calls itself on another device's approval prompt. */
        fun defaultDeviceName(): String {
            val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            return host?.takeIf { it.isNotBlank() } ?: "A desktop"
        }
    }
}
