package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Buckets block sessions into hour/day/month slices for a given period, clipping each session
 * against bucket boundaries so a session spanning midnight/week/month is split correctly instead
 * of being credited entirely to its start bucket.
 */
object TimeSavedCalculator {

    private val zone: ZoneId = ZoneId.systemDefault()

    fun bucketsFor(
        periodType: TimePeriodType,
        anchor: LocalDate,
        sessions: List<BlockSession>,
        ongoing: List<BlockSession> = emptyList(),
    ): List<TimeSavedBucket> {
        val all = sessions + ongoing
        val boundaries = bucketBoundaries(periodType, anchor)
        return boundaries.zipWithNext { start, end ->
            val duration = all.sumOf { overlapMillis(it, start, end) }
            TimeSavedBucket(bucketStartMillis = start, durationMillis = duration, capacityMillis = end - start)
        }
    }

    fun totalFor(
        periodType: TimePeriodType,
        anchor: LocalDate,
        sessions: List<BlockSession>,
        ongoing: List<BlockSession> = emptyList(),
    ): Long = bucketsFor(periodType, anchor, sessions, ongoing).sumOf { it.durationMillis }

    /**
     * The still-in-progress session(s) not yet persisted to [sessions]. Normally a single span
     * from [activeSessionStart] to [now], but an emergency bypass carves itself out: time already
     * counted before the bypass started stays credited, nothing accrues while it's running, and
     * counting resumes fresh from the moment it expires. Mirrors how MonolithPreferences splits
     * the session when it's finally persisted, so the displayed total never jumps on that write.
     */
    fun ongoingSessions(blockState: BlockState, activeSessionStart: Long?, now: Long): List<BlockSession> {
        if (!blockState.isActive || activeSessionStart == null) return emptyList()
        val bypassExpiresAt = blockState.bypassExpiresAtMillis
            ?: return listOf(BlockSession(activeSessionStart, now))

        val bypassStartedAt = bypassExpiresAt - BlockState.BYPASS_DURATION_MILLIS
        val beforeBypassEnd = bypassStartedAt.coerceIn(activeSessionStart, now)
        val segments = mutableListOf<BlockSession>()
        if (beforeBypassEnd > activeSessionStart) segments.add(BlockSession(activeSessionStart, beforeBypassEnd))
        if (!blockState.isBypassActive(now)) {
            val afterBypassStart = bypassExpiresAt.coerceIn(activeSessionStart, now)
            if (now > afterBypassStart) segments.add(BlockSession(afterBypassStart, now))
        }
        return segments
    }

    /** Millis boundaries of the whole period, e.g. [monthStart, monthEnd). */
    fun periodRange(periodType: TimePeriodType, anchor: LocalDate): Pair<Long, Long> {
        val boundaries = bucketBoundaries(periodType, anchor)
        return boundaries.first() to boundaries.last()
    }

    /** N+1 millis boundaries carving the period into N buckets. */
    private fun bucketBoundaries(periodType: TimePeriodType, anchor: LocalDate): List<Long> = when (periodType) {
        TimePeriodType.DAY -> {
            val dayStart = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
            val hourMillis = 60 * 60 * 1000L
            (0..24).map { dayStart + it * hourMillis }
        }
        TimePeriodType.WEEK -> {
            val weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            (0..7).map { weekStart.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        }
        TimePeriodType.MONTH -> {
            val monthStart = anchor.withDayOfMonth(1)
            val dayCount = monthStart.lengthOfMonth()
            (0..dayCount).map { monthStart.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        }
        TimePeriodType.YEAR -> {
            val yearStart = anchor.withDayOfYear(1)
            (0..12).map { yearStart.plusMonths(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        }
    }

    private fun overlapMillis(session: BlockSession, rangeStart: Long, rangeEnd: Long): Long {
        val start = maxOf(session.startMillis, rangeStart)
        val end = minOf(session.endMillis, rangeEnd)
        return (end - start).coerceAtLeast(0)
    }
}
