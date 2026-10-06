package com.monolith.app.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagCodeCodecTest {

    private val uid = byteArrayOf(0x04, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F)
    private val master = ByteArray(32) { it.toByte() }

    @Test
    fun `round trip returns the master`() {
        assertArrayEquals(master, TagCodeCodec.decode(uid, TagCodeCodec.encode(uid, master)))
    }

    @Test
    fun `payload is the advertised size`() {
        assertEquals(61, TagCodeCodec.PAYLOAD_BYTES)
        assertEquals(TagCodeCodec.PAYLOAD_BYTES, TagCodeCodec.encode(uid, master).size)
    }

    @Test
    fun `another tag's uid does not decode`() {
        val other = uid.copyOf().also { it[6] = 0x70 }
        assertNull(TagCodeCodec.decode(other, TagCodeCodec.encode(uid, master)))
    }

    @Test
    fun `a tampered byte does not decode`() {
        val payload = TagCodeCodec.encode(uid, master)
        payload[20] = (payload[20].toInt() xor 1).toByte()
        assertNull(TagCodeCodec.decode(uid, payload))
    }

    @Test
    fun `an unknown version does not decode`() {
        val payload = TagCodeCodec.encode(uid, master)
        payload[0] = 2
        assertNull(TagCodeCodec.decode(uid, payload))
    }

    @Test
    fun `a short payload does not decode`() {
        assertNull(TagCodeCodec.decode(uid, ByteArray(10)))
    }

    @Test
    fun `both records fit an NTAG213`() {
        // Short-record header (3) + type "U" (1) + URI prefix byte (1) + the URI itself.
        val uriRecord = 3 + 1 + 1 + "monolith://tag/".length + uid.size * 2
        // Short-record header (3) + external type + payload.
        val codeRecord = 3 + "monolith.app:k".length + TagCodeCodec.PAYLOAD_BYTES
        // 137 is the NDEF capacity Android reports for an NTAG213 (144 bytes of user memory).
        assertTrue(uriRecord + codeRecord <= 137)
    }
}
