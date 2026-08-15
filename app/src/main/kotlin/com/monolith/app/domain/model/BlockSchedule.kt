package com.monolith.app.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * A recurring rule that switches Monolith **on**. Deliberately has no end time: releasing is the
 * card's job, and adding a scheduled release would hand the user a second way out that costs
 * nothing. A rule only ever fires; it never unblocks.
 */
data class BlockSchedule(
    val id: String,
    val enabled: Boolean,
    val days: Set<DayOfWeek>,
    val startTime: LocalTime,
) {
    /** A rule with no days selected can never come round, so it's treated as inert everywhere. */
    private val isArmed: Boolean get() = enabled && days.isNotEmpty()

    /**
     * The first occurrence strictly after [now], or null if this rule is inert. Scans a full week
     * forward: seven days always contains the next occurrence of any non-empty day set, and the
     * eighth iteration covers "same weekday, but today's time already passed".
     */
    fun nextFireAfter(now: ZonedDateTime): ZonedDateTime? {
        if (!isArmed) return null
        return (0..7).asSequence()
            .map { occurrenceOn(now, daysAhead = it.toLong()) }
            .filterNotNull()
            .firstOrNull { it.isAfter(now) }
    }

    /**
     * The most recent occurrence at or before [now], or null if this rule is inert. Used by
     * catch-up, which has to reconstruct a fire the device slept through.
     */
    fun lastFireAtOrBefore(now: ZonedDateTime): ZonedDateTime? {
        if (!isArmed) return null
        return (0..7).asSequence()
            .map { occurrenceOn(now, daysAhead = -it.toLong()) }
            .filterNotNull()
            .firstOrNull { !it.isAfter(now) }
    }

    /**
     * This rule's start time on the day [daysAhead] from [now]'s date, or null if that weekday
     * isn't selected. Building through [ZonedDateTime.of] lets java.time resolve the awkward local
     * times a DST transition creates -- a spring-forward gap shifts the fire forward by the offset
     * rather than throwing, and a fall-back overlap picks the earlier of the two instants.
     */
    private fun occurrenceOn(now: ZonedDateTime, daysAhead: Long): ZonedDateTime? {
        val date = now.toLocalDate().plusDays(daysAhead)
        if (date.dayOfWeek !in days) return null
        return ZonedDateTime.of(date, startTime, now.zone)
    }

    companion object {
        /**
         * How far back catch-up will reach for a fire the device missed while powered off. Long
         * enough that turning the phone off through a scheduled block doesn't dodge it, short
         * enough that a phone left in a drawer for days doesn't switch on to a stale rule.
         */
        const val CATCH_UP_GRACE_MILLIS: Long = 8 * 60 * 60 * 1000L
    }
}

/** The soonest fire across every rule, or null if nothing is armed. Drives the single alarm. */
fun List<BlockSchedule>.earliestNextFire(now: ZonedDateTime): ZonedDateTime? =
    mapNotNull { it.nextFireAfter(now) }.minOrNull()

/**
 * The latest fire that landed in `(after, now]`, or null if none did. [after] is the exclusive
 * lower bound the caller derives from the last-handled watermark and the grace window, so an
 * occurrence already acted on can't be replayed.
 */
fun List<BlockSchedule>.latestMissedFire(now: ZonedDateTime, after: ZonedDateTime): ZonedDateTime? =
    mapNotNull { it.lastFireAtOrBefore(now) }.filter { it.isAfter(after) }.maxOrNull()

/**
 * The occurrence that should activate Monolith right now, or null if there isn't one. Serves both
 * the alarm landing on time and a catch-up after the device was off, because they ask the same
 * question: what is the most recent fire that hasn't been handled yet?
 *
 * The lower bound is whichever is later of the last-handled watermark and the grace window, so an
 * occurrence is never replayed, and a phone left off for days doesn't wake up to a stale rule.
 */
fun List<BlockSchedule>.pendingFire(
    now: ZonedDateTime,
    lastHandledMillis: Long?,
): ZonedDateTime? {
    val graceStart = now.minusNanos(BlockSchedule.CATCH_UP_GRACE_MILLIS * 1_000_000)
    val lastHandled = lastHandledMillis?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
    val after = if (lastHandled != null && lastHandled.isAfter(graceStart)) lastHandled else graceStart
    return latestMissedFire(now, after)
}
