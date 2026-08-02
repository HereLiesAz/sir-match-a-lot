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
 *   carries a fresh 12-byte nonce.
 */
object RoomCrypto {

    /** How many digits the humans compare. */
    const val SAS_DIGITS = 6

    /** Bytes of nonce on every sealed frame. */
    const val NONCE_BYTES = 12

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
     */
    class Session(private val key: ByteArray, val sas: String) {

        /** [plaintext] sealed under this session, as nonce followed by ciphertext. */
        fun seal(plaintext: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            return nonce + cipher.doFinal(plaintext)
        }

        /**
         * [sealed] opened, or null.
         *
         * Null covers every failure the same way — truncated, tampered, sealed
         * under another key — because a caller has nothing useful to do with the
         * difference and an attacker would like to know which it was.
         */
        fun open(sealed: ByteArray): ByteArray? {
            if (sealed.size <= NONCE_BYTES) return null
            return runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, sealed.copyOf(NONCE_BYTES)),
                )
                cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
            }.getOrNull()
        }
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
        )
    }.getOrNull()

    /** The two public keys in a canonical order, with the room code appended. */
    private fun transcriptOf(a: ByteArray, b: ByteArray, roomCode: String): ByteArray {
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
