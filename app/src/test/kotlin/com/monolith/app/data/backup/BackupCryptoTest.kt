package com.monolith.app.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val okm = BackupCrypto.hkdf(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun `token and key are deterministic and different`() {
        val master = ByteArray(32) { it.toByte() }
        assertEquals(BackupCrypto.deriveToken(master), BackupCrypto.deriveToken(master))
        assertEquals(43, BackupCrypto.deriveToken(master).length)
        assertNotEquals(BackupCrypto.encodeCode(BackupCrypto.deriveKey(master)), BackupCrypto.deriveToken(master))
    }

    @Test
    fun `codes round trip and reject junk`() {
        val master = BackupCrypto.newMaster()
        val code = BackupCrypto.encodeCode(master)
        assertEquals(43, code.length)
        assertArrayEquals(master, BackupCrypto.decodeCode("  $code\n"))
        assertNull(BackupCrypto.decodeCode("short"))
        assertNull(BackupCrypto.decodeCode("!".repeat(43)))
    }

    @Test
    fun `encrypt then decrypt returns the plaintext`() {
        val key = BackupCrypto.deriveKey(BackupCrypto.newMaster())
        val plain = "{\"version\":1}".repeat(500).toByteArray()
        val blob = BackupCrypto.encrypt(key, plain)
        assertEquals(1, blob[0].toInt())
        assertArrayEquals(plain, BackupCrypto.decrypt(key, blob))
    }

    @Test
    fun `wrong key, tampered byte and unknown version fail`() {
        val key = BackupCrypto.deriveKey(BackupCrypto.newMaster())
        val blob = BackupCrypto.encrypt(key, "hello".toByteArray())
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(BackupCrypto.deriveKey(BackupCrypto.newMaster()), blob) }
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(key, tampered) }
        val future = blob.copyOf().also { it[0] = 2 }
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(key, future) }
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(key, ByteArray(5)) }
    }
}
