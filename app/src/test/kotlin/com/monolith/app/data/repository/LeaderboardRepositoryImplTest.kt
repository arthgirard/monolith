package com.monolith.app.data.repository

import com.monolith.app.data.leaderboard.BoardResponse
import com.monolith.app.data.leaderboard.CreateGroupRequest
import com.monolith.app.data.leaderboard.GroupDto
import com.monolith.app.data.leaderboard.GroupResponse
import com.monolith.app.data.leaderboard.IdentityStore
import com.monolith.app.data.leaderboard.JoinRequest
import com.monolith.app.data.leaderboard.LeaderboardApi
import com.monolith.app.data.leaderboard.MeResponse
import com.monolith.app.data.leaderboard.ShareDto
import com.monolith.app.data.leaderboard.SyncRequest
import com.monolith.app.data.leaderboard.UpdateGroupRequest
import com.monolith.app.data.leaderboard.UpdateMeRequest
import com.monolith.app.data.leaderboard.toDomain
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private class FakeIdentityStore(initial: Identity? = null, selected: String? = null) : IdentityStore {
    override val identity = MutableStateFlow(initial)
    override val selectedGroupId = MutableStateFlow(selected)
    override val removedNotice = MutableStateFlow(false)
    override suspend fun save(identity: Identity) {
        this.identity.value = identity
        removedNotice.value = false
    }
    override suspend fun saveGroups(groups: List<GroupInfo>) {
        identity.value = identity.value?.copy(groups = groups)
    }
    override suspend fun select(groupId: String?) { selectedGroupId.value = groupId }
    override suspend fun clear(removed: Boolean) {
        identity.value = null
        selectedGroupId.value = null
        removedNotice.value = removed
    }
    override suspend fun dismissRemovedNotice() { removedNotice.value = false }
}

private class FakeLeaderboardApi : LeaderboardApi {
    var createResult: LeaderboardResult<GroupResponse>? = null
    var meResult: LeaderboardResult<MeResponse> = LeaderboardResult.Ok(MeResponse("Ana", emptyList()))
    var boardResult: LeaderboardResult<BoardResponse> = LeaderboardResult.Ok(BoardResponse(emptyList()))
    var leaveResult: LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)
    var updateGroupResult: LeaderboardResult<GroupResponse>? = null
    var duringBoard: suspend () -> Unit = {}
    val syncResults = ArrayDeque<LeaderboardResult<Unit>>()
    val syncRequests = mutableListOf<SyncRequest>()
    val createCalls = mutableListOf<Pair<String?, CreateGroupRequest>>()
    val meTokens = mutableListOf<String>()
    val updateGroupRequests = mutableListOf<UpdateGroupRequest>()
    val leftGroups = mutableListOf<String>()

    override suspend fun createGroup(token: String?, request: CreateGroupRequest): LeaderboardResult<GroupResponse> {
        createCalls += token to request
        return createResult ?: LeaderboardResult.Ok(GroupResponse(token = if (token == null) "tok" else null, group = g1))
    }
    override suspend fun join(token: String?, request: JoinRequest): LeaderboardResult<GroupResponse> =
        LeaderboardResult.Ok(GroupResponse(token = if (token == null) "tok" else null, group = g1))
    override suspend fun me(token: String): LeaderboardResult<MeResponse> {
        meTokens += token
        return meResult
    }
    override suspend fun updateMe(token: String, request: UpdateMeRequest): LeaderboardResult<MeResponse> =
        LeaderboardResult.Ok(MeResponse(request.displayName, (meResult as LeaderboardResult.Ok).value.groups))
    override suspend fun updateGroup(token: String, groupId: String, request: UpdateGroupRequest): LeaderboardResult<GroupResponse> {
        updateGroupRequests += request
        return updateGroupResult ?: LeaderboardResult.Err(LeaderboardError.SERVER)
    }
    override suspend fun leaveGroup(token: String, groupId: String): LeaderboardResult<Unit> {
        leftGroups += groupId
        return leaveResult
    }
    override suspend fun sync(token: String, request: SyncRequest): LeaderboardResult<Unit> {
        syncRequests += request
        return syncResults.removeFirstOrNull() ?: LeaderboardResult.Ok(Unit)
    }
    override suspend fun board(token: String, groupId: String, window: BoardWindow, date: LocalDate): LeaderboardResult<BoardResponse> {
        duringBoard()
        return boardResult
    }
}

private val g1 = GroupDto("g1", "ABCDEFGH", null, 2, listOf("Sam"), ShareDto(saved = true, streak = false, pauses = false))
private val g2 = GroupDto("g2", "JKLMNPQR", null, 2, listOf("Lea"), ShareDto(saved = false, streak = false, pauses = true))

class LeaderboardRepositoryImplTest {

    private val share = ShareSettings(saved = true, streak = true, pauses = true)
    private val day = DayAggregate(LocalDate.of(2026, 9, 28), 1000, 1, 0)
    private val today = LocalDate.of(2026, 9, 28)
    private fun ana(vararg groups: GroupDto, token: String = "tok") = Identity(token, "Ana", groups.map { it.toDomain() })

    @Test
    fun `create without identity saves the token and selects the new group`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1))) }
        val store = FakeIdentityStore()

        val result = LeaderboardRepositoryImpl(api, store).createGroup(" Ana ", share)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals("tok", store.identity.value?.token)
        assertEquals("Ana", store.identity.value?.displayName)
        assertEquals(listOf(g1.toDomain()), store.identity.value?.groups)
        assertEquals("g1", store.selectedGroupId.value)
        assertEquals(null to CreateGroupRequest("Ana", ShareDto(true, true, true)), api.createCalls.single())
    }

    @Test
    fun `create with identity sends the token and no display name`() = runBlocking {
        val api = FakeLeaderboardApi().apply {
            createResult = LeaderboardResult.Ok(GroupResponse(group = g2))
            meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1, g2)))
        }
        val store = FakeIdentityStore(ana(g1), selected = "g1")

        LeaderboardRepositoryImpl(api, store).createGroup("Ignored", share)

        val (token, request) = api.createCalls.single()
        assertEquals("tok", token)
        assertNull(request.displayName)
        assertEquals(ana(g1, g2), store.identity.value)
        assertEquals("g2", store.selectedGroupId.value)
    }

    @Test
    fun `create while offline saves nothing`() = runBlocking {
        val api = FakeLeaderboardApi().apply { createResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeIdentityStore()

        val result = LeaderboardRepositoryImpl(api, store).createGroup("Ana", share)

        assertEquals(LeaderboardResult.Err(LeaderboardError.NETWORK), result)
        assertNull(store.identity.value)
        assertNull(store.selectedGroupId.value)
    }

    @Test
    fun `restore brings every group back`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1, g2))) }
        val store = FakeIdentityStore()

        LeaderboardRepositoryImpl(api, store).restore("  tok \n")

        assertEquals("tok", api.meTokens.single())
        assertEquals(ana(g1, g2), store.identity.value)
        assertEquals("g1", store.selectedGroupId.value)
    }

    @Test
    fun `sync filters by the union of group shares`() = runBlocking {
        val api = FakeLeaderboardApi()

        LeaderboardRepositoryImpl(api, FakeIdentityStore(ana(g1, g2))).sync(listOf(day), streakStartedAt = 5)

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

        val result = LeaderboardRepositoryImpl(api, store).sync(listOf(day), null)

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

        val result = LeaderboardRepositoryImpl(api, store).board("g1", BoardWindow.WEEK, today)

        assertEquals(LeaderboardResult.Err(LeaderboardError.NOT_MEMBER), result)
        assertEquals(listOf(g2.toDomain()), store.identity.value?.groups)
        assertEquals("g2", store.selectedGroupId.value)
    }

    @Test
    fun `leaving the last group clears without a notice`() = runBlocking {
        // The server deletes a user who leaves their last group, so the refresh after it is a 401.
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED) }
        val store = FakeIdentityStore(ana(g1, g2), selected = "g1")

        val result = LeaderboardRepositoryImpl(api, store).leaveGroup("g1")

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(listOf("g1"), api.leftGroups)
        assertNull(store.identity.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `leaving what the cache thinks is the last group keeps a group joined elsewhere`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g2))) }
        val store = FakeIdentityStore(ana(g1), selected = "g1")

        val result = LeaderboardRepositoryImpl(api, store).leaveGroup("g1")

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(ana(g2), store.identity.value)
        assertEquals("g2", store.selectedGroupId.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `sync without any cached group uploads nothing`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore(ana())

        val result = LeaderboardRepositoryImpl(api, store).sync(listOf(day), streakStartedAt = 5)

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

        LeaderboardRepositoryImpl(api, store).createGroup(null, share)

        assertNull(store.identity.value)
        assertNull(store.selectedGroupId.value)
    }

    @Test
    fun `leaving one of two keeps the identity`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g2))) }
        val store = FakeIdentityStore(ana(g1, g2), selected = "g1")

        LeaderboardRepositoryImpl(api, store).leaveGroup("g1")

        assertEquals(ana(g2), store.identity.value)
        assertEquals("g2", store.selectedGroupId.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `401 clears with a notice`() = runBlocking {
        val api = FakeLeaderboardApi().apply { syncResults += LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED) }
        val store = FakeIdentityStore(ana(g1), selected = "g1")
        val repo = LeaderboardRepositoryImpl(api, store)

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

        LeaderboardRepositoryImpl(api, store).board("g1", BoardWindow.WEEK, today)

        assertEquals(newer, store.identity.value)
        assertFalse(store.removedNotice.value)
    }

    @Test
    fun `calls without an identity are UNAUTHORIZED and skip the network`() = runBlocking {
        val api = FakeLeaderboardApi()

        val result = LeaderboardRepositoryImpl(api, FakeIdentityStore()).sync(listOf(day), null)

        assertEquals(LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED), result)
        assertEquals(0, api.syncRequests.size)
    }

    @Test
    fun `updateGroup replaces that group in the cache`() = runBlocking {
        val renamed = g1.copy(name = "Flat", share = ShareDto(true, true, true))
        val api = FakeLeaderboardApi().apply { updateGroupResult = LeaderboardResult.Ok(GroupResponse(group = renamed)) }
        val store = FakeIdentityStore(ana(g1, g2), selected = "g1")

        LeaderboardRepositoryImpl(api, store).updateGroup("g1", share = ShareSettings(true, true, true), name = " Flat ")

        assertEquals(UpdateGroupRequest(ShareDto(true, true, true), "Flat"), api.updateGroupRequests.single())
        assertEquals(ana(renamed, g2), store.identity.value)
    }

    @Test
    fun `a new group clears the removed notice`() = runBlocking {
        val api = FakeLeaderboardApi().apply { meResult = LeaderboardResult.Ok(MeResponse("Ana", listOf(g1))) }
        val store = FakeIdentityStore().apply { removedNotice.value = true }

        LeaderboardRepositoryImpl(api, store).createGroup("Ana", share)

        assertFalse(store.removedNotice.value)
    }
}
