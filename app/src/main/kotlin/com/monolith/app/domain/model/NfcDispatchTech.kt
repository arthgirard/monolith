package com.monolith.app.domain.model

/**
 * Which NFC technology Monolith registers a background filter for, given a tag.
 *
 * Monolith is built to run on whatever tag you already own: an expired transit card, a dead debit
 * card, an Amiibo, a Skylander. None of those can be written, so they are recognised by hardware
 * UID, and seeing one arrive while Monolith is in the background needs a TECH_DISCOVERED filter.
 * Such a filter is a claim on part of the phone's NFC traffic, so the claim is kept as small as
 * the tag allows.
 */
object NfcDispatchTech {

    /**
     * Narrowest first. NfcA is last on purpose: nearly every tag in existence answers to it, so
     * choosing it puts Monolith back in front of every tap. IsoDep is second-last because it
     * covers payment and transit cards, which is exactly the traffic worth staying out of.
     */
    val PRIORITY = listOf(
        "MifareClassic",
        "MifareUltralight",
        "NfcF",
        "NfcV",
        "IsoDep",
        "NfcB",
        "NfcA",
    )

    /**
     * What a tag linked before this existed falls back to. Such a link records no technology, and
     * leaving it with none would silently stop the tag working on the next update. NfcA covers
     * almost every tag people repurpose, at the cost of a wide claim, which the first matching tap
     * then narrows for good (see [ToggleBlockModeFromTagUseCase]).
     */
    const val LEGACY_FALLBACK = "NfcA"

    /** The most specific supported technology, or null if the tag offers none Monolith can use. */
    fun narrowest(techList: List<String>): String? {
        val supported = techList.map { it.substringAfterLast('.') }.toSet()
        return PRIORITY.firstOrNull { it in supported }
    }

    /** The technology to register for [link], or null when none should be registered at all. */
    fun forLink(link: NfcTagLink?): String? = when {
        link == null -> null
        // An NDEF tag carries a monolith:// URI and is matched by that. No claim needed, so no
        // other app's tag can reach Monolith at all.
        link.mode == TagLinkMode.SMART_NDEF -> null
        else -> link.dispatchTech ?: LEGACY_FALLBACK
    }
}
