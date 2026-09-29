package com.monolith.app.data.repository

import com.monolith.app.data.leaderboard.BoardResponse
import com.monolith.app.data.leaderboard.CreateGroupRequest
import com.monolith.app.data.leaderboard.JoinRequest
import com.monolith.app.data.leaderboard.JoinResponse
import com.monolith.app.data.leaderboard.LeaderboardApi
import com.monolith.app.data.leaderboard.MeResponse
import com.monolith.app.data.leaderboard.MembershipStore
import com.monolith.app.data.leaderboard.ShareDto
import com.monolith.app.data.leaderboard.SyncRequest
import com.monolith.app.data.leaderboard.UpdateMeRequest
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.GroupMembership
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private class FakeMembershipStore(initial: GroupMembership? = null) : MembershipStore {
    override val membership = MutableStateFlow(initial)
    override val removedNotice = MutableStateFlow(false)
    override suspend fun save(membership: GroupMembership) {
        this.membership.value = membership
        removedNotice.value = false
    }
    override suspend fun clear(removed: Boolean) {
        membership.value = null
        removedNotice.value = removed
    }
    override suspend fun dismissRemovedNotice() { removedNotice.value = false }
}

private class FakeLeaderboardApi : LeaderboardApi {
    var createResult: LeaderboardResult<JoinResponse> = LeaderboardResult.Ok(JoinResponse("tok", "ABCDEFGH"))
    var boardResult: LeaderboardResult<BoardResponse> = LeaderboardResult.Ok(BoardResponse(emptyList()))
    var duringBoard: suspend () -> Unit = {}
    val syncResults = ArrayDeque<LeaderboardResult<Unit>>()
    val syncRequests = mutableListOf<SyncRequest>()
    val updateRequests = mutableListOf<UpdateMeRequest>()

    override suspend fun createGroup(request: CreateGroupRequest) = createResult
    override suspend fun join(request: JoinRequest) = createResult
    override suspend fun me(token: String): LeaderboardResult<MeResponse> =
        LeaderboardResult.Ok(MeResponse("Ana", ShareDto(true, false, true), "ABCDEFGH"))
    override suspend fun updateMe(token: String, request: UpdateMeRequest): LeaderboardResult<MeResponse> {
        updateRequests += request
        return LeaderboardResult.Ok(MeResponse(request.displayName ?: "Ana", request.share ?: ShareDto(true, true, true), "ABCDEFGH"))
    }
    override suspend fun sync(token: String, request: SyncRequest): LeaderboardResult<Unit> {
        syncRequests += request
        return syncResults.removeFirstOrNull() ?: LeaderboardResult.Ok(Unit)
    }
    override suspend fun board(token: String, window: BoardWindow, date: LocalDate): LeaderboardResult<BoardResponse> {
        duringBoard()
        return boardResult
    }
    override suspend fun leave(token: String): LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)
}

class LeaderboardRepositoryImplTest {

    private val member = GroupMembership("tok", "Ana", "ABCDEFGH", ShareSettings(saved = true, streak = false, pauses = true))
    private val day = DayAggregate(LocalDate.of(2026, 9, 28), 1000, 1, 0)

    @Test
    fun `create saves the membership`() = runBlocking {
        val store = FakeMembershipStore()
        val repo = LeaderboardRepositoryImpl(FakeLeaderboardApi(), store)

        val result = repo.createGroup(" Ana ", member.share)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(member, store.membership.value)
    }

    @Test
    fun `create while offline saves nothing`() = runBlocking {
        val api = FakeLeaderboardApi().apply { createResult = LeaderboardResult.Err(LeaderboardError.NETWORK) }
        val store = FakeMembershipStore()

        val result = LeaderboardRepositoryImpl(api, store).createGroup("Ana", member.share)

        assertEquals(LeaderboardResult.Err(LeaderboardError.NETWORK), result)
        assertNull(store.membership.value)
    }

    @Test
    fun `restore stores what the server knows about the token`() = runBlocking {
        val store = FakeMembershipStore()

        LeaderboardRepositoryImpl(FakeLeaderboardApi(), store).restore("  tok \n")

        assertEquals(member, store.membership.value)
    }

    @Test
    fun `sync strips signals the membership hides`() = runBlocking {
        val api = FakeLeaderboardApi()

        LeaderboardRepositoryImpl(api, FakeMembershipStore(member)).sync(listOf(day), streakStartedAt = 5)

        assertNull(api.syncRequests.single().streakStartedAt)
        assertEquals(1000L, api.syncRequests.single().days.single().savedMs)
    }

    @Test
    fun `hidden_signal re-sends the local share flags and retries once`() = runBlocking {
        val api = FakeLeaderboardApi().apply { syncResults += LeaderboardResult.Err(LeaderboardError.HIDDEN_SIGNAL) }

        val result = LeaderboardRepositoryImpl(api, FakeMembershipStore(member)).sync(listOf(day), null)

        assertEquals(LeaderboardResult.Ok(Unit), result)
        assertEquals(ShareDto(true, false, true), api.updateRequests.single().share)
        assertEquals(2, api.syncRequests.size)
    }

    @Test
    fun `401 clears the stored membership`() = runBlocking {
        val api = FakeLeaderboardApi().apply { boardResult = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED) }
        val store = FakeMembershipStore(member)

        val result = LeaderboardRepositoryImpl(api, store).board(BoardWindow.WEEK, LocalDate.of(2026, 9, 28))

        assertEquals(LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED), result)
        assertNull(store.membership.first())
    }

    @Test
    fun `calls without a membership are UNAUTHORIZED and skip the network`() = runBlocking {
        val api = FakeLeaderboardApi()

        val result = LeaderboardRepositoryImpl(api, FakeMembershipStore()).sync(listOf(day), null)

        assertEquals(LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED), result)
        assertEquals(0, api.syncRequests.size)
    }

    @Test
    fun `leave clears the membership`() = runBlocking {
        val store = FakeMembershipStore(member)

        LeaderboardRepositoryImpl(FakeLeaderboardApi(), store).leave()

        assertNull(store.membership.value)
    }

    @Test
    fun `a background 401 leaves a removed notice`() = runBlocking {
        val api = FakeLeaderboardApi().apply { syncResults += LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED) }
        val store = FakeMembershipStore(member)
        val repo = LeaderboardRepositoryImpl(api, store)

        repo.sync(listOf(day), null)

        assertNull(store.membership.value)
        assertTrue(repo.observeRemovedNotice().first())
        repo.dismissRemovedNotice()
        assertFalse(repo.observeRemovedNotice().first())
    }

    @Test
    fun `leaving is not a removal`() = runBlocking {
        val repo = LeaderboardRepositoryImpl(FakeLeaderboardApi(), FakeMembershipStore(member))

        repo.leave()

        assertFalse(repo.observeRemovedNotice().first())
    }

    @Test
    fun `a new group clears the removed notice`() = runBlocking {
        val store = FakeMembershipStore().apply { removedNotice.value = true }
        val repo = LeaderboardRepositoryImpl(FakeLeaderboardApi(), store)

        repo.createGroup("Ana", member.share)

        assertFalse(repo.observeRemovedNotice().first())
    }

    @Test
    fun `a late 401 for an old token keeps the newer membership`() = runBlocking {
        val newer = member.copy(token = "tok2")
        val store = FakeMembershipStore(member)
        val api = FakeLeaderboardApi().apply {
            boardResult = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
            duringBoard = { store.save(newer) }
        }

        LeaderboardRepositoryImpl(api, store).board(BoardWindow.WEEK, LocalDate.of(2026, 9, 28))

        assertEquals(newer, store.membership.value)
        assertFalse(store.removedNotice.value)
    }
}
