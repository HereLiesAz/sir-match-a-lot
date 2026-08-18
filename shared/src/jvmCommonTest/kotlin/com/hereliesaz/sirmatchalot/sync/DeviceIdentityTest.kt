package com.hereliesaz.sirmatchalot.sync

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory store, standing in for the app's preferences file. */
internal class MemoryStore(
    private val values: MutableMap<String, String> = HashMap(),
) : KeyValueStore {
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

class DeviceIdentityTest {

    @Test
    fun `an identity survives the app being closed`() {
        // The point of the whole thing: a device that is a new stranger every
        // launch cannot be recognised, which is why pairing had to be repeated
        // at every connection.
        val store = MemoryStore()
        val first = DeviceIdentity.load(store)
        val second = DeviceIdentity.load(store)

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.publicKey, second.publicKey)
    }

    @Test
    fun `two devices are two identities`() {
        assertNotEquals(
            DeviceIdentity.load(MemoryStore()).fingerprint,
            DeviceIdentity.load(MemoryStore()).fingerprint,
        )
    }

    @Test
    fun `a signature verifies against its own transcript and no other`() {
        val identity = DeviceIdentity.load(MemoryStore())
        val transcript = "this exchange".toByteArray()
        val signature = identity.sign(transcript)

        assertTrue(DeviceIdentity.verify(identity.publicKey, transcript, signature))
        assertFalse(
            "a signature must not carry over to another exchange",
            DeviceIdentity.verify(identity.publicKey, "another exchange".toByteArray(), signature),
        )
    }

    @Test
    fun `one device cannot sign as another`() {
        // The attack the trust list would otherwise invite: claim a fingerprint
        // somebody else's device is remembered under. The fingerprint says which
        // key to check; without that key's private half the signature fails.
        val real = DeviceIdentity.load(MemoryStore())
        val impostor = DeviceIdentity.load(MemoryStore())
        val transcript = "this exchange".toByteArray()

        assertFalse(
            DeviceIdentity.verify(real.publicKey, transcript, impostor.sign(transcript)),
        )
    }

    @Test
    fun `a corrupted store yields a fresh identity rather than a crash`() {
        val store = MemoryStore()
        store.putString("identity_private", "not a key")
        store.putString("identity_public", "also not a key")

        val identity = DeviceIdentity.load(store)
        assertEquals(16, identity.fingerprint.length)
        // And the replacement is written back, so it is stable from now on.
        assertEquals(identity.fingerprint, DeviceIdentity.load(store).fingerprint)
    }

    @Test
    fun `rubbish where an identity should be is refused`() {
        assertNull(DeviceIdentity.decodePublicKey(ByteArray(0)))
        assertNull(DeviceIdentity.decodePublicKey("nonsense".toByteArray()))
    }
}

class KnownDevicesTest {

    @Test
    fun `a remembered device is known, and survives a restart`() {
        val store = MemoryStore()
        KnownDevices(store).remember("abcd1234", "Pixel 8")

        val reopened = KnownDevices(store)
        assertTrue(reopened.isKnown("abcd1234"))
        assertEquals("Pixel 8", reopened.all()["abcd1234"])
    }

    @Test
    fun `an unknown device is not known`() {
        assertFalse(KnownDevices(MemoryStore()).isKnown("abcd1234"))
    }

    @Test
    fun `forgetting one leaves the others`() {
        val devices = KnownDevices(MemoryStore())
        devices.remember("aaaa", "One")
        devices.remember("bbbb", "Two")

        devices.forget("aaaa")
        assertFalse(devices.isKnown("aaaa"))
        assertTrue(devices.isKnown("bbbb"))
    }

    @Test
    fun `forgetting everything is possible`() {
        // A trust list with no way to empty it is one you cannot correct after
        // approving the wrong thing.
        val devices = KnownDevices(MemoryStore())
        devices.remember("aaaa", "One")
        devices.remember("bbbb", "Two")

        devices.forgetAll()
        assertTrue(devices.all().isEmpty())
    }

    @Test
    fun `a name with a newline or an equals sign does not corrupt the list`() {
        val devices = KnownDevices(MemoryStore())
        devices.remember("aaaa", "Bad\nName=Here")
        devices.remember("bbbb", "Two")

        assertEquals(2, devices.all().size)
        assertTrue(devices.isKnown("aaaa"))
        assertTrue(devices.isKnown("bbbb"))
    }
}
