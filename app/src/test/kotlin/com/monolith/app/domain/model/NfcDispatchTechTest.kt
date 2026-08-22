package com.monolith.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Monolith runs on whatever tag you already own, so the background filter it registers is the
 * one place it can accidentally take over the phone's NFC. These pin how small that claim stays.
 */
class NfcDispatchTechTest {

    @Test
    fun `a Skylander is listened for on MifareClassic, not on NfcA`() {
        val skylander = listOf("android.nfc.tech.NfcA", "android.nfc.tech.MifareClassic")

        assertEquals("MifareClassic", NfcDispatchTech.narrowest(skylander))
    }

    @Test
    fun `an Amiibo is listened for on MifareUltralight, not on NfcA or Ndef`() {
        val amiibo = listOf(
            "android.nfc.tech.NfcA",
            "android.nfc.tech.MifareUltralight",
            "android.nfc.tech.Ndef",
        )

        assertEquals("MifareUltralight", NfcDispatchTech.narrowest(amiibo))
    }

    @Test
    fun `a card offering nothing tighter than IsoDep still avoids NfcA`() {
        val card = listOf("android.nfc.tech.IsoDep", "android.nfc.tech.NfcA")

        assertEquals("IsoDep", NfcDispatchTech.narrowest(card))
    }

    @Test
    fun `NfcA is only chosen when the tag offers nothing else`() {
        assertEquals("NfcA", NfcDispatchTech.narrowest(listOf("android.nfc.tech.NfcA")))
    }

    @Test
    fun `a tag Monolith cannot listen for yields nothing`() {
        assertNull(NfcDispatchTech.narrowest(listOf("android.nfc.tech.NfcBarcode")))
    }

    @Test
    fun `an NDEF-linked tag registers no technology at all`() {
        val smart = NfcTagLink(uid = "AA", mode = TagLinkMode.SMART_NDEF, ndefUri = "monolith://tag/AA")

        assertNull(NfcDispatchTech.forLink(smart))
    }

    @Test
    fun `no linked tag registers no technology at all`() {
        assertNull(NfcDispatchTech.forLink(null))
    }

    @Test
    fun `a UID tag registers exactly its own technology`() {
        val uid = NfcTagLink(uid = "AA", mode = TagLinkMode.FALLBACK_UID, dispatchTech = "MifareClassic")

        assertEquals("MifareClassic", NfcDispatchTech.forLink(uid))
    }

    @Test
    fun `a UID tag linked before technologies were recorded keeps working`() {
        // The regression this guards: such a link stores no technology, and registering nothing
        // for it would silently stop the tag toggling Monolith on the update that shipped this.
        val legacy = NfcTagLink(uid = "AA", mode = TagLinkMode.FALLBACK_UID, dispatchTech = null)

        assertEquals(NfcDispatchTech.LEGACY_FALLBACK, NfcDispatchTech.forLink(legacy))
    }

    @Test
    fun `every technology that can be chosen has an alias to enable`() {
        // narrowest() may only ever return something the manifest declares a filter for, and
        // NfcDispatchGate enables aliases straight from PRIORITY, so the two stay in step.
        NfcDispatchTech.PRIORITY.forEach { tech ->
            assertEquals(tech, NfcDispatchTech.narrowest(listOf("android.nfc.tech.$tech")))
        }
    }
}
