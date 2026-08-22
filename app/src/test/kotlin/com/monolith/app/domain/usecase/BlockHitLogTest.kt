package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BlockHitLogTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val app = "com.example.blocked"
    private val other = "com.example.other"

    /** Local noon on [day], so tests aren't sensitive to which side of midnight they run. */
    private fun noon(day: LocalDate): Long =
        day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val today = LocalDate.of(2026, 8, 22)

    @Test
    fun `a first hit is recorded`() {
        val updated = BlockHitLog.record(emptyList(), app, noon(today))

        assertEquals(listOf(BlockHit(app, noon(today))), updated)
    }

    @Test
    fun `a repeat inside the dedupe window is dropped`() {
        val existing = listOf(BlockHit(app, noon(today)))

        val updated = BlockHitLog.record(existing, app, noon(today) + BlockHitLog.DEDUPE_MILLIS - 1)

        assertNull(updated)
    }

    @Test
    fun `a repeat after the dedupe window is recorded`() {
        val existing = listOf(BlockHit(app, noon(today)))

        val updated = BlockHitLog.record(existing, app, noon(today) + BlockHitLog.DEDUPE_MILLIS)

        assertNotNull(updated)
        assertEquals(2, updated!!.size)
    }

    @Test
    fun `the dedupe window is per package`() {
        // One reach re-firing for app must not swallow a genuine, separate reach for another app.
        val existing = listOf(BlockHit(app, noon(today)))

        val updated = BlockHitLog.record(existing, other, noon(today) + 1)

        assertNotNull(updated)
        assertEquals(2, updated!!.size)
    }

    @Test
    fun `hits past the retention window are pruned on write`() {
        val stale = BlockHit(app, noon(today) - BlockHitLog.RETENTION_MILLIS - 1)
        val fresh = BlockHit(other, noon(today) - 1000)

        val updated = BlockHitLog.record(listOf(stale, fresh), app, noon(today))

        assertNotNull(updated)
        assertEquals(listOf(fresh, BlockHit(app, noon(today))), updated)
    }

    @Test
    fun `only today's hits for that package are counted`() {
        val yesterday = today.minusDays(1)
        val hits = listOf(
            BlockHit(app, noon(yesterday)),
            BlockHit(app, noon(today)),
            BlockHit(app, noon(today) + 3_600_000),
            BlockHit(other, noon(today)),
        )

        assertEquals(2, BlockHitLog.countToday(hits, app, noon(today) + 7_200_000, zone))
    }

    @Test
    fun `the count rolls over at local midnight, not on a rolling 24 hours`() {
        // 23:30 yesterday is less than 24 hours before 00:30 today, but it belongs to the day
        // the user thinks of as yesterday, which is the day the wall is reporting on.
        val lateYesterday = today.minusDays(1).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val earlyToday = today.atTime(0, 30).atZone(zone).toInstant().toEpochMilli()

        val count = BlockHitLog.countToday(listOf(BlockHit(app, lateYesterday)), app, earlyToday, zone)

        assertEquals(0, count)
    }
}
