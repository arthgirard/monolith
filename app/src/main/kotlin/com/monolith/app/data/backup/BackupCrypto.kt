package com.monolith.app.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Keys and blobs for encrypted backups. Everything derives from a 32-byte master
 * (the recovery code): one HKDF branch is the server token, another is the AES key,
 * so the server never sees anything that can decrypt the blob.
 */
object BackupCrypto {
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val MASTER_BYTES = 32
    private const val CODE_LENGTH = 43
    private const val HMAC = "HmacSHA256"
    private const val HASH_BYTES = 32
    private const val KEY_BYTES = 32
    private const val MAX_PLAIN_BYTES = 16 * 1024 * 1024

    private val random = SecureRandom()
    private val salt = "monolith".toByteArray(Charsets.UTF_8)
    private val codeShape = Regex("^[A-Za-z0-9_-]{$CODE_LENGTH}$")

    fun newMaster(): ByteArray = ByteArray(MASTER_BYTES).also { random.nextBytes(it) }

    fun encodeCode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Null unless, once trimmed, the code is exactly 43 base64url characters holding 32 bytes. */
    fun decodeCode(code: String): ByteArray? {
        val trimmed = code.trim()
        if (!codeShape.matches(trimmed)) return null
        val bytes = try {
            Base64.getUrlDecoder().decode(trimmed)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size != MASTER_BYTES) return null
        // 43 characters leave 2 spare bits; reject codes that are not the canonical encoding.
        return if (encodeCode(bytes) == trimmed) bytes else null
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..(255 * HASH_BYTES)) { "length out of range" }
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream(length)
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmac(prk, previous + info + counter.toByte())
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun deriveToken(master: ByteArray): String =
        encodeCode(hkdf(master, salt, "auth v1".toByteArray(Charsets.UTF_8), 32))

    fun deriveKey(master: ByteArray): ByteArray =
        hkdf(master, salt, "backup v1".toByteArray(Charsets.UTF_8), 32)

    /** A key that is not 32 bytes is a programming error and throws IllegalArgumentException. */
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        try {
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(byteArrayOf(VERSION))
            return byteArrayOf(VERSION) + nonce + cipher.doFinal(gzip(plain))
        } catch (e: GeneralSecurityException) {
            throw BackupCryptoException("encryption failed", e)
        } catch (e: IOException) {
            throw BackupCryptoException("encryption failed", e)
        }
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < 1 + NONCE_BYTES + TAG_BYTES) throw BackupCryptoException("backup is too short")
        if (blob[0] != VERSION) throw BackupCryptoException("unsupported backup version ${blob[0]}")
        if (key.size != KEY_BYTES) throw BackupCryptoException("key must be $KEY_BYTES bytes")
        try {
            val nonce = blob.copyOfRange(1, 1 + NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(byteArrayOf(blob[0]))
            val compressed = cipher.doFinal(blob, 1 + NONCE_BYTES, blob.size - 1 - NONCE_BYTES)
            return gunzip(compressed)
        } catch (e: GeneralSecurityException) {
            throw BackupCryptoException("backup could not be decrypted", e)
        } catch (e: IllegalArgumentException) {
            throw BackupCryptoException("backup could not be decrypted", e)
        } catch (e: IOException) {
            throw BackupCryptoException("backup is corrupt", e)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_BYTES) else key, HMAC))
        return mac.doFinal(data)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(data) }
        return bytes.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_PLAIN_BYTES) throw BackupCryptoException("backup too large")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
}
