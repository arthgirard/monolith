package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.GroupMembership
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.repository.LeaderboardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

class FakeLeaderboardRepository(membership: GroupMembership? = null) : LeaderboardRepository {
    val membership = MutableStateFlow(membership)
    val syncCalls = mutableListOf<Pair<List<DayAggregate>, Long?>>()

    override fun observeMembership(): Flow<GroupMembership?> = membership
    override suspend fun createGroup(displayName: String, share: ShareSettings) = LeaderboardResult.Ok(Unit)
    override suspend fun joinGroup(inviteCode: String, displayName: String, share: ShareSettings) = LeaderboardResult.Ok(Unit)
    override suspend fun restore(recoveryCode: String) = LeaderboardResult.Ok(Unit)
    override suspend fun updateProfile(displayName: String?, share: ShareSettings?) = LeaderboardResult.Ok(Unit)
    override suspend fun sync(days: List<DayAggregate>, streakStartedAt: Long?): LeaderboardResult<Unit> {
        syncCalls += days to streakStartedAt
        return LeaderboardResult.Ok(Unit)
    }
    override suspend fun board(window: BoardWindow, today: LocalDate): LeaderboardResult<List<BoardRow>> = LeaderboardResult.Ok(emptyList())
    override suspend fun leave() = LeaderboardResult.Ok(Unit)
}
