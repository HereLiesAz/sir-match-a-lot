package com.hereliesaz.sirmatchalot.sync

import com.hereliesaz.sirmatchalot.data.KeyValueStore
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * This device's long-term name, in the only sense the room can check.
 *
 * ## Why there are two key pairs
 *
 * [RoomCrypto] makes a fresh key pair for every connection, which is what gives
 * a session forward secrecy: nothing kept on the device afterwards can decrypt
 * a recording of it. That is exactly the wrong property for recognising a
 * device — an ephemeral key is a stranger every time, which is why pairing had
 * to be repeated at every connection and why every reconnection put a dialog in
 * front of two people who had already answered it.
 *
 * So there is a second, *persistent* key pair. It never encrypts anything. Its
 * only job is to sign the ephemeral exchange, which proves two things at once:
 * that whoever is on the other end holds this identity's private key, and that
 * they hold it *now*, in this exchange, rather than replaying a recording of an
 * earlier one.
 *
 * ## What a fingerprint is worth
 *
 * A fingerprint on its own is a claim, not a credential — anyone can send any
 * bytes. Trusting a remembered fingerprint is safe only because the signature
 * is checked against the key it names, over a transcript that includes both
 * ephemeral keys and the room code. Remembering the fingerprint without
 * verifying the signature would turn "we have paired before" into "anyone who
 * has seen you pair before", which is worse than prompting every time.
 */
class DeviceIdentity private constructor(private val keys: KeyPair) {

    /** This device's public identity, for the wire. */
    val publicKey: PublicKey get() = keys.public

    /** What the other side remembers this device as. */
    val fingerprint: String get() = fingerprintOf(keys.public)

    /** Signs [transcript] — the exchange this device is taking part in. */
    fun sign(transcript: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keys.private)
            update(transcript)
            sign()
        }

    companion object {
        private const val CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        private const val KEY_PRIVATE = "identity_private"
        private const val KEY_PUBLIC = "identity_public"

        /**
         * This device's identity, loaded or created.
         *
         * Created exactly once and kept: an identity that changed would make
         * every previously paired device a stranger again, which is the problem
         * this exists to solve.
         */
        fun load(store: KeyValueStore): DeviceIdentity {
            val existing = restore(store)
            if (existing != null) return DeviceIdentity(existing)

            val generated = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec(CURVE), SecureRandom())
            }.generateKeyPair()

            store.putString(KEY_PRIVATE, WebSocketProtocol.base64(generated.private.encoded))
            store.putString(KEY_PUBLIC, WebSocketProtocol.base64(generated.public.encoded))
            return DeviceIdentity(generated)
        }

        private fun restore(store: KeyValueStore): KeyPair? = runCatching {
            val privateBytes =
                WebSocketProtocol.decodeBase64(store.getString(KEY_PRIVATE) ?: return null)
                    ?: return null
            val publicBytes =
                WebSocketProtocol.decodeBase64(store.getString(KEY_PUBLIC) ?: return null)
                    ?: return null
            val factory = KeyFactory.getInstance("EC")
            KeyPair(
                factory.generatePublic(X509EncodedKeySpec(publicBytes)),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
            )
        }.getOrNull()

        /** A public identity from the wire, or null if it is not one. */
        fun decodePublicKey(bytes: ByteArray): PublicKey? = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull()

        /**
         * Whether [signature] over [transcript] was made by [key].
         *
         * The check the whole trust list rests on. A remembered fingerprint says
         * only which key to check against; this is what makes claiming somebody
         * else's fingerprint useless.
         */
        fun verify(key: PublicKey, transcript: ByteArray, signature: ByteArray): Boolean =
            runCatching {
                Signature.getInstance(SIGNATURE_ALGORITHM).run {
                    initVerify(key)
                    update(transcript)
                    verify(signature)
                }
            }.getOrDefault(false)

        /**
         * A short, stable name for [key].
         *
         * The first eight bytes of SHA-256, in hex. Short enough to store and
         * log, long enough that finding a second key with the same one is not
         * something an attacker on a Wi-Fi network is going to do — and it is
         * never the only thing checked anyway.
         */
        fun fingerprintOf(key: PublicKey): String =
            MessageDigest.getInstance("SHA-256")
                .digest(key.encoded)
                .take(8)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * The devices this one has paired with before.
 *
 * Kept so that two people who have already compared six digits are not asked to
 * compare them again every time they open the app. A device is remembered by
 * the fingerprint of its identity key, and remembering it means only that its
 * *signature* will be trusted — never that its claim to a fingerprint will be.
 *
 * Stored as one string because there are a handful of entries and no reason for
 * a table: `fingerprint=name` per line, and a name that contains a newline or an
 * equals sign is trimmed of them rather than escaped, since it is a label on a
 * dialog and not a key.
 */
class KnownDevices(private val store: KeyValueStore) {

    /** Every remembered device, fingerprint to the name it was paired under. */
    fun all(): Map<String, String> {
        val raw = store.getString(KEY) ?: return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
    }

    /** True when [fingerprint] has been paired with and not forgotten. */
    fun isKnown(fingerprint: String): Boolean = all().containsKey(fingerprint)

    /** Remembers [fingerprint], so the next connection needs no dialog. */
    fun remember(fingerprint: String, name: String) {
        if (fingerprint.isBlank()) return
        write(all() + (fingerprint to name.replace('\n', ' ').replace('=', ' ').trim()))
    }

    /** Forgets one device, which will be prompted for again next time. */
    fun forget(fingerprint: String) {
        write(all() - fingerprint)
    }

    /** Forgets every device. The revocation this needs to have. */
    fun forgetAll() {
        write(emptyMap())
    }

    private fun write(devices: Map<String, String>) {
        store.putString(KEY, devices.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private companion object {
        const val KEY = "known_devices"
    }
}
