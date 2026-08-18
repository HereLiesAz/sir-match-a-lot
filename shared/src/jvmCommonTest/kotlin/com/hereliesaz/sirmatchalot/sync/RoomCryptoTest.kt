package com.hereliesaz.sirmatchalot.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room's key exchange and the code the two humans compare.
 *
 * These run on the JVM because everything here is `javax.crypto` and
 * `java.security`, which the JDK provides — so the property that matters most,
 * that a device in the middle cannot make both screens show the same digits, is
 * checked by an ordinary unit test rather than by two phones and a hope.
 */
class RoomCryptoTest {

    private fun session(
        ownKeys: java.security.KeyPair,
        peerKeys: java.security.KeyPair,
        roomCode: String = "TEST",
    ) = RoomCrypto.agree(ownKeys, peerKeys.public, roomCode)

    @Test
    fun `both sides of an exchange reach the same session`() {
        val host = RoomCrypto.newKeyPair()
        val peer = RoomCrypto.newKeyPair()

        val hostSide = session(host, peer)
        val peerSide = session(peer, host)

        assertNotNull(hostSide)
        assertNotNull(peerSide)
        assertEquals("the code both users compare must match", hostSide!!.sas, peerSide!!.sas)

        val sealed = hostSide.seal("crossfader to 40".toByteArray())
        assertEquals("crossfader to 40", peerSide.open(sealed)?.toString(Charsets.UTF_8))
    }

    @Test
    fun `a device in the middle cannot make both codes match`() {
        // The attack the code exists to stop: someone on the network runs one
        // exchange with the host and another with the joining device, and reads
        // and rewrites everything in between. The keys agree perfectly on both
        // legs — that is what makes a bare key exchange useless on its own — and
        // the only thing that gives it away is that the two legs are different
        // exchanges, so they produce different digits.
        val host = RoomCrypto.newKeyPair()
        val peer = RoomCrypto.newKeyPair()
        val attacker = RoomCrypto.newKeyPair()

        val hostToAttacker = session(host, attacker)!!
        val peerToAttacker = session(peer, attacker)!!

        assertNotEquals(
            "the two legs must not show the same digits",
            hostToAttacker.sas,
            peerToAttacker.sas,
        )
    }

    @Test
    fun `a different room code is a different session`() {
        val host = RoomCrypto.newKeyPair()
        val peer = RoomCrypto.newKeyPair()

        val right = RoomCrypto.agree(host, peer.public, "ABCD")!!
        val wrong = RoomCrypto.agree(peer, host.public, "WXYZ")!!

        assertNotEquals(right.sas, wrong.sas)
        assertNull(
            "a session from another room must not open these frames",
            wrong.open(right.seal("load track".toByteArray())),
        )
    }

    @Test
    fun `the room code is compared without regard to case`() {
        val host = RoomCrypto.newKeyPair()
        val peer = RoomCrypto.newKeyPair()
        assertEquals(
            RoomCrypto.agree(host, peer.public, "abcd")!!.sas,
            RoomCrypto.agree(peer, host.public, "ABCD")!!.sas,
        )
    }

    @Test
    fun `a tampered frame does not open`() {
        val host = RoomCrypto.newKeyPair()
        val peer = RoomCrypto.newKeyPair()
        val hostSide = session(host, peer)!!
        val peerSide = session(peer, host)!!

        val sealed = hostSide.seal("""{"crossfader":40}""".toByteArray())
        // Flip one bit of the ciphertext — the sort of thing a device in the
        // middle does when it cannot read a frame but would like to change it.
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        assertNotNull("the original must still open", peerSide.open(sealed))
        assertNull("a flipped bit must not open", peerSide.open(tampered))
    }

    @Test
    fun `truncated and empty frames are rejected rather than crashing`() {
        val host = RoomCrypto.newKeyPair()
        val peer = RoomCrypto.newKeyPair()
        val peerSide = session(peer, host)!!

        assertNull(peerSide.open(ByteArray(0)))
        assertNull(peerSide.open(ByteArray(RoomCrypto.NONCE_BYTES)))
        assertNull(peerSide.open(ByteArray(RoomCrypto.NONCE_BYTES - 1)))
    }

    @Test
    fun `sealing the same message twice gives different bytes`() {
        // A fresh nonce per frame. Without one, "crossfader to 0" is recognisable
        // on the wire every time it is sent, and a repeated frame can be replayed.
        val host = RoomCrypto.newKeyPair()
        val peer = RoomCrypto.newKeyPair()
        val hostSide = session(host, peer)!!

        val once = hostSide.seal("same".toByteArray())
        val twice = hostSide.seal("same".toByteArray())
        assertNotEquals(once.toList(), twice.toList())
    }

    @Test
    fun `a code is six digits, and not always the same six`() {
        val seen = HashSet<String>()
        repeat(24) {
            val host = RoomCrypto.newKeyPair()
            val peer = RoomCrypto.newKeyPair()
            val sas = session(host, peer)!!.sas
            assertEquals(RoomCrypto.SAS_DIGITS, sas.length)
            assertTrue("not digits: $sas", sas.all { it.isDigit() })
            seen.add(sas)
        }
        assertTrue("the code never varied across 24 exchanges", seen.size > 20)
    }

    @Test
    fun `rubbish where a public key should be is refused`() {
        assertNull(RoomCrypto.decodePublicKey(ByteArray(0)))
        assertNull(RoomCrypto.decodePublicKey("not a key at all".toByteArray()))
    }

    @Test
    fun `a public key survives the wire`() {
        val keys = RoomCrypto.newKeyPair()
        val restored = RoomCrypto.decodePublicKey(RoomCrypto.encodePublicKey(keys.public))
        assertNotNull(restored)
        assertEquals(keys.public, restored)
    }
}
