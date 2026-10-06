package com.monolith.app.domain.model

/** Whether the linked tag holds the recovery code a reinstall would need. */
enum class TagCodeState { CURRENT, MISSING, STALE }

/**
 * Null when the question does not apply: no tag, a UID-only tag, one too small for the code,
 * backup off, or an identity without a code yet. Those users never hear about codes on tags.
 */
fun tagCodeState(link: NfcTagLink?, backupEnabled: Boolean, recoveryCode: String?): TagCodeState? {
    if (link == null || link.mode != TagLinkMode.SMART_NDEF || !link.codeFits) return null
    if (!backupEnabled || recoveryCode == null) return null
    return when (link.code) {
        null -> TagCodeState.MISSING
        recoveryCode -> TagCodeState.CURRENT
        else -> TagCodeState.STALE
    }
}
