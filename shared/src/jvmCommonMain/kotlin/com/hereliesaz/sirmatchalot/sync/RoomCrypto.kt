package com.hereliesaz.sirmatchalot.sync

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The room's transport security: a key exchange, an authentication code two
 * humans compare, and authenticated encryption for everything after.
 *
 * ## What this is for
 *
 * The room protocol runs over cleartext `ws://` on whatever Wi-Fi is to hand,
 * because a network security config cannot express "cleartext to a phone whose
 * address I will not know until I find it". Everything on that wire was
 * therefore readable by anyone on the network, and the room code — which is
 * broadcast in the clear by discovery — gated nothing but the shape of the
 * conversation.
 *
 * Two devices now agree a key over that wire and encrypt everything else with
 * it.
 *
 * ## Why a code the users compare
 *
 * A raw key exchange stops a passive listener and nothing else: whoever sits in
 * the middle can run one exchange with each side and read everything, and no
 * amount of key length changes that. The exchange has to be *authenticated* by
 * something an attacker cannot forge, and the only channel this app has that no
 * attacker is on is the two people standing in the booth looking at each other's
 * screens.
 *
 * So both devices derive six digits from the exchange itself ([sasOf]) and show
 * them. Identical on both screens means the two devices agreed a key with each
 * other. A device in the middle cannot make both codes match — it holds two
 * different exchanges, and the codes it produces differ from each other with
 * probability 999,999 in a million. The approval is mutual because the check is:
 * the host is confirming which device it just admitted, and the joining device
 * is confirming which host it has been admitted to.
 *
 * ## Why the joining device commits first
 *
 * Six digits are only about twenty bits. If a device in the middle could see
 * both honest keys before choosing its own, it could try keys offline until
 * the two legs happened to produce the same digits — a million tries is
 * seconds of work. So the joining device's `hello` carries only
 * [commitmentOf] its key; the host answers with its own key; and only then
 * does the joiner reveal the key it committed to, which the host checks with
 * [verifyCommitment] before deriving anything. Whoever sits in the middle has
 * to fix its key on each leg before it learns the honest key on that leg, so
 * it gets exactly one guess at the digits, not a million.
 *
 * ## Replays
 *
 * Every sealed frame carries a sequence number that increases by one per frame
 * in each direction, authenticated with the frame ([Session.seal]). The
 * receiver refuses any frame whose number is not greater than the last one it
 * accepted, so a recorded frame — "load this track", "play pad 3" — cannot be
 * sent again by somebody who merely watched it go past. The direction is
 * authenticated too, so a frame cannot be reflected back at its sender.
 *
 * ## The parts, and why these ones
 *
 * - **P-256 ECDH.** X25519 would be the modern choice and is not available
 *   below API 33; `minSdk` here is 24. P-256 key agreement has been in the
 *   platform since API 1.
 * - **HKDF-SHA256**, written out below because `javax.crypto` has no
 *   `HKDF` on Android. Extract-then-expand over the raw shared secret, salted
 *   with the transcript, so the session key depends on both public keys and the
 *   room code rather than on the secret alone.
 * - **AES-256-GCM**, which authenticates as well as encrypts: a tampered frame
 *   fails to open rather than decrypting to something plausible. Each frame
 *   carries a fresh 12-byte nonce and an authenticated sequence number.
 */
object RoomCrypto {

    /** How many digits the humans compare. */
    const val SAS_DIGITS = 6

    /** Bytes of nonce on every sealed frame. */
    const val NONCE_BYTES = 12

    /** Bytes of sequence number in front of every sealed frame. */
    const val SEQUENCE_BYTES = 8

    private const val GCM_TAG_BYTES = 16

    private const val GCM_TAG_BITS = 128
    private const val CURVE = "secp256r1"

    private val random = SecureRandom()

    /** A fresh key pair for one connection. Never reused across connections. */
    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE), random)
        }.generateKeyPair()

    /** [key] as bytes, for putting on the wire. */
    fun encodePublicKey(key: PublicKey): ByteArray = key.encoded

    /**
     * What the joining device sends in place of its key: a SHA-256 of the
     * encoded key, which binds it to that key without revealing it.
     */
    fun commitmentOf(encodedKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(encodedKey)

    /** Whether [encodedKey] is the key [commitment] was made to. */
    fun verifyCommitment(commitment: ByteArray, encodedKey: ByteArray): Boolean =
        MessageDigest.isEqual(commitment, commitmentOf(encodedKey))

    /** A peer's public key from the wire, or null if it is not one. */
    fun decodePublicKey(bytes: ByteArray): PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()

    /**
     * The session for a completed exchange.
     *
     * @param key what frames are sealed with.
     * @param sas the six digits both devices show their user. Equal on both
     *   sides exactly when both sides are talking to each other.
     * @param direction which way this side sends: 0 or 1, opposite on the two
     *   sides. Authenticated with every frame, so a frame cannot be bounced
     *   back to the device that sealed it.
     */
    class Session(private val key: ByteArray, val sas: String, private val direction: Int) {

        private val sent = java.util.concurrent.atomic.AtomicLong(0)

        /** The highest sequence number [open] has accepted. Guarded by `this`. */
        private var lastReceived = 0L

        /** The key itself, for tests that check two legs agreed different ones. */
        internal fun keyBytes(): ByteArray = key.copyOf()

        /**
         * [plaintext] sealed under this session, as sequence number, nonce and
         * ciphertext. The sequence number is in the clear but authenticated
         * along with the direction, so changing either makes the frame fail to
         * open.
         */
        fun seal(plaintext: ByteArray): ByteArray {
            val sequence = sent.incrementAndGet()
            val header = sequenceBytes(sequence)
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData(direction, header))
            return header + nonce + cipher.doFinal(plaintext)
        }

        /**
         * [sealed] opened, or null.
         *
         * Null covers every failure the same way — truncated, tampered, sealed
         * under another key, replayed, reflected — because a caller has nothing
         * useful to do with the difference and an attacker would like to know
         * which it was. Only a frame that authenticates moves the replay
         * window, so garbage cannot push it forward.
         */
        @Synchronized
        fun open(sealed: ByteArray): ByteArray? {
            if (sealed.size < SEQUENCE_BYTES + NONCE_BYTES + GCM_TAG_BYTES) return null
            val header = sealed.copyOf(SEQUENCE_BYTES)
            val sequence = java.nio.ByteBuffer.wrap(header).long
            if (sequence <= lastReceived) return null
            val opened = runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(
                        GCM_TAG_BITS,
                        sealed.copyOfRange(SEQUENCE_BYTES, SEQUENCE_BYTES + NONCE_BYTES),
                    ),
                )
                cipher.updateAAD(associatedData(1 - direction, header))
                val offset = SEQUENCE_BYTES + NONCE_BYTES
                cipher.doFinal(sealed, offset, sealed.size - offset)
            }.getOrNull() ?: return null
            lastReceived = sequence
            return opened
        }

        private fun sequenceBytes(sequence: Long): ByteArray =
            java.nio.ByteBuffer.allocate(SEQUENCE_BYTES).putLong(sequence).array()

        private fun associatedData(direction: Int, header: ByteArray): ByteArray =
            byteArrayOf(direction.toByte()) + header
    }

    /**
     * Agrees a session from this side's [ownKeys] and the other side's key.
     *
     * @param roomCode mixed into the derivation, so two devices that typed
     *   different codes cannot reach the same session even if their exchange
     *   otherwise succeeds.
     * @return null when [peerKey] is not a usable P-256 key.
     */
    fun agree(ownKeys: KeyPair, peerKey: PublicKey, roomCode: String): Session? = runCatching {
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(ownKeys.private)
            doPhase(peerKey, true)
        }
        val shared = agreement.generateSecret()

        // The transcript is both public keys in a fixed order — sorted, so both
        // sides build the same one without needing to agree who speaks first —
        // and the room code. Any disagreement about any of it produces a
        // different key and a different code.
        val transcript = transcriptOf(
            encodePublicKey(ownKeys.public),
            encodePublicKey(peerKey),
            roomCode,
        )

        val prk = hkdfExtract(salt = transcript, input = shared)
        Session(
            key = hkdfExpand(prk, "sir-match-a-lot room key".toByteArray(), 32),
            sas = sasOf(hkdfExpand(prk, "sir-match-a-lot room sas".toByteArray(), 8)),
            // The same sort the transcript uses, so the two sides pick
            // opposite directions without either needing to know it is "host".
            direction = if (compare(encodePublicKey(ownKeys.public), encodePublicKey(peerKey)) <= 0) 0 else 1,
        )
    }.getOrNull()

    /**
     * What both sides sign, and what the session key is salted with.
     *
     * Public because [DeviceIdentity] signs it: a signature over this proves the
     * signer is taking part in *this* exchange, with these ephemeral keys and
     * this room code, rather than replaying a recording of an earlier one.
     */
    fun transcriptOf(a: ByteArray, b: ByteArray, roomCode: String): ByteArray {
        val first: ByteArray
        val second: ByteArray
        if (compare(a, b) <= 0) {
            first = a
            second = b
        } else {
            first = b
            second = a
        }
        return MessageDigest.getInstance("SHA-256").run {
            update(first)
            update(second)
            update(roomCode.uppercase().toByteArray())
            digest()
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        val shared = minOf(a.size, b.size)
        for (i in 0 until shared) {
            val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return a.size - b.size
    }

    /**
     * Six digits from [bytes], zero-padded.
     *
     * Decimal rather than hex because these are read aloud across a booth as
     * often as they are compared on screen, and because a code that looks like a
     * PIN is treated like one.
     */
    fun sasOf(bytes: ByteArray): String {
        var value = 0L
        for (i in 0 until minOf(8, bytes.size)) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        }
        val modulus = 1_000_000L
        val digits = ((value % modulus) + modulus) % modulus
        return digits.toString().padStart(SAS_DIGITS, '0')
    }

    private fun hkdfExtract(salt: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(input)
        }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val out = ByteArray(length)
        var block = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val take = minOf(block.size, length - written)
            System.arraycopy(block, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }
}
