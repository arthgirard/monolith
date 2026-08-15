package com.monolith.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Covers the window arithmetic shared by an alarm landing on time and a post-boot catch-up. */
class PendingFireTest {

    private val zone: ZoneId = ZoneId.of("Europe/Lisbon")

    private fun at(date: String, time: String): ZonedDateTime =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone)

    private fun millis(date: String, time: String): Long = at(date, time).toInstant().toEpochMilli()

    // 2026-08-17 is a Monday; the rule fires Mondays at 22:00.
    private val rules = listOf(
        BlockSchedule("s", enabled = true, days = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(22, 0)),
    )

    @Test
    fun `alarm landing on time reports the occurrence that just came due`() {
        val pending = rules.pendingFire(now = at("2026-08-17", "22:00"), lastHandledMillis = null)
        assertEquals(at("2026-08-17", "22:00"), pending)
    }

    @Test
    fun `a fire missed inside the grace window is caught up`() {
        // Booted three hours late, well inside the eight-hour window.
        val pending = rules.pendingFire(now = at("2026-08-18", "01:00"), lastHandledMillis = null)
        assertEquals(at("2026-08-17", "22:00"), pending)
    }

    @Test
    fun `a fire missed outside the grace window is not caught up`() {
        // Booted nine hours late.
        assertNull(rules.pendingFire(now = at("2026-08-18", "07:00"), lastHandledMillis = null))
    }

    @Test
    fun `an already handled occurrence is not replayed`() {
        val pending = rules.pendingFire(
            now = at("2026-08-18", "01:00"),
            lastHandledMillis = millis("2026-08-17", "22:00"),
        )
        assertNull(pending)
    }

    @Test
    fun `a watermark from a previous cycle does not suppress a newer fire`() {
        val pending = rules.pendingFire(
            now = at("2026-08-17", "22:00"),
            lastHandledMillis = millis("2026-08-10", "22:00"),
        )
        assertEquals(at("2026-08-17", "22:00"), pending)
    }

    @Test
    fun `nothing pending when no rule has come round`() {
        assertNull(rules.pendingFire(now = at("2026-08-17", "09:00"), lastHandledMillis = null))
    }

    @Test
    fun `disabled rules contribute nothing`() {
        val disabled = rules.map { it.copy(enabled = false) }
        assertNull(disabled.pendingFire(now = at("2026-08-17", "22:00"), lastHandledMillis = null))
    }

    @Test
    fun `the most recent of several due occurrences wins`() {
        val two = rules + BlockSchedule(
            id = "t",
            enabled = true,
            days = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(20, 0),
        )
        val pending = two.pendingFire(now = at("2026-08-17", "23:00"), lastHandledMillis = null)
        assertEquals(at("2026-08-17", "22:00"), pending)
    }
}
