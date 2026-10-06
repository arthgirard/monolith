package com.monolith.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagCodeStateTest {

    private val code = "a".repeat(43)
    private val smart = NfcTagLink(uid = "u", mode = TagLinkMode.SMART_NDEF, ndefUri = "monolith://tag/u", linkedAtMillis = 0L)

    @Test
    fun `the code on the tag matching is current`() {
        assertEquals(TagCodeState.CURRENT, tagCodeState(smart.copy(code = code), backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `a writable tag without a code is missing it`() {
        assertEquals(TagCodeState.MISSING, tagCodeState(smart, backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `another code on the tag is stale`() {
        assertEquals(TagCodeState.STALE, tagCodeState(smart.copy(code = "b".repeat(43)), backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `a UID-only tag never comes up`() {
        val uidOnly = NfcTagLink(uid = "u", mode = TagLinkMode.FALLBACK_UID, linkedAtMillis = 0L)
        assertNull(tagCodeState(uidOnly, backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `a tag too small for the code never comes up`() {
        assertNull(tagCodeState(smart.copy(codeFits = false), backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `nothing comes up with backup off`() {
        assertNull(tagCodeState(smart, backupEnabled = false, recoveryCode = code))
    }

    @Test
    fun `nothing comes up before the identity has a code`() {
        // Offline setup: the tag carries a code the identity will only get once it registers.
        assertNull(tagCodeState(smart.copy(code = code), backupEnabled = true, recoveryCode = null))
    }

    @Test
    fun `no tag, nothing to say`() {
        assertNull(tagCodeState(null, backupEnabled = true, recoveryCode = code))
    }
}
