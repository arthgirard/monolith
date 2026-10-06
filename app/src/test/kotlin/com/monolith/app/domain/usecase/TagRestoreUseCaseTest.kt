package com.monolith.app.domain.usecase

import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.FetchedBackup
import com.monolith.app.domain.repository.MeInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagRestoreUseCaseTest {

    private val tag = allocateTag()
    private val code = "c".repeat(43)
    private val me = MeInfo("Ana", emptyList(), backupAt = 1_700_000_100_000L)
    private val snapshot = BackupSnapshot(
        createdAt = 1_700_000_000_000L,
        sessions = emptyList(),
        blockedPackages = emptyList(),
        importantPeople = emptyList(),
        schedules = emptyList(),
        strictness = null,
    )

    private class Harness(codeOnTag: String?, fetch: LeaderboardResult<FetchedBackup>) {
        val provisioner = FakeTagProvisioner(codeOnTag = codeOnTag)
        val blocks = FakeBlockRepository(initiallyActive = false, linkedTag = null)
        val backup = FakeBackupRepository(fetch)
        val leaderboard = FakeLeaderboardRepository()
        val writer = RecordingWriter()
        val restore = RestoreBackupUseCase(blocks, backup, leaderboard, writer, CountingAfterRestore(), FakeAppRepository())
        val useCase = TagRestoreUseCase(provisioner, blocks, restore)
    }

    private fun fetched(snapshot: BackupSnapshot?) = LeaderboardResult.Ok(FetchedBackup(code, me, snapshot))

    @Test
    fun `a tag without a code has nothing to offer`() = runBlocking {
        val h = Harness(codeOnTag = null, fetch = fetched(snapshot))

        assertEquals(TagCheck.NoCode, h.useCase.check(tag))
        assertTrue("no code, no network", h.backup.fetchCalls.isEmpty())
    }

    @Test
    fun `a code with a backup is offered with its date`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(snapshot))

        assertEquals(TagCheck.Offer(backupAt = me.backupAt, hasBackup = true), h.useCase.check(tag))
    }

    @Test
    fun `a code with only friends is offered without a backup`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(null))

        assertEquals(TagCheck.Offer(backupAt = me.backupAt, hasBackup = false), h.useCase.check(tag))
    }

    @Test
    fun `a code whose identity is gone reads as a blank tag`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED))

        assertEquals(TagCheck.NoCode, h.useCase.check(tag))
    }

    @Test
    fun `offline is never mistaken for a blank tag`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = LeaderboardResult.Err(LeaderboardError.NETWORK))
        assertEquals(TagCheck.Unreachable, h.useCase.check(tag))

        h.backup.fetchResult = LeaderboardResult.Err(LeaderboardError.SERVER)
        assertEquals(TagCheck.Unreachable, h.useCase.check(tag))
    }

    @Test
    fun `an unreadable backup is reported`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = LeaderboardResult.Err(LeaderboardError.INVALID))

        assertEquals(TagCheck.Unreadable, h.useCase.check(tag))
    }

    @Test
    fun `recheck asks again with the same code`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = LeaderboardResult.Err(LeaderboardError.NETWORK))
        h.useCase.check(tag)

        h.backup.fetchResult = fetched(snapshot)
        assertEquals(TagCheck.Offer(backupAt = me.backupAt, hasBackup = true), h.useCase.recheck())
        assertEquals(listOf(code, code), h.backup.fetchCalls)
    }

    @Test
    fun `restore writes the backup and links the tag without writing to it`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(snapshot))
        h.useCase.check(tag)

        assertEquals(TagRestoreResult.Done(hasBackup = true), h.useCase.restore())
        assertEquals(1, h.writer.writes.size)
        assertEquals(h.provisioner.existingLink(tag, code), h.blocks.observeLinkedTag().first())
        assertEquals("the tag already carries this code", 0, h.provisioner.provisionCount)
    }

    @Test
    fun `a restore that fails after the offer links nothing`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(snapshot))
        h.useCase.check(tag)
        h.leaderboard.restoreResult = LeaderboardResult.Err(LeaderboardError.NETWORK)

        assertEquals(TagRestoreResult.Failed, h.useCase.restore())
        assertNull(h.blocks.observeLinkedTag().first())
        assertTrue(h.writer.writes.isEmpty())
    }

    @Test
    fun `restore without a check does nothing`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(snapshot))

        assertEquals(TagRestoreResult.Failed, h.useCase.restore())
        assertNull(h.blocks.observeLinkedTag().first())
    }
}
