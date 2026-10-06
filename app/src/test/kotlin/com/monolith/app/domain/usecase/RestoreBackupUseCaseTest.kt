package com.monolith.app.domain.usecase

import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.FetchedBackup
import com.monolith.app.domain.repository.MeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreBackupUseCaseTest {

    private val code = "c".repeat(43)
    private val snapshot = BackupSnapshot(
        createdAt = 1_700_000_000_000L,
        sessions = listOf(BackupSnapshot.SessionEntry(1L, 2L)),
        blockedPackages = listOf("com.example.feed"),
        importantPeople = emptyList(),
        schedules = emptyList(),
        strictness = "DEFAULT",
    )
    private val me = MeInfo("Ana", emptyList(), backupAt = 1_700_000_100_000L)

    private class Harness(active: Boolean, fetch: LeaderboardResult<FetchedBackup>) {
        val blocks = FakeBlockRepository(initiallyActive = active)
        val backup = FakeBackupRepository(fetch)
        val leaderboard = FakeLeaderboardRepository()
        val writer = RecordingWriter()
        val after = CountingAfterRestore()
        val useCase = RestoreBackupUseCase(blocks, backup, leaderboard, writer, after)

        fun assertNothingWritten() {
            assertTrue(writer.writes.isEmpty())
            assertTrue(leaderboard.restoreCalls.isEmpty())
            assertTrue(backup.enabledCalls.isEmpty())
            assertEquals(0, after.runs)
        }
    }

    private fun fetched(snapshot: BackupSnapshot?) = LeaderboardResult.Ok(FetchedBackup(code, me, snapshot))

    @Test
    fun `refuses while active and writes nothing`() = runBlocking {
        val h = Harness(active = true, fetch = fetched(snapshot))

        assertEquals(RestoreOutcome.Refused, h.useCase.prepare(code))
        assertTrue(h.backup.fetchCalls.isEmpty())
        h.assertNothingWritten()
    }

    @Test
    fun `bad code fails and writes nothing`() = runBlocking {
        val h = Harness(active = false, fetch = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED))

        assertEquals(RestoreOutcome.Failed(LeaderboardError.UNAUTHORIZED), h.useCase.prepare("nope"))
        // Nothing was prepared, so a confirm has nothing to write either.
        assertTrue(h.useCase.confirm() is RestoreOutcome.Failed)
        h.assertNothingWritten()
    }

    @Test
    fun `unreadable backup writes nothing`() = runBlocking {
        val h = Harness(active = false, fetch = LeaderboardResult.Err(LeaderboardError.INVALID))

        assertEquals(RestoreOutcome.Failed(LeaderboardError.INVALID), h.useCase.prepare(code))
        h.assertNothingWritten()
    }

    @Test
    fun `confirm writes the snapshot, saves the identity and runs afterRestore`() = runBlocking {
        val h = Harness(active = false, fetch = fetched(snapshot))

        assertEquals(RestoreOutcome.Ready(backupAt = me.backupAt, hasBackup = true), h.useCase.prepare(code))
        h.assertNothingWritten()

        assertEquals(RestoreOutcome.Done, h.useCase.confirm())
        assertEquals(listOf(snapshot), h.writer.writes)
        assertEquals(listOf(code), h.leaderboard.restoreCalls)
        assertEquals(listOf(true), h.backup.enabledCalls)
        assertEquals(1, h.after.runs)
    }

    @Test
    fun `confirm re-checks active`() = runBlocking {
        val h = Harness(active = false, fetch = fetched(snapshot))

        assertTrue(h.useCase.prepare(code) is RestoreOutcome.Ready)
        h.blocks.setBlockModeActive(true)

        assertEquals(RestoreOutcome.Refused, h.useCase.confirm())
        h.assertNothingWritten()
    }

    @Test
    fun `no backup restores only the identity`() = runBlocking {
        val h = Harness(active = false, fetch = fetched(null))

        assertEquals(RestoreOutcome.Ready(backupAt = me.backupAt, hasBackup = false), h.useCase.prepare(code))

        assertEquals(RestoreOutcome.Done, h.useCase.confirm())
        assertTrue(h.writer.writes.isEmpty())
        assertEquals(listOf(code), h.leaderboard.restoreCalls)
        assertTrue(h.backup.enabledCalls.isEmpty())
        assertEquals(0, h.after.runs)
    }

    @Test
    fun `a failed identity save on confirm writes no snapshot`() = runBlocking {
        val h = Harness(active = false, fetch = fetched(snapshot))
        h.leaderboard.restoreResult = LeaderboardResult.Err(LeaderboardError.NETWORK)

        assertTrue(h.useCase.prepare(code) is RestoreOutcome.Ready)

        assertEquals(RestoreOutcome.Failed(LeaderboardError.NETWORK), h.useCase.confirm())
        assertTrue(h.writer.writes.isEmpty())
        assertTrue(h.backup.enabledCalls.isEmpty())
        assertEquals(0, h.after.runs)
    }
}
