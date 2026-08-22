package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wall's "Held" readout and the waiver sentence's streak both come from here. They used to
 * be computed independently, which is exactly the kind of pair that drifts: a wall reading
 * "Held 4h 12m" over a sentence naming a streak of three hours reads as a bug in the app rather
 * than as two views of one number.
 */
class GetCurrentStreakUseCaseTest {

    private val blockRepository = FakeBlockRepository(initiallyActive = true)
    private val getCurrentStreak = GetCurrentStreakUseCase(blockRepository)

    @Test
    fun `an inactive Monolith has no streak`() = runBlocking {
        val idle = FakeBlockRepository(initiallyActive = false)

        assertEquals(0L, GetCurrentStreakUseCase(idle).current())
    }

    @Test
    fun `the streak matches what TimeSavedCalculator credits for the same inputs`() = runBlocking {
        val now = System.currentTimeMillis()
        val startedAt = now - 90 * 60 * 1000
        val expected = TimeSavedCalculator
            .ongoingSessions(BlockState(isActive = true), startedAt, now)
            .sumOf { it.durationMillis }

        blockRepository.setActiveSessionStart(startedAt)

        assertEquals(expected, getCurrentStreak.current(now))
    }
}
