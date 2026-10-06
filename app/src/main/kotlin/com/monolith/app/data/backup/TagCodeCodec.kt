package com.monolith.app.data.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The recovery code as it sits on a linked tag, sealed under a key derived from the tag's own UID.
 *
 * Hidden, not protected: a reader app shows opaque bytes, but the key comes from the UID and
 * constants anyone can read here, so whoever studies the app can still open it. Accepted because
 * whoever holds the tag can already turn Monolith off. Binding the key to the UID means the record
 * copied onto another tag does not decode.
 */
object TagCodeCodec {
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MASTER_BYTES = 32
    private const val KEY_BYTES = 32

    /** Version, nonce, the sealed master, and the GCM tag. */
    const val PAYLOAD_BYTES = 1 + NONCE_BYTES + MASTER_BYTES + TAG_BITS / 8

    private val random = SecureRandom()
    private val salt = "monolith".toByteArray(Charsets.UTF_8)
    private val info = "tag v1".toByteArray(Charsets.UTF_8)

    fun encode(uid: ByteArray, master: ByteArray): ByteArray {
        require(master.size == MASTER_BYTES) { "master must be $MASTER_BYTES bytes" }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        return byteArrayOf(VERSION) + nonce + cipher(Cipher.ENCRYPT_MODE, uid, nonce).doFinal(master)
    }

    /** Null for another tag's UID, a tampered payload, or a version this build does not know. */
    fun decode(uid: ByteArray, payload: ByteArray): ByteArray? {
        if (payload.size != PAYLOAD_BYTES || payload[0] != VERSION) return null
        val nonce = payload.copyOfRange(1, 1 + NONCE_BYTES)
        return try {
            cipher(Cipher.DECRYPT_MODE, uid, nonce).doFinal(payload, 1 + NONCE_BYTES, payload.size - 1 - NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun cipher(mode: Int, uid: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            val key = BackupCrypto.hkdf(uid, salt, info, KEY_BYTES)
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(byteArrayOf(VERSION))
        }
}
