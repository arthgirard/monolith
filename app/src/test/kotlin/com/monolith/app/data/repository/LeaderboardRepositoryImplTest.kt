package com.monolith.app.data.repository

import com.monolith.app.data.backup.BackupCrypto
import com.monolith.app.data.leaderboard.CreateGroupRequest
import com.monolith.app.data.leaderboard.GroupDto
import com.monolith.app.data.leaderboard.GroupResponse
import com.monolith.app.data.leaderboard.MeResponse
import com.monolith.app.data.leaderboard.RegisterRequest
import com.monolith.app.data.leaderboard.ShareDto
import com.monolith.app.data.leaderboard.UpdateGroupRequest
import com.monolith.app.data.leaderboard.toDomain
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LeaderboardRepositoryImplTest {

    private val share = ShareSettings(saved = true, streak = true, pauses = true)
    private val day = DayAggregate(LocalDate.of(2026, 9, 28), 1000, 1, 0)
    private val today = LocalDate.of(2026, 9, 28)
    private fun ana(vararg groups: GroupDto, token: String = "tok") = Identity(token, "Ana", groups.map { it.toDomain() }, master = "m")
    private fun repo(api: FakeLeaderboardApi, store: FakeIdentityStore, names: FakeDisplayNameStore = FakeDisplayNameStore()) =
        LeaderboardRepositoryImpl(api, store, IdentityManager(api, store, names), names)

    private fun repo(api: FakeLeaderboardApi, store: FakeIdentityStore, name: String) = repo(api, store, FakeDisplayNameStore(name))

    @Test
    fun `create without identity registers a phone-generated one and selects the new group`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1))) }
        val store = FakeIdentityStore()

        val result = repo(api, store, " Ana ").createGroup(share)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        val identity = store.identity.value!!
        val token = BackupCrypto.deriveToken(BackupCrypto.decodeCode(identity.master!!)!!)
        assertEquals(RegisterRequest(token, "Ana"), api.registerRequests.single())
        assertEquals(Identity(token, "Ana", listOf(g1.toDomain()), identity.master), identity)
        assertEquals("g1", store.selectedGroupId.value)
        // Registered with the name already: the create body carries none.
        assertEquals(token to CreateGroupRequest(null, ShareDto(true, true, true)), api.createCalls.single())
    }

    @Test
    fun `create sends the bearer and the name only for a nameless identity`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1))) }
        val store = FakeIdentityStore(Identity("tok", "", emptyList(), master = "m"))

        val result = repo(api, store, " Ana ").createGroup(share)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals("tok" to CreateGroupRequest("Ana", ShareDto(true, true, true)), api.createCalls.single())
        assertTrue(api.registerRequests.isEmpty())
        assertEquals(Identity("tok", "Ana", listOf(g1.toDomain()), "m"), store.identity.value)

        repo(api, store, "Ignored").joinGroup("JKLMNPQR", share)

        val (token, request) = api.joinCalls.single()
        assertEquals("tok", token)
        assertNull(request.displayName)
    }

    @Test
    fun `create with identity sends the token and no display name`() = runBlocking {
        val api = FakeLeaderboardApi().apply {
            createResult = LeaderboardResult.Ok(GroupResponse(group = g2))
            meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1, g2)))
        }
        val store = FakeIdentityStore(ana(g1), selected = "g1")

        repo(api, store, "Ignored").createGroup(share)

        val (token, request) = api.createCalls.single()
        assertEquals("tok", token)
        assertNull(request.displayName)
        assertEquals(ana(g1, g2), store.identity.value)
        assertEquals("g2", store.selectedGroupId.value)
    }

    @Test
    fun `create while offline saves nothing`() = runBlocking {
        val api = FakeLeaderboardApi().apply { registerResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeIdentityStore()

        val result = repo(api, store, "Ana").createGroup(share)

        assertEquals(LeaderboardResult.Err(LeaderboardError.NETWORK), result)
        assertNull(store.identity.value)
        assertNull(store.selectedGroupId.value)
    }

    @Test
    fun `restore derives the token from the code and brings every group back`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1, g2), backupAt = 7)) }
        val store = FakeIdentityStore()
        val master = BackupCrypto.newMaster()
        val code = BackupCrypto.encodeCode(master)
        val token = BackupCrypto.deriveToken(master)

        val result = repo(api, store).restore("  $code \n")

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(token, api.meTokens.single())
        assertEquals(Identity(token, "Ana", listOf(g1.toDomain(), g2.toDomain()), code, backupAt = 7), store.identity.value)
        assertEquals("g1", store.selectedGroupId.value)
    }

    @Test
    fun `restoring another identity never carries backup settings over`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Bea", listOf(g1))) }
        // A backup-only identity with backup on.
        val store = FakeIdentityStore(Identity("a", "", emptyList(), "m")).apply {
            backupEnabled.value = true
            lastBackupAt.value = 1000
        }

        val result = repo(api, store).restore(BackupCrypto.encodeCode(BackupCrypto.newMaster()))

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals("Bea", store.identity.value?.displayName)
        assertFalse(store.backupEnabled.value)
        assertNull(store.lastBackupAt.value)
    }

    @Test
    fun `restoring the same identity keeps its backup settings`() = runBlocking {
        val master = BackupCrypto.newMaster()
        val code = BackupCrypto.encodeCode(master)
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1))) }
        val store = FakeIdentityStore(Identity(BackupCrypto.deriveToken(master), "Ana", emptyList(), code)).apply {
            backupEnabled.value = true
            lastBackupAt.value = 1000
        }

        repo(api, store).restore(code)

        assertTrue(store.backupEnabled.value)
        assertEquals(1000L, store.lastBackupAt.value)
    }

    @Test
    fun `the retry after a dead token keeps the old name when setup gave none`() = runBlocking {
        val api = FakeLeaderboardApi().apply {
            deadTokens += "old"
            meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1)))
        }
        val store = FakeIdentityStore(ana(token = "old"))

        val result = repo(api, store).createGroup(share)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals("Ana", api.registerRequests.single().displayName)
        assertEquals("Ana", store.identity.value?.displayName)
    }

    @Test
    fun `restore with a malformed code is UNAUTHORIZED and skips the network`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()

        val result = repo(api, store).restore("tok")

        assertEquals(LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED), result)
        assertTrue(api.meTokens.isEmpty())
        assertNull(store.identity.value)
    }

    @Test
    fun `sync filters by the union of group shares`() = runBlocking {
        val api = FakeLeaderboardApi()

        repo(api, FakeIdentityStore(ana(g1, g2))).sync(listOf(day), streakStartedAt = 5)

        val request = api.syncRequests.single()
        assertEquals(1000L, request.days.single().savedMs)
        assertEquals(1, request.days.single().bypassCount)
        assertNull(request.streakStartedAt)
    }

    @Test
    fun `hidden_signal refreshes groups and retries once`() = runBlocking {
        val narrower = g1.copy(share = ShareDto(saved = false, streak = false, pauses = true))
        val api = FakeLeaderboardApi().apply {
            syncResults += LeaderboardResult.Err(LeaderboardError.HIDDEN_SIGNAL)
            meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(narrower)))
        }
        val store = FakeIdentityStore(ana(g1), selected = "g1")

        val result = repo(api, store).sync(listOf(day), null)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(2, api.syncRequests.size)
        assertEquals(1000L, api.syncRequests[0].days.single().savedMs)
        assertNull(api.syncRequests[1].days.single().savedMs)
        assertEquals(1, api.syncRequests[1].days.single().bypassCount)
        assertEquals(listOf(narrower.toDomain()), store.identity.value?.groups)
    }

    @Test
    fun `not_member on a board drops the group`() = runBlocking {
        val api = FakeLeaderboardApi().apply {
            boardResult = LeaderboardResult.Err(LeaderboardError.NOT_MEMBER)
            meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g2)))
        }
        val store = FakeIdentityStore(ana(g1, g2), selected = "g1")

        val result = repo(api, store).board("g1", BoardWindow.WEEK, today)

        assertEquals(LeaderboardResult.Err(LeaderboardError.NOT_MEMBER), result)
        assertEquals(listOf(g2.toDomain()), store.identity.value?.groups)
        assertEquals("g2", store.selectedGroupId.value)
    }

    @Test
    fun `leaving the last group clears without a notice`() = runBlocking {
        // The server deletes a user who leaves their last group, so the refresh after it is a 401.
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED) }
        val store = FakeIdentityStore(ana(g1, g2), selected = "g1")

        val result = repo(api, store).leaveGroup("g1")

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(listOf("g1"), api.leftGroups)
        assertNull(store.identity.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `leaving what the cache thinks is the last group keeps a group joined elsewhere`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g2))) }
        val store = FakeIdentityStore(ana(g1), selected = "g1")

        val result = repo(api, store).leaveGroup("g1")

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(ana(g2), store.identity.value)
        assertEquals("g2", store.selectedGroupId.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `sync without any cached group uploads nothing`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore(ana())

        val result = repo(api, store).sync(listOf(day), streakStartedAt = 5)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertTrue(api.syncRequests.isEmpty())
    }

    @Test
    fun `a group entered by a dead token selects nothing`() = runBlocking {
        val api = FakeLeaderboardApi().apply {
            createResult = LeaderboardResult.Ok(GroupResponse(group = g2))
            meResult = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        }
        val store = FakeIdentityStore(ana(g1), selected = "g1")

        repo(api, store).createGroup(share)

        assertNull(store.identity.value)
        assertNull(store.selectedGroupId.value)
    }

    @Test
    fun `leaving one of two keeps the identity`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g2))) }
        val store = FakeIdentityStore(ana(g1, g2), selected = "g1")

        repo(api, store).leaveGroup("g1")

        assertEquals(ana(g2), store.identity.value)
        assertEquals("g2", store.selectedGroupId.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `401 clears with a notice`() = runBlocking {
        val api = FakeLeaderboardApi().apply { syncResults += LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED) }
        val store = FakeIdentityStore(ana(g1), selected = "g1")
        val repo = repo(api, store)

        val result = repo.sync(listOf(day), null)

        assertEquals(LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED), result)
        assertNull(store.identity.value)
        assertTrue(store.removedNotice.value)
        repo.dismissRemovedNotice()
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `a late 401 does not clear a newer identity`() = runBlocking {
        val newer = ana(g2, token = "tok2")
        val store = FakeIdentityStore(ana(g1))
        val api = FakeLeaderboardApi().apply {
            boardResult = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
            duringBoard = { store.save(newer) }
        }

        repo(api, store).board("g1", BoardWindow.WEEK, today)

        assertEquals(newer, store.identity.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `calls without an identity are UNAUTHORIZED and skip the network`() = runBlocking {
        val api = FakeLeaderboardApi()

        val result = repo(api, FakeIdentityStore()).sync(listOf(day), null)

        assertEquals(LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED), result)
        assertEquals(0, api.syncRequests.size)
    }

    @Test
    fun `updateGroup replaces that group in the cache`() = runBlocking {
        val renamed = g1.copy(name = "Flat", share = ShareDto(true, true, true))
        val api = FakeLeaderboardApi().apply { updateGroupResult = LeaderboardResult.Ok(GroupResponse(group = renamed)) }
        val store = FakeIdentityStore(ana(g1, g2), selected = "g1")

        repo(api, store).updateGroup("g1", share = ShareSettings(true, true, true), name = " Flat ")

        assertEquals(UpdateGroupRequest(ShareDto(true, true, true), "Flat"), api.updateGroupRequests.single())
        assertEquals(ana(renamed, g2), store.identity.value)
    }

    @Test
    fun `a new group clears the removed notice`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1))) }
        val store = FakeIdentityStore().apply { removedNotice.value = true }

        repo(api, store, "Ana").createGroup(share)

        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `offline last leave, then a 401 on refresh shows no notice`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeIdentityStore(ana(g1), selected = "g1")
        val repo = repo(api, store)

        repo.leaveGroup("g1")
        assertEquals(ana(), store.identity.value)
        api.meResult = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        repo.refreshGroups()

        assertNull(store.identity.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `create with a dead token and no cached groups retries as a new user and succeeds, no notice`() = runBlocking {
        // An identity whose last leave was offline: its token is dead and it caches no groups.
        val api = FakeLeaderboardApi().apply {
            deadTokens += "old"
            meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1)))
        }
        val store = FakeIdentityStore(ana(token = "old"))

        val result = repo(api, store, " Ana ").createGroup(share)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        val identity = store.identity.value!!
        assertEquals(listOf("old", identity.token), api.createCalls.map { it.first })
        assertEquals("Ana", api.registerRequests.single().displayName)
        assertEquals(Identity(identity.token, "Ana", listOf(g1.toDomain()), identity.master), identity)
        assertEquals("g1", store.selectedGroupId.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `a dead legacy token with no cached groups registers a new user, no notice`() = runBlocking {
        // The single-group build's token: dead after the server migration, no master, no groups.
        val api = FakeLeaderboardApi().apply {
            deadTokens += "old"
            meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1)))
        }
        val store = FakeIdentityStore(Identity("old", "Ana", emptyList()))

        val result = repo(api, store, " Ana ").createGroup(share)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals("old", api.rotateCalls.single().first)
        val identity = store.identity.value!!
        assertEquals(listOf(identity.token), api.createCalls.map { it.first })
        assertEquals(listOf(g1.toDomain()), identity.groups)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `a new name keeps the master and is stored on the phone too`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1))) }
        val store = FakeIdentityStore(ana(g1))
        val names = FakeDisplayNameStore("Ana")

        repo(api, store, names).setDisplayName(" Bea ")

        assertEquals(Identity("tok", "Bea", listOf(g1.toDomain()), "m"), store.identity.value)
        assertEquals("Bea", names.displayName.value)
    }

    @Test
    fun `a new name the server didn't take changes nothing`() = runBlocking {
        val api = FakeLeaderboardApi().apply { updateMeResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeIdentityStore(ana(g1))
        val names = FakeDisplayNameStore("Ana")

        val result = repo(api, store, names).setDisplayName("Bea")

        assertEquals(LeaderboardResult.Err(LeaderboardError.NETWORK), result)
        assertEquals(ana(g1), store.identity.value)
        assertEquals("Ana", names.displayName.value)
    }

    @Test
    fun `a name set without an identity stays on the phone`() = runBlocking {
        val api = FakeLeaderboardApi().apply { updateMeResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeIdentityStore()
        val names = FakeDisplayNameStore()

        val result = repo(api, store, names).setDisplayName(" Ana ")

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals("Ana", names.displayName.value)
        assertNull(store.identity.value)
    }

    @Test
    fun `an install without a setup name shows its identity's`() = runBlocking {
        val repo = repo(FakeLeaderboardApi(), FakeIdentityStore(ana(g1)))

        assertEquals("Ana", repo.observeDisplayName().first())
    }

    @Test
    fun `restore brings the identity's name back as the setup name`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Bea", listOf(g1))) }
        val names = FakeDisplayNameStore("Ana")

        repo(api, FakeIdentityStore(), names).restore(BackupCrypto.encodeCode(BackupCrypto.newMaster()))

        assertEquals("Bea", names.displayName.value)
    }

    @Test
    fun `leaving a group you are already out of succeeds and drops it`() = runBlocking {
        val api = FakeLeaderboardApi().apply {
            leaveResult = LeaderboardResult.Err(LeaderboardError.NOT_MEMBER)
            meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g2)))
        }
        val store = FakeIdentityStore(ana(g1, g2), selected = "g1")

        val result = repo(api, store).leaveGroup("g1")

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(ana(g2), store.identity.value)
        assertEquals("g2", store.selectedGroupId.value)
    }
}
