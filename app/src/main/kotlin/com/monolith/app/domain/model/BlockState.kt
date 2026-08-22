package com.monolith.app.domain.model

data class BlockState(
    val isActive: Boolean = false,
    val bypassExpiresAtMillis: Long? = null,
) {
    fun isBypassActive(nowMillis: Long): Boolean =
        bypassExpiresAtMillis != null && bypassExpiresAtMillis > nowMillis

    /** Effective enforcement: Monolith is on and no live bypass is running. */
    fun isEnforcing(nowMillis: Long): Boolean = isActive && !isBypassActive(nowMillis)

    /**
     * A bypass has been started this cycle, whether it's still counting down or already ran
     * out, and stays true until a tag tap clears it. One bypass per Monolith cycle.
     */
    val bypassUsed: Boolean get() = bypassExpiresAtMillis != null

    /**
     * True for a short while after a bypass runs out. [bypassExpiresAtMillis] survives expiry
     * (it's what [bypassUsed] reads), so the moment the window closes is otherwise invisible:
     * the block overlay simply reappears with no explanation of where the bypass went. The wall
     * uses this to say so once.
     */
    fun bypassJustEnded(nowMillis: Long): Boolean {
        val expiresAt = bypassExpiresAtMillis ?: return false
        return nowMillis >= expiresAt && nowMillis - expiresAt < BYPASS_RECENTLY_ENDED_MILLIS
    }

    companion object {
        const val BYPASS_DURATION_MILLIS: Long = 15 * 60 * 1000L

        /** How long after a bypass expires the wall still calls it out. */
        const val BYPASS_RECENTLY_ENDED_MILLIS: Long = 2 * 60 * 1000L
    }
}
