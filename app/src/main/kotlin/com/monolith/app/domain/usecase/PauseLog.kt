package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.Pause
import com.monolith.app.domain.model.PauseType

/**
 * The retention rule for the pause log, kept pure like [BlockHitLog]. The write stays inside
 * MonolithPreferences' atomic edit, next to the pause it records.
 */
object PauseLog {

    /**
     * One day more than the 35 the leaderboard uploads, so the oldest uploaded day still has all
     * its pauses when the phone sits in a zone behind UTC.
     */
    const val RETENTION_MILLIS: Long = 36L * 24 * 60 * 60 * 1000

    fun record(existing: List<Pause>, type: PauseType, now: Long): List<Pause> {
        val cutoff = now - RETENTION_MILLIS
        return existing.filter { it.atMillis >= cutoff } + Pause(type, now)
    }
}
