package com.monolith.app.domain.repository

import android.nfc.Tag
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.NfcTagLink

/**
 * Bridges the domain layer to the NFC hardware. Implemented by [com.monolith.app.nfc.NfcManager]:
 * tries to write an NDEF URI record first, falls back to reading the tag's UID if the tag
 * is read-only or unformattable.
 */
interface TagProvisioner {
    /** Writes Monolith's URI to [tag], and [code] beside it when given and the tag has room. */
    suspend fun provisionTag(tag: Tag, code: String?): NfcLinkResult

    /** Extracts a stable identifier (UID, or the NDEF URI if present) from a tapped tag. */
    fun identifyTag(tag: Tag): String

    /** The narrowest NFC technology [tag] can be listened for on, or null if there is none. */
    fun dispatchTechFor(tag: Tag): String?

    /**
     * The recovery code [tag] carries, from the NDEF message Android cached when it was
     * discovered. Null when there is none, or it does not decode for this tag. Writes nothing.
     */
    fun readCode(tag: Tag): String?

    /** The link for a tag Monolith already wrote, carrying [code], made without writing to it. */
    fun existingLink(tag: Tag, code: String): NfcTagLink
}
