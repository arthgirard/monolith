package com.monolith.app.service

import com.monolith.app.domain.model.GroupMembership
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.usecase.FakeBlockRepository
import com.monolith.app.domain.usecase.FakeLeaderboardRepository
import com.monolith.app.domain.usecase.SyncLeaderboardUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LeaderboardSyncerTest {

    private val member = GroupMembership("tok", "Ana", "ABCDEFGH", ShareSettings(saved = true, streak = true, pauses = true))

    private fun syncerFor(leaderboard: FakeLeaderboardRepository): LeaderboardSyncer {
        val blocks = FakeBlockRepository()
        return LeaderboardSyncer(blocks, leaderboard, SyncLeaderboardUseCase(blocks, leaderboard))
    }

    @Test
    fun `an immediate request uploads well before the debounce and stamps the sync time`() = runBlocking {
        val leaderboard = FakeLeaderboardRepository(member)
        val syncer = syncerFor(leaderboard)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            syncer.start(scope)
            // The debounce is 5 s; anything under that came through the immediate path. The
            // request repeats until the collector has subscribed, since requests are not replayed.
            withTimeout(2_000) {
                while (syncer.lastSyncedAt.value == null) {
                    syncer.requestSync(immediate = true)
                    delay(20)
                }
            }
            assertNotNull(syncer.lastSyncedAt.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a refused upload leaves the sync time alone`() = runBlocking {
        val leaderboard = FakeLeaderboardRepository(member).apply { syncResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val syncer = syncerFor(leaderboard)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            syncer.start(scope)
            withTimeout(2_000) {
                while (leaderboard.syncCalls.isEmpty()) {
                    syncer.requestSync(immediate = true)
                    delay(20)
                }
            }
            delay(200) // Let the upload's outcome land.
            assertNull(syncer.lastSyncedAt.value)
        } finally {
            scope.cancel()
        }
    }
}
