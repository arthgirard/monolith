package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSession
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
        ongoing: BlockSession?,
    ): List<TimeSavedBucket> {
        val all = if (ongoing != null) sessions + ongoing else sessions
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
        ongoing: BlockSession?,
    ): Long = bucketsFor(periodType, anchor, sessions, ongoing).sumOf { it.durationMillis }

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
