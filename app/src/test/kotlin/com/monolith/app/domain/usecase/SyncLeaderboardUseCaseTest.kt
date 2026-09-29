package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.Pause
import com.monolith.app.domain.model.PauseType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SyncLeaderboardUseCaseTest {

    private val zone = ZoneId.systemDefault()
    private val hour = 60 * 60 * 1000L

    @Test
    fun `uploads today's ongoing time, pauses and streak start`() = runBlocking {
        val now = System.currentTimeMillis()
        val blocks = FakeBlockRepository(initiallyActive = true).apply {
            setActiveSessionStart(now - hour)
            pauses.value = listOf(Pause(PauseType.UNLOCK, now - 2 * hour))
        }
        val leaderboard = FakeLeaderboardRepository()

        val pauseEnd = SyncLeaderboardUseCase(blocks, leaderboard)(now, zone)

        val (days, streakStartedAt) = leaderboard.syncCalls.single()
        assertEquals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), days.first().date)
        assertEquals(35, days.size)
        assertEquals(now - hour, streakStartedAt)
        assertEquals(1, days.sumOf { it.unlockCount })
        assertNull(pauseEnd)
    }

    @Test
    fun `during an app unlock the streak is off and the pause end is returned`() = runBlocking {
        val now = System.currentTimeMillis()
        // grantAppUnlock fast-forwards the session clock past the unlock window.
        val blocks = FakeBlockRepository(initiallyActive = true).apply { setActiveSessionStart(now + 5 * 60_000) }
        val leaderboard = FakeLeaderboardRepository()

        val pauseEnd = SyncLeaderboardUseCase(blocks, leaderboard)(now, zone)

        assertNull(leaderboard.syncCalls.single().second)
        assertEquals(now + 5 * 60_000, pauseEnd)
    }
}
