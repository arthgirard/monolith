package com.monolith.app.domain.model

enum class TagLinkMode {
    /** Tag accepted an NDEF URI write; taps on an uninstalled phone open the download page. */
    SMART_NDEF,

    /** Tag was read-only or unformattable; Monolith fell back to matching its hardware UID. */
    FALLBACK_UID,
}

data class NfcTagLink(
    val uid: String,
    val mode: TagLinkMode,
    val ndefUri: String? = null,
    val linkedAtMillis: Long = System.currentTimeMillis(),
)
