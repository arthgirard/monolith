package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.Pause
import com.monolith.app.domain.model.PauseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyAggregatesTest {

    // TimeSavedCalculator buckets in the system zone, so the test builds its instants there too.
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 9, 28)
    private val hour = 60 * 60 * 1000L

    private fun at(date: LocalDate, hourOfDay: Int, minute: Int = 0): Long =
        date.atTime(hourOfDay, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `builds 35 days newest first`() {
        val days = DailyAggregates.build(emptyList(), emptyList(), emptyList(), today, zone)

        assertEquals(35, days.size)
        assertEquals(today, days.first().date)
        assertEquals(today.minusDays(34), days.last().date)
    }

    @Test
    fun `a session over midnight is split between the two days`() {
        val yesterday = today.minusDays(1)
        val session = BlockSession(at(yesterday, 23), at(today, 1))

        val days = DailyAggregates.build(listOf(session), emptyList(), emptyList(), today, zone)

        assertEquals(2 * hour, days.sumOf { it.savedMillis })
        assertEquals(hour, days[0].savedMillis)
        assertEquals(hour, days[1].savedMillis)
    }

    @Test
    fun `ongoing time counts today`() {
        val ongoing = listOf(BlockSession(at(today, 9), at(today, 11)))

        val days = DailyAggregates.build(emptyList(), ongoing, emptyList(), today, zone)

        assertEquals(2 * hour, days[0].savedMillis)
    }

    @Test
    fun `pauses are counted per local day by type`() {
        val pauses = listOf(
            Pause(PauseType.BYPASS, at(today, 8)),
            Pause(PauseType.UNLOCK, at(today, 9)),
            Pause(PauseType.UNLOCK, at(today, 10)),
            Pause(PauseType.BYPASS, at(today.minusDays(2), 12)),
            Pause(PauseType.BYPASS, at(today.minusDays(40), 12)),
        )

        val days = DailyAggregates.build(emptyList(), emptyList(), pauses, today, zone)

        assertEquals(1, days[0].bypassCount)
        assertEquals(2, days[0].unlockCount)
        assertEquals(1, days[2].bypassCount)
        assertEquals(2, days.sumOf { it.bypassCount })
    }

    @Test
    fun `streak start is now minus the ongoing duration`() {
        val now = at(today, 12)
        val ongoing = listOf(BlockSession(at(today, 10), now))

        assertEquals(at(today, 10), DailyAggregates.streakStartedAt(ongoing, now))
        assertNull(DailyAggregates.streakStartedAt(emptyList(), now))
    }

    @Test
    fun `pause end is the later of a future session start and a live bypass expiry`() {
        val now = 1_000_000L
        val active = BlockState(isActive = true, bypassExpiresAtMillis = now + 500)

        assertEquals(now + 900, DailyAggregates.pauseEndsAt(active, now + 900, now))
        assertEquals(now + 500, DailyAggregates.pauseEndsAt(active, now - 10, now))
        assertNull(DailyAggregates.pauseEndsAt(BlockState(isActive = true), now - 10, now))
        assertNull(DailyAggregates.pauseEndsAt(BlockState(isActive = false), now + 900, now))
    }
}
