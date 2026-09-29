package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.repository.LeaderboardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

class FakeLeaderboardRepository(identity: Identity? = null) : LeaderboardRepository {
    val identity = MutableStateFlow(identity)
    val selectedGroupId = MutableStateFlow(identity?.groups?.firstOrNull()?.id)
    val syncCalls = mutableListOf<Pair<List<DayAggregate>, Long?>>()
    var syncResult: LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)
    val restoreCalls = mutableListOf<String>()
    var restoreResult: LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)

    val removedNotice = MutableStateFlow(false)

    override fun observeIdentity(): Flow<Identity?> = identity
    override fun observeSelectedGroupId(): Flow<String?> = selectedGroupId
    override suspend fun selectGroup(groupId: String) { selectedGroupId.value = groupId }
    override fun observeRemovedNotice(): Flow<Boolean> = removedNotice
    override suspend fun dismissRemovedNotice() { removedNotice.value = false }
    override suspend fun createGroup(displayName: String?, share: ShareSettings) = LeaderboardResult.Ok(Unit)
    override suspend fun joinGroup(inviteCode: String, displayName: String?, share: ShareSettings) = LeaderboardResult.Ok(Unit)
    override suspend fun restore(recoveryCode: String): LeaderboardResult<Unit> {
        restoreCalls += recoveryCode
        return restoreResult
    }
    override suspend fun rename(displayName: String) = LeaderboardResult.Ok(Unit)
    override suspend fun refreshGroups() = LeaderboardResult.Ok(Unit)
    override suspend fun updateGroup(groupId: String, share: ShareSettings?, name: String?) = LeaderboardResult.Ok(Unit)
    override suspend fun leaveGroup(groupId: String) = LeaderboardResult.Ok(Unit)
    override suspend fun sync(days: List<DayAggregate>, streakStartedAt: Long?): LeaderboardResult<Unit> {
        syncCalls += days to streakStartedAt
        return syncResult
    }
    override suspend fun board(groupId: String, window: BoardWindow, today: LocalDate): LeaderboardResult<List<BoardRow>> =
        LeaderboardResult.Ok(emptyList())
}
