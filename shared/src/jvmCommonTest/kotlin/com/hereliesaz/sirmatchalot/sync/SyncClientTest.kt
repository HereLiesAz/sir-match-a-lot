package com.hereliesaz.sirmatchalot.sync

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The client half, against the real server.
 *
 * `SyncServerTest` drives the server with a hand-written peer; this drives it
 * with the [SyncClient] the apps actually ship, so the two halves of the
 * handshake — commitment, reveal, sealed identities, mutual approval — are
 * checked against each other rather than each against a test's idea of the
 * other.
 */
class SyncClientTest {

    private var server: SyncServer? = null
    private val clients = ArrayList<SyncClient>()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server?.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Recorder : SyncClient.SyncListener {
        val connected = LinkedBlockingQueue<Unit>()
        val disconnected = LinkedBlockingQueue<Unit>()
        val codes = LinkedBlockingQueue<String>()
        val states = LinkedBlockingQueue<JSONObject>()
        val refusals = LinkedBlockingQueue<String>()

        override fun onServerDiscovered(serverIp: String, wsUrl: String) {}
        override fun onConnected() { connected.offer(Unit) }
        override fun onDisconnected() { disconnected.offer(Unit) }
        override fun onJoinRefused(reason: String) { refusals.offer(reason) }
        override fun onPairingCode(pairingCode: String) { codes.offer(pairingCode) }
        override fun onKnownHostRejoined(name: String) {}
        override fun onRoomStateReceived(json: JSONObject) { states.offer(json) }
        override fun onKaossMoveEvent(x: Float, y: Float, padId: Int) {}
        override fun onSamplerTriggerEvent(padId: Int) {}
        override fun onAutoSyncEvent() {}
        override fun onLoadTrackEvent(deck: String, trackId: String) {}
        override fun onSeekEvent(deck: String, time: Float) {}
        override fun onNudgeEvent(deck: String, direction: String) {}
    }

    private fun startServer(code: String): SyncServer =
        SyncServer(port = freePort(), discoveryPort = freePort()).also {
            it.start(code)
            server = it
        }

    private fun client(recorder: Recorder): SyncClient =
        SyncClient(recorder).also { clients.add(it) }

    @Test
    fun `nothing reaches the room until this device's user approves`() {
        val server = startServer("ABCD")
        val pending = ArrayBlockingQueue<SyncServer.PendingPeer>(4)
        server.onPairingRequested = { pending.offer(it) }
        val changes = LinkedBlockingQueue<JSONObject>()
        server.onStateChanged = { changes.offer(it) }

        val recorder = Recorder()
        val client = client(recorder)
        client.connect("ws://127.0.0.1:${server.port}", "abcd", "Laptop", "all")

        val code = recorder.codes.poll(3, TimeUnit.SECONDS)
        val asked = pending.poll(3, TimeUnit.SECONDS)
        assertNotNull("the client must be shown digits", code)
        assertNotNull("the host must be asked", asked)
        assertEquals("both screens must show the same digits", asked!!.pairingCode, code)

        // The host approves, and the client tries to join and to move the
        // crossfader — but its own user has not looked at the digits yet.
        server.approvePeer(asked.id)
        client.joinRoom("ABCD", "all", "Laptop")
        client.updateCrossfader(77, "ABCD")

        assertNull("no join may go out before approval", recorder.states.poll(800, TimeUnit.MILLISECONDS))
        assertNull("nor any update", changes.poll(100, TimeUnit.MILLISECONDS))
        assertEquals(0, server.peerCount)

        client.approvePairing("ABCD", "all", "Laptop")
        assertNotNull("approval must join the room", recorder.states.poll(3, TimeUnit.SECONDS))
        assertEquals(1, server.peerCount)

        client.updateCrossfader(-20, "ABCD")
        assertEquals(-20, changes.poll(3, TimeUnit.SECONDS)!!.getInt("crossfader"))
    }

    @Test
    fun `an old connection closing does not report the new one as dropped`() {
        // connect() closes the previous socket, whose close then arrives after
        // the new socket has opened — and used to be reported as this client
        // disconnecting, while it was in fact connected.
        val server = startServer("ABCD")
        val recorder = Recorder()
        val client = client(recorder)
        val url = "ws://127.0.0.1:${server.port}"

        client.connect(url, "ABCD")
        assertNotNull(recorder.connected.poll(3, TimeUnit.SECONDS))
        assertNotNull(recorder.codes.poll(3, TimeUnit.SECONDS))

        client.connect(url, "ABCD")
        assertNotNull(recorder.connected.poll(3, TimeUnit.SECONDS))
        assertNull(
            "the replaced socket's close must be ignored",
            recorder.disconnected.poll(1_500, TimeUnit.MILLISECONDS),
        )
        assertNotNull("and the new one still pairs", recorder.codes.poll(3, TimeUnit.SECONDS))
    }
}
