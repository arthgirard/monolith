package com.monolith.app.domain.model

data class BlockState(
    val isActive: Boolean = false,
    val bypassExpiresAtMillis: Long? = null,
) {
    fun isBypassActive(nowMillis: Long): Boolean =
        bypassExpiresAtMillis != null && bypassExpiresAtMillis > nowMillis

    /** Effective enforcement: block mode is on and no live bypass is running. */
    fun isEnforcing(nowMillis: Long): Boolean = isActive && !isBypassActive(nowMillis)

    /**
     * A bypass has been started this cycle — whether it's still counting down or already ran
     * out — and stays true until a tag tap clears it. One bypass per Block Mode cycle.
     */
    val bypassUsed: Boolean get() = bypassExpiresAtMillis != null

    companion object {
        const val BYPASS_DURATION_MILLIS: Long = 15 * 60 * 1000L
    }
}
