package com.monolith.app.data.repository

import com.monolith.app.data.backup.BackupCrypto
import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.data.backup.BackupSnapshotCodec
import com.monolith.app.data.leaderboard.MeResponse
import com.monolith.app.data.leaderboard.RegisterRequest
import com.monolith.app.data.leaderboard.RotateTokenRequest
import com.monolith.app.data.leaderboard.toDomain
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.FetchedBackup
import com.monolith.app.domain.repository.MeInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRepositoryTest {

    private val snapshot = BackupSnapshot(
        createdAt = 42,
        sessions = listOf(BackupSnapshot.SessionEntry(1, 2)),
        blockedPackages = listOf("com.example"),
        importantPeople = emptyList(),
        schedules = emptyList(),
        strictness = null,
    )

    private fun repo(api: FakeLeaderboardApi, store: FakeIdentityStore) = BackupRepositoryImpl(api, store, IdentityManager(api, store))

    private fun masterOf(identity: Identity?) = BackupCrypto.decodeCode(identity!!.master!!)!!

    @Test
    fun `ensureIdentity without identity registers a phone-generated token`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()

        val result = repo(api, store).ensureIdentity(" Ana ")

        assertEquals(LeaderboardResult.Ok(Unit), result)
        val identity = store.identity.value!!
        val token = BackupCrypto.deriveToken(masterOf(identity))
        assertEquals(RegisterRequest(token, "Ana"), api.registerRequests.single())
        assertEquals(Identity(token, "Ana", emptyList(), identity.master), identity)
    }

    @Test
    fun `ensureIdentity without a name registers a nameless one`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()

        repo(api, store).ensureIdentity(null)

        assertNull(api.registerRequests.single().displayName)
        assertEquals("", store.identity.value?.displayName)
    }

    @Test
    fun `legacy identity migrates to a master`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore(Identity("old", "Ana", listOf(g1.toDomain())))

        val result = repo(api, store).ensureIdentity(null)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        val identity = store.identity.value!!
        val token = BackupCrypto.deriveToken(masterOf(identity))
        assertEquals("old" to RotateTokenRequest(token), api.rotateCalls.single())
        assertEquals(Identity(token, "Ana", listOf(g1.toDomain()), identity.master), identity)
        assertTrue(api.registerRequests.isEmpty())
    }

    @Test
    fun `failed migration keeps the legacy identity`() = runBlocking {
        val legacy = Identity("old", "Ana", listOf(g1.toDomain()))
        val api = FakeLeaderboardApi().apply { rotateResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeIdentityStore(legacy)
        val repo = repo(api, store)

        val result = repo.ensureIdentity(null)

        assertEquals(LeaderboardResult.Err(LeaderboardError.NETWORK), result)
        assertEquals(legacy, store.identity.value)
        assertNull(repo.observeRecoveryCode().first())
    }

    @Test
    fun `a migration whose answer was lost is adopted on the next try`() = runBlocking {
        val legacy = Identity("old", "Ana", listOf(g1.toDomain()))
        val api = FakeLeaderboardApi().apply { rotateResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeIdentityStore(legacy)
        val repo = repo(api, store)
        repo.ensureIdentity(null)
        // The server did rotate: the old token is dead now, the new one works.
        val pending = api.rotateCalls.single().second.token
        api.deadTokens += "old"
        api.rotateResult = LeaderboardResult.Ok(Unit)

        val result = repo.ensureIdentity(null)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(listOf(pending, pending), api.rotateCalls.map { it.second.token })
        assertEquals(pending, api.meTokens.single())
        assertEquals(pending, store.identity.value?.token)
        assertEquals(pending, BackupCrypto.deriveToken(masterOf(store.identity.value)))
        assertEquals(listOf(g1.toDomain()), store.identity.value?.groups)
    }

    @Test
    fun `upload encrypts with the key from the master`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()
        val repo = repo(api, store)
        repo.setBackupEnabled(true)

        val result = repo.upload(snapshot, now = 1000)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        val identity = store.identity.value!!
        val (token, bytes) = api.putBackupCalls.single()
        assertEquals(identity.token, token)
        val key = BackupCrypto.deriveKey(masterOf(identity))
        assertEquals(snapshot, BackupSnapshotCodec.decode(BackupCrypto.decrypt(key, bytes)))
        assertEquals(1000L, store.lastBackupAt.value)
    }

    @Test
    fun `upload while disabled sends nothing`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore(Identity("tok", "Ana", emptyList(), BackupCrypto.encodeCode(BackupCrypto.newMaster())))

        repo(api, store).upload(snapshot, now = 1000)

        assertTrue(api.putBackupCalls.isEmpty())
        assertNull(store.lastBackupAt.value)
    }

    @Test
    fun `fetch with a bad code is UNAUTHORIZED and writes nothing`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()
        val repo = repo(api, store)

        assertEquals(LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED), repo.fetch("not a code"))
        assertEquals(0, api.calls)

        // A well-formed code nobody has.
        val unknown = BackupCrypto.newMaster()
        api.deadTokens += BackupCrypto.deriveToken(unknown)
        assertEquals(LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED), repo.fetch(BackupCrypto.encodeCode(unknown)))
        assertTrue(api.getBackupTokens.isEmpty())
        assertNull(store.identity.value)
        assertFalse(store.backupEnabled.value)
    }

    @Test
    fun `fetch without a backup returns me and a null snapshot`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1))) }
        val store = FakeIdentityStore()
        val master = BackupCrypto.newMaster()
        val code = BackupCrypto.encodeCode(master)

        val result = repo(api, store).fetch(" $code\n")

        assertEquals(LeaderboardResult.Ok(FetchedBackup(code, MeInfo("Ana", listOf(g1.toDomain()), null), null)), result)
        assertEquals(listOf(BackupCrypto.deriveToken(master)), api.meTokens)
        assertEquals(listOf(BackupCrypto.deriveToken(master)), api.getBackupTokens)
        assertNull(store.identity.value)
    }

    @Test
    fun `fetch decrypts the backup`() = runBlocking {
        val master = BackupCrypto.newMaster()
        val blob = BackupCrypto.encrypt(BackupCrypto.deriveKey(master), BackupSnapshotCodec.encode(snapshot))
        val api = FakeLeaderboardApi().apply {
            meResult = LeaderboardResult.Ok(MeResponse("Ana", emptyList(), backupAt = 9))
            getBackupResult = LeaderboardResult.Ok(blob)
        }

        val result = repo(api, FakeIdentityStore()).fetch(BackupCrypto.encodeCode(master))

        assertEquals(LeaderboardResult.Ok(FetchedBackup(BackupCrypto.encodeCode(master), MeInfo("Ana", emptyList(), 9), snapshot)), result)
    }

    @Test
    fun `fetch with an undecryptable blob is INVALID`() = runBlocking {
        val otherKey = BackupCrypto.deriveKey(BackupCrypto.newMaster())
        val api = FakeLeaderboardApi().apply {
            getBackupResult = LeaderboardResult.Ok(BackupCrypto.encrypt(otherKey, BackupSnapshotCodec.encode(snapshot)))
        }
        val store = FakeIdentityStore()

        val result = repo(api, store).fetch(BackupCrypto.encodeCode(BackupCrypto.newMaster()))

        assertEquals(LeaderboardResult.Err(LeaderboardError.INVALID), result)
        assertNull(store.identity.value)
    }

    @Test
    fun `disabling deletes the server copy and clears lastBackupAt`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore(Identity("tok", "Ana", listOf(g1.toDomain()), "m")).apply {
            backupEnabled.value = true
            lastBackupAt.value = 1000
        }

        val result = repo(api, store).setBackupEnabled(false)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(listOf("tok"), api.deleteBackupTokens)
        assertFalse(store.backupEnabled.value)
        assertNull(store.lastBackupAt.value)
        assertEquals("tok", store.identity.value?.token)
    }

    @Test
    fun `disabling a backup-only identity clears it quietly`() = runBlocking {
        // The server deletes a user with no group and no backup, so the check after is a 401.
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore(Identity("tok", "", emptyList(), "m")).apply { backupEnabled.value = true }
        api.meResult = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)

        val result = repo(api, store).setBackupEnabled(false)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertNull(store.identity.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `a failed delete keeps backup on`() = runBlocking {
        val api = FakeLeaderboardApi().apply { deleteBackupResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeIdentityStore(Identity("tok", "Ana", listOf(g1.toDomain()), "m")).apply {
            backupEnabled.value = true
            lastBackupAt.value = 1000
        }

        val result = repo(api, store).setBackupEnabled(false)

        assertEquals(LeaderboardResult.Err(LeaderboardError.NETWORK), result)
        assertTrue(store.backupEnabled.value)
        assertEquals(1000L, store.lastBackupAt.value)
    }

    @Test
    fun `enabling creates an identity`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()

        val result = repo(api, store).setBackupEnabled(true)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertTrue(store.backupEnabled.value)
        assertEquals(1, api.registerRequests.size)
    }
}
