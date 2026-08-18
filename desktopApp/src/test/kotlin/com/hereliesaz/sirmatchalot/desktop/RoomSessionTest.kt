package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two [RoomSession]s, one hosting and one joining, driven the way a screen
 * would drive them. This is the actual claim of the whole desktop-linking
 * effort — a room hosted from this class and one joined from it can pair and
 * exchange state — proven mechanically here because there is no display in
 * CI to click through it with.
 */
class RoomSessionTest {

    /** An in-memory store, so no test writes to the real machine's home dir. */
    private class MemoryStore : KeyValueStore {
        private val values = HashMap<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private val sessions = ArrayList<RoomSession>()

    private fun session(name: String): RoomSession =
        RoomSession(MemoryStore(), deviceName = name).also { sessions.add(it) }

    @After
    fun tearDown() {
        sessions.forEach {
            runCatching { it.stopHosting() }
            runCatching { it.leaveRoom() }
        }
    }

    private fun <T> awaitValue(timeoutMs: Long = 3_000, poll: () -> T?): T? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            poll()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun `a desktop host and a desktop guest can pair`() {
        val host = session("Host Desktop")
        assertTrue("hosting should start", host.startHosting("ABCD"))
        val hostUrl = host.hostUrl.value
        assertNotNull("a hosting session must have a websocket URL", hostUrl)

        val guest = session("Guest Desktop")
        guest.joinRoom(hostUrl!!, "ABCD")

        // The host's approval prompt appears once the guest's hello arrives.
        val pending = awaitValue { host.pendingPeers.value.firstOrNull() }
        assertNotNull("host should see a pending peer", pending)
        host.approvePeer(pending!!.id)

        // The guest sees the same six digits and confirms them, which is the
        // other half of the pairing — see RoomSession.approveHostPairing.
        assertNotNull(
            "guest should be asked to confirm the pairing code",
            awaitValue { guest.pendingHostPairing.value },
        )
        guest.approveHostPairing()

        assertTrue(
            "guest should end up connected",
            awaitValue { guest.isConnected.value.takeIf { it } } == true,
        )
        assertTrue(
            "host should count the guest as joined",
            awaitValue { host.peerCount.value.takeIf { it > 0 } } == 1,
        )
        assertEquals(
            "and remember it for next time",
            1,
            host.knownDevices.all().size,
        )
    }

    @Test
    fun `a stranger is asked about before anything is remembered`() {
        val host = session("Host Desktop")
        assertTrue(host.startHosting("WXYZ"))
        val guest = session("Stranger")
        guest.joinRoom(host.hostUrl.value!!, "WXYZ")

        assertNotNull(
            "a stranger must be asked about",
            awaitValue { host.pendingPeers.value.firstOrNull() },
        )
        assertEquals(0, host.knownDevices.all().size)
    }
}
