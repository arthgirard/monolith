package com.monolith.app.domain.model

sealed interface NfcLinkResult {
    data class Success(val link: NfcTagLink) : NfcLinkResult
    data class Failure(val reason: String) : NfcLinkResult

    /** Monolith is active, so the key it is holding cannot be swapped for another one. */
    data object Locked : NfcLinkResult
}

sealed interface NfcTapResult {
    data class Toggled(val nowActive: Boolean) : NfcTapResult
    data object UnknownTag : NfcTapResult
    data object NoTagLinked : NfcTapResult
}
