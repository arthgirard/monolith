package com.monolith.app.domain.repository

import android.nfc.Tag
import com.monolith.app.domain.model.NfcLinkResult

/**
 * Bridges the domain layer to the NFC hardware. Implemented by [com.monolith.app.nfc.NfcManager]:
 * tries to write an NDEF URI record first, falls back to reading the tag's UID if the tag
 * is read-only or unformattable.
 */
interface TagProvisioner {
    suspend fun provisionTag(tag: Tag): NfcLinkResult

    /** Extracts a stable identifier (UID, or the NDEF URI if present) from a tapped tag. */
    fun identifyTag(tag: Tag): String
}
