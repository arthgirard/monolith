package com.monolith.app.service

import com.monolith.app.domain.model.AppUnlock
import com.monolith.app.domain.model.BlockState
import kotlin.math.ceil

/** What the enforcement notification is reporting right now. */
sealed interface EnforcementStatus {

    /** When this state runs out, or null when nothing is running out. */
    val expiresAtMillis: Long?

    /** Monolith holding the line, nothing carved out of it. */
    data object Enforcing : EnforcementStatus {
        override val expiresAtMillis: Long? = null
    }

    /** The global emergency bypass is running, and everything is let through until it ends. */
    data class Bypass(override val expiresAtMillis: Long) : EnforcementStatus

    /** One package is exempt until [expiresAtMillis]; every other blocked app stays blocked. */
    data class AppUnlocked(
        val packageName: String,
        override val expiresAtMillis: Long,
    ) : EnforcementStatus
}

/**
 * How many minutes a breach still has, drawn as a row of [total] segments with [lit] of them
 * still burning.
 */
data class SegmentRow(val total: Int, val lit: Int)

/**
 * The enforcement notification's state, and the cadence its redraws follow. Kept free of Android
 * so both can be tested without a notification shade to read them out of.
 */
object EnforcementSegments {

    private const val MINUTE_MILLIS = 60_000L

    /** Matches SessionPortraitRenderer's bitmap, whose pixels are what actually go stale. */
    private const val WIDTH_PX = 960
    private const val STEP_PX = 4

    private const val MIN_PORTRAIT_STEP_MILLIS = MINUTE_MILLIS
    private const val MAX_PORTRAIT_STEP_MILLIS = 5 * MINUTE_MILLIS

    /**
     * A running bypass outranks any app unlock. The bypass is global -- while it runs every app
     * is through, so naming one of them would describe the smaller exception and hide the larger
     * one. A cycle can also hold more than one live unlock, and the one shown is the one expiring
     * last, which is the same one deciding when the session timer resumes.
     */
    fun statusFor(state: BlockState, unlocks: Map<String, Long>, now: Long): EnforcementStatus {
        val bypassExpiresAt = state.bypassExpiresAtMillis
        if (bypassExpiresAt != null && state.isBypassActive(now)) {
            return EnforcementStatus.Bypass(bypassExpiresAt)
        }
        val latestUnlock = unlocks.filterValues { it > now }.maxByOrNull { it.value }
            ?: return EnforcementStatus.Enforcing
        return EnforcementStatus.AppUnlocked(latestUnlock.key, latestUnlock.value)
    }

    /**
     * The countdown as a row of minutes, or null when nothing is running out. Remaining minutes
     * are rounded up, so a part-used minute still burns its segment and the row empties at the
     * moment the window closes rather than a minute before it.
     */
    fun rowFor(status: EnforcementStatus, now: Long): SegmentRow? {
        val windowMillis = when (status) {
            EnforcementStatus.Enforcing -> return null
            is EnforcementStatus.Bypass -> BlockState.BYPASS_DURATION_MILLIS
            is EnforcementStatus.AppUnlocked -> AppUnlock.DURATION_MILLIS
        }
        val total = (windowMillis / MINUTE_MILLIS).toInt().coerceAtLeast(1)
        val remaining = ((status.expiresAtMillis ?: return null) - now).coerceAtLeast(0L)
        return SegmentRow(total = total, lit = ceil(remaining.toDouble() / MINUTE_MILLIS).toInt().coerceIn(0, total))
    }

    /**
     * How long until the portrait is worth redrawing while Monolith is simply holding. Its right
     * edge is now, so the bar goes stale even though no state changes -- the newest held time is
     * missing from it until something redraws.
     *
     * Scaled to the session's own length rather than fixed, because the bar is normalised: a
     * minute is a fifth of a five-minute session and a thousandth of a day-long one. This is the
     * time it takes roughly [STEP_PX] pixels of the bitmap to change, floored so a young session
     * doesn't redraw constantly and capped so an old one doesn't drift far behind.
     */
    fun millisToNextPortraitStep(cycleStart: Long, now: Long): Long {
        val elapsed = (now - cycleStart).coerceAtLeast(0L)
        return (elapsed / (WIDTH_PX / STEP_PX)).coerceIn(MIN_PORTRAIT_STEP_MILLIS, MAX_PORTRAIT_STEP_MILLIS)
    }

    /**
     * How long until the countdown next turns over a minute, for the redraw loop to sleep.
     * Measured against the countdown rather than a flat minute: a redraw landing a few seconds
     * after each boundary would drift further from it every time, and the bar would fall visibly
     * behind the number beside it.
     */
    fun millisToNextMinute(expiresAtMillis: Long, now: Long): Long {
        val remaining = expiresAtMillis - now
        if (remaining <= 0L) return 0L
        val intoMinute = remaining % MINUTE_MILLIS
        return if (intoMinute == 0L) MINUTE_MILLIS else intoMinute
    }

}
