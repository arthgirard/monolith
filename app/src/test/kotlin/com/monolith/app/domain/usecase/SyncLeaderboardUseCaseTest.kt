package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.Accrual
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.Pause
import com.monolith.app.domain.model.PauseType
import com.monolith.app.domain.model.SharedApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val outcome = SyncLeaderboardUseCase(blocks, leaderboard, FakeAppRepository())(now, zone)

        val (days, streakStartedAt) = leaderboard.syncCalls.single()
        assertEquals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), days.first().date)
        assertEquals(35, days.size)
        assertEquals(now - hour, streakStartedAt)
        assertEquals(1, days.sumOf { it.unlockCount })
        assertEquals(SyncOutcome(synced = true, pauseEndsAt = null), outcome)
        assertEquals(0L, leaderboard.syncedAccruals.single()?.resumesInMillis)
        assertEquals(true, leaderboard.syncedActive.single())
    }

    @Test
    fun `during an app unlock the streak is off and the pause end is returned`() = runBlocking {
        val now = System.currentTimeMillis()
        // grantAppUnlock fast-forwards the session clock past the unlock window.
        val blocks = FakeBlockRepository(initiallyActive = true).apply { setActiveSessionStart(now + 5 * 60_000) }
        val leaderboard = FakeLeaderboardRepository()

        val outcome = SyncLeaderboardUseCase(blocks, leaderboard, FakeAppRepository())(now, zone)

        assertNull(leaderboard.syncCalls.single().second)
        assertEquals(now + 5 * 60_000, outcome.pauseEndsAt)
        // Friends' boards resume on their own if the unlock runs out with the phone asleep.
        assertEquals(5 * 60_000L, leaderboard.syncedAccruals.single()?.resumesInMillis)
    }

    @Test
    fun `accrual carries the zone's offset and nothing while Monolith is off`() = runBlocking {
        val now = Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
        val montreal = ZoneId.of("America/Montreal")
        val on = FakeBlockRepository(initiallyActive = true).apply { setActiveSessionStart(now - hour) }
        val leaderboard = FakeLeaderboardRepository()

        SyncLeaderboardUseCase(on, leaderboard, FakeAppRepository())(now, montreal)
        SyncLeaderboardUseCase(FakeBlockRepository(), leaderboard, FakeAppRepository())(now, montreal)

        assertEquals(listOf(Accrual(0, -240), null), leaderboard.syncedAccruals)
    }

    @Test
    fun `a failed upload is reported as not synced`() = runBlocking {
        val leaderboard = FakeLeaderboardRepository().apply { syncResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }

        val outcome = SyncLeaderboardUseCase(FakeBlockRepository(), leaderboard, FakeAppRepository())(System.currentTimeMillis(), zone)

        assertFalse(outcome.synced)
    }

    @Test
    fun `uploads the blocked apps by label, leaving out any this phone can't name`() = runBlocking {
        val apps = FakeAppRepository(
            blocked = setOf("com.example.video", "com.example.chat", "com.example.gone"),
            labels = mapOf("com.example.video" to " Video ", "com.example.chat" to "chat"),
        )
        val leaderboard = FakeLeaderboardRepository()

        SyncLeaderboardUseCase(FakeBlockRepository(), leaderboard, apps)(System.currentTimeMillis(), zone)

        assertEquals(
            listOf(
                SharedApp("com.example.chat", "chat"),
                SharedApp("com.example.video", "Video"),
            ),
            leaderboard.syncedApps.single(),
        )
    }
}
