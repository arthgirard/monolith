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
    /**
     * The single NFC technology Monolith listens for in the background so this tag can be
     * recognised by UID, as a simple class name like "MifareClassic". Null for [SMART_NDEF]
     * tags, which carry a monolith:// URI and are matched by that instead.
     *
     * Null means Monolith registers no technology filter at all, so no other app's tag can ever
     * reach it. When a technology is registered it is the narrowest one the tag supports, so an
     * Amiibo listens on MifareUltralight rather than on NfcA, which every sticker in the world
     * also answers to.
     */
    val dispatchTech: String? = null,
)
