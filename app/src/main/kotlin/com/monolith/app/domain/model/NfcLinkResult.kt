package com.monolith.app.domain.model

sealed interface NfcLinkResult {
    data class Success(val link: NfcTagLink) : NfcLinkResult
    data class Failure(val reason: String) : NfcLinkResult
}

sealed interface NfcTapResult {
    data class Toggled(val nowActive: Boolean) : NfcTapResult
    data object UnknownTag : NfcTapResult
    data object NoTagLinked : NfcTapResult
}
