package com.monolith.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class BlockScheduleTest {

    private val zone: ZoneId = ZoneId.of("Europe/Lisbon")

    private fun at(date: String, time: String, z: ZoneId = zone): ZonedDateTime =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), z)

    private fun schedule(
        days: Set<DayOfWeek>,
        time: String = "22:00",
        enabled: Boolean = true,
    ) = BlockSchedule(id = "s", enabled = enabled, days = days, startTime = LocalTime.parse(time))

    // 2026-08-17 is a Monday.
    private val monday = "2026-08-17"
    private val tuesday = "2026-08-18"
    private val friday = "2026-08-21"

    @Test
    fun `next fire is later the same day`() {
        val rule = schedule(setOf(DayOfWeek.MONDAY))
        assertEquals(at(monday, "22:00"), rule.nextFireAfter(at(monday, "09:00")))
    }

    @Test
    fun `next fire wraps to the following week once today's time has passed`() {
        val rule = schedule(setOf(DayOfWeek.MONDAY))
        assertEquals(at("2026-08-24", "22:00"), rule.nextFireAfter(at(monday, "23:00")))
    }

    @Test
    fun `next fire picks the nearest selected day`() {
        val rule = schedule(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(at(friday, "22:00"), rule.nextFireAfter(at(tuesday, "08:00")))
    }

    @Test
    fun `an occurrence exactly now is not after now`() {
        val rule = schedule(setOf(DayOfWeek.MONDAY))
        assertEquals(at("2026-08-24", "22:00"), rule.nextFireAfter(at(monday, "22:00")))
    }

    @Test
    fun `a disabled rule never fires`() {
        val rule = schedule(setOf(DayOfWeek.MONDAY), enabled = false)
        assertNull(rule.nextFireAfter(at(monday, "09:00")))
        assertNull(rule.lastFireAtOrBefore(at(monday, "23:00")))
    }

    @Test
    fun `a rule with no days never fires`() {
        val rule = schedule(emptySet())
        assertNull(rule.nextFireAfter(at(monday, "09:00")))
        assertNull(rule.lastFireAtOrBefore(at(monday, "23:00")))
    }

    @Test
    fun `last fire at or before includes an occurrence exactly now`() {
        val rule = schedule(setOf(DayOfWeek.MONDAY))
        assertEquals(at(monday, "22:00"), rule.lastFireAtOrBefore(at(monday, "22:00")))
    }

    @Test
    fun `last fire walks back to the previous selected day`() {
        val rule = schedule(setOf(DayOfWeek.MONDAY))
        assertEquals(at(monday, "22:00"), rule.lastFireAtOrBefore(at(tuesday, "07:00")))
    }

    @Test
    fun `spring forward gap resolves to a real instant`() {
        // Lisbon springs forward 2026-03-29: 01:00 jumps straight to 02:00, so 01:30 doesn't exist.
        val rule = schedule(setOf(DayOfWeek.SUNDAY), time = "01:30")
        val fire = rule.nextFireAfter(at("2026-03-29", "00:00"))!!
        assertEquals(LocalTime.of(2, 30), fire.toLocalTime())
    }

    @Test
    fun `earliest next fire folds across rules`() {
        val rules = listOf(
            schedule(setOf(DayOfWeek.FRIDAY), time = "09:00"),
            schedule(setOf(DayOfWeek.TUESDAY), time = "20:00"),
        )
        assertEquals(at(tuesday, "20:00"), rules.earliestNextFire(at(monday, "23:00")))
    }

    @Test
    fun `earliest next fire is null when nothing is armed`() {
        val rules = listOf(schedule(setOf(DayOfWeek.MONDAY), enabled = false), schedule(emptySet()))
        assertNull(rules.earliestNextFire(at(monday, "09:00")))
    }

    @Test
    fun `latest missed fire finds an occurrence inside the window`() {
        val rules = listOf(schedule(setOf(DayOfWeek.MONDAY)))
        val missed = rules.latestMissedFire(now = at(tuesday, "02:00"), after = at(monday, "18:00"))
        assertEquals(at(monday, "22:00"), missed)
    }

    @Test
    fun `latest missed fire ignores an occurrence at or before the lower bound`() {
        val rules = listOf(schedule(setOf(DayOfWeek.MONDAY)))
        assertNull(rules.latestMissedFire(now = at(tuesday, "02:00"), after = at(monday, "22:00")))
    }

    @Test
    fun `latest missed fire takes the most recent of several rules`() {
        val rules = listOf(
            schedule(setOf(DayOfWeek.MONDAY), time = "09:00"),
            schedule(setOf(DayOfWeek.MONDAY), time = "21:00"),
        )
        val missed = rules.latestMissedFire(now = at(tuesday, "02:00"), after = at(monday, "00:00"))
        assertEquals(at(monday, "21:00"), missed)
    }
}
