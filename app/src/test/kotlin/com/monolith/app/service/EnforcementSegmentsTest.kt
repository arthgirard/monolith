package com.monolith.app.service

import com.monolith.app.domain.model.AppUnlock
import com.monolith.app.domain.model.BlockState
import org.junit.Assert.assertEquals
import org.junit.Test

class EnforcementSegmentsTest {

    private val now = 1_700_000_000_000L
    private val app = "com.example.blocked"
    private val other = "com.example.other"

    @Test
    fun `enforcing has no countdown row`() {
        assertEquals(null, EnforcementSegments.rowFor(EnforcementStatus.Enforcing, now))
    }

    @Test
    fun `a whole bypass lights every minute of it`() {
        val status = EnforcementStatus.Bypass(now + BlockState.BYPASS_DURATION_MILLIS)

        assertEquals(SegmentRow(total = 15, lit = 15), EnforcementSegments.rowFor(status, now))
    }

    @Test
    fun `a whole app unlock lights every minute of it`() {
        val status = EnforcementStatus.AppUnlocked(app, now + AppUnlock.DURATION_MILLIS)

        assertEquals(SegmentRow(total = 5, lit = 5), EnforcementSegments.rowFor(status, now))
    }

    @Test
    fun `a part-used minute still burns its segment`() {
        // 3m01s left is still into the fourth minute, and rounding down would put the row a whole
        // minute ahead of the countdown beside it.
        val status = EnforcementStatus.AppUnlocked(app, now + 181_000L)

        assertEquals(4, EnforcementSegments.rowFor(status, now)?.lit)
    }

    @Test
    fun `the row is empty at expiry and never negative past it`() {
        assertEquals(0, EnforcementSegments.rowFor(EnforcementStatus.Bypass(now), now)?.lit)
        assertEquals(0, EnforcementSegments.rowFor(EnforcementStatus.Bypass(now - 60_000L), now)?.lit)
    }

    @Test
    fun `an expiry further out than its window clamps to the row's length`() {
        val status = EnforcementStatus.AppUnlocked(app, now + AppUnlock.DURATION_MILLIS * 3)

        assertEquals(SegmentRow(total = 5, lit = 5), EnforcementSegments.rowFor(status, now))
    }

    @Test
    fun `a running bypass outranks an app unlock`() {
        val state = BlockState(isActive = true, bypassExpiresAtMillis = now + 60_000L)

        val status = EnforcementSegments.statusFor(state, mapOf(app to now + 120_000L), now)

        assertEquals(EnforcementStatus.Bypass(now + 60_000L), status)
    }

    @Test
    fun `an expired bypass doesn't outrank a live unlock`() {
        // bypassExpiresAtMillis survives expiry -- it's what bypassUsed reads -- so a stale value
        // would otherwise keep the shade reporting a bypass that ended.
        val state = BlockState(isActive = true, bypassExpiresAtMillis = now - 1L)

        val status = EnforcementSegments.statusFor(state, mapOf(app to now + 120_000L), now)

        assertEquals(EnforcementStatus.AppUnlocked(app, now + 120_000L), status)
    }

    @Test
    fun `the unlock shown is the one expiring last`() {
        val unlocks = mapOf(app to now + 60_000L, other to now + 120_000L)

        val status = EnforcementSegments.statusFor(BlockState(isActive = true), unlocks, now)

        assertEquals(EnforcementStatus.AppUnlocked(other, now + 120_000L), status)
    }

    @Test
    fun `stale unlocks don't count as a breach`() {
        val status = EnforcementSegments.statusFor(BlockState(isActive = true), mapOf(app to now - 1L), now)

        assertEquals(EnforcementStatus.Enforcing, status)
    }

    @Test
    fun `the redraw loop sleeps to the next minute boundary, not a flat minute`() {
        assertEquals(4_000L, EnforcementSegments.millisToNextMinute(now + 664_000L, now))
    }

    @Test
    fun `on a boundary the redraw loop sleeps a whole minute`() {
        assertEquals(60_000L, EnforcementSegments.millisToNextMinute(now + 660_000L, now))
    }

    @Test
    fun `a young session redraws no faster than once a minute`() {
        // Five minutes in, four pixels' worth is a couple of seconds -- redrawing that often would
        // re-post the notification continuously for a bar nobody can see change.
        assertEquals(60_000L, EnforcementSegments.millisToNextPortraitStep(now, now + 5 * 60_000L))
    }

    @Test
    fun `a long session redraws no slower than every five minutes`() {
        val aDay = 24 * 60 * 60 * 1000L

        assertEquals(5 * 60_000L, EnforcementSegments.millisToNextPortraitStep(now, now + aDay))
    }

    @Test
    fun `between those the step scales with the session's own length`() {
        // Four hours in, four pixels of a 960-wide bar is a minute of it.
        val fourHours = 4 * 60 * 60 * 1000L

        assertEquals(60_000L, EnforcementSegments.millisToNextPortraitStep(now, now + fourHours))
    }

    @Test
    fun `a clock behind the cycle start still yields a sane step`() {
        assertEquals(60_000L, EnforcementSegments.millisToNextPortraitStep(now, now - 1000L))
    }

    @Test
    fun `past expiry the redraw loop has nothing left to wait for`() {
        assertEquals(0L, EnforcementSegments.millisToNextMinute(now - 1L, now))
    }
}
