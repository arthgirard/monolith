package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.Pause
import com.monolith.app.domain.model.PauseType
import com.monolith.app.domain.model.TimePeriodType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the leaderboard uploads, derived from the same sessions the time gained screen reads.
 * Day totals go through [TimeSavedCalculator] so the board can never disagree with that screen.
 */
object DailyAggregates {

    const val DAYS = 35

    fun build(
        sessions: List<BlockSession>,
        ongoing: List<BlockSession>,
        pauses: List<Pause>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayAggregate> {
        val pausesByDate = pauses.groupBy { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() }
        return (0 until DAYS).map { offset ->
            val date = today.minusDays(offset.toLong())
            val dayPauses = pausesByDate[date].orEmpty()
            DayAggregate(
                date = date,
                savedMillis = TimeSavedCalculator.totalFor(TimePeriodType.DAY, date, sessions, ongoing),
                bypassCount = dayPauses.count { it.type == PauseType.BYPASS },
                unlockCount = dayPauses.count { it.type == PauseType.UNLOCK },
            )
        }
    }

    /**
     * When the current streak began, so the board can tick it without syncing. Null while nothing
     * is being credited: Monolith off, or a pause running.
     */
    fun streakStartedAt(ongoing: List<BlockSession>, now: Long): Long? {
        val streak = GetCurrentStreakUseCase.streakOf(ongoing)
        return if (streak > 0) now - streak else null
    }

    /**
     * When a running pause ends, if one is running. Nothing in DataStore changes at that moment,
     * so the syncer schedules its own upload for it; otherwise friends would see the streak stuck
     * at "off" until the next session event.
     */
    fun pauseEndsAt(blockState: BlockState, activeSessionStart: Long?, now: Long): Long? {
        if (!blockState.isActive) return null
        return listOfNotNull(activeSessionStart, blockState.bypassExpiresAtMillis).filter { it > now }.maxOrNull()
    }
}
