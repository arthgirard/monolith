package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ApplyPendingScheduleUseCaseTest {

    private val blockRepository = FakeBlockRepository()
    private val appUnlockRepository = FakeAppUnlockRepository()

    private fun applyFor(vararg schedules: BlockSchedule): Pair<ApplyPendingScheduleUseCase, FakeScheduleRepository> {
        val scheduleRepository = FakeScheduleRepository(schedules.toList())
        val useCase = ApplyPendingScheduleUseCase(
            scheduleRepository = scheduleRepository,
            activateScheduledBlock = ActivateScheduledBlockUseCase(
                activateBlockMode = ActivateBlockModeUseCase(blockRepository, appUnlockRepository),
                scheduleRepository = scheduleRepository,
            ),
        )
        return useCase to scheduleRepository
    }

    /**
     * Built from an offset off the real clock rather than a fixed time, so a rule "an hour ago"
     * stays an hour ago even when the test runs just after midnight.
     */
    private fun ruleOffsetHours(hours: Long, id: String = "s"): BlockSchedule {
        val moment = ZonedDateTime.now().plusHours(hours)
        return BlockSchedule(
            id = id,
            enabled = true,
            days = setOf(moment.dayOfWeek),
            startTime = moment.toLocalTime().withSecond(0).withNano(0),
        )
    }

    private suspend fun isActive(): Boolean = blockRepository.observeBlockState().first().isActive

    @Test
    fun `a fire missed an hour ago switches Monolith on`() = runBlocking {
        val (apply, schedules) = applyFor(ruleOffsetHours(-1))

        assertTrue("reports the transition so the caller can notify", apply())

        assertTrue(isActive())
        assertEquals(1, blockRepository.sessionStartCount)
        assertNotNull(schedules.lastHandledFire())
    }

    @Test
    fun `a rule that hasn't come round yet does nothing`() = runBlocking {
        val (apply, schedules) = applyFor(ruleOffsetHours(1))

        assertFalse(apply())

        assertFalse(isActive())
        assertNull(schedules.lastHandledFire())
    }

    @Test
    fun `a fire well outside the grace window is not caught up`() = runBlocking {
        // The grace window is eight hours; this one is a day old.
        val (apply, _) = applyFor(ruleOffsetHours(-24))

        apply()

        assertFalse(isActive())
    }

    @Test
    fun `the same occurrence is never applied twice`() = runBlocking {
        val (apply, _) = applyFor(ruleOffsetHours(-1))

        apply()
        blockRepository.setBlockModeActive(false) // as if the user tapped their tag
        apply()

        assertFalse("a handled occurrence must not reactivate after a tag tap", isActive())
        assertEquals(1, blockRepository.sessionStartCount)
    }

    @Test
    fun `a watermark stamped at edit time suppresses an occurrence from before the edit`() = runBlocking {
        // What SaveBlockScheduleUseCase does: adding a rule whose time already passed today must
        // not make Monolith switch itself on the next time the process starts.
        val (apply, schedules) = applyFor(ruleOffsetHours(-1))
        schedules.setLastHandledFire(System.currentTimeMillis())

        apply()

        assertFalse(isActive())
    }

    @Test
    fun `firing while already active leaves the running streak alone`() = runBlocking {
        val (apply, schedules) = applyFor(ruleOffsetHours(-1))
        blockRepository.setBlockModeActive(true)
        val startsBefore = blockRepository.sessionStartCount
        val clearsBefore = blockRepository.clearBypassCount

        assertFalse("no transition, so nothing to notify the user about", apply())

        assertTrue(isActive())
        assertEquals("session clock must not be restamped", startsBefore, blockRepository.sessionStartCount)
        assertEquals("a running bypass must survive", clearsBefore, blockRepository.clearBypassCount)
        assertNotNull("the occurrence is still marked handled", schedules.lastHandledFire())
    }

    @Test
    fun `activation starts a fresh cycle`() = runBlocking {
        val (apply, _) = applyFor(ruleOffsetHours(-1))

        apply()

        assertEquals(1, blockRepository.clearBypassCount)
        assertEquals(1, appUnlockRepository.clearUnlocksCount)
    }

    @Test
    fun `no schedules means nothing happens`() = runBlocking {
        val (apply, schedules) = applyFor()

        apply()

        assertFalse(isActive())
        assertNull(schedules.lastHandledFire())
    }
}
