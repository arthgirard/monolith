package com.monolith.app.data.repository

import com.monolith.app.data.leaderboard.BoardRowDto
import com.monolith.app.data.leaderboard.CreateGroupRequest
import com.monolith.app.data.leaderboard.JoinRequest
import com.monolith.app.data.leaderboard.LeaderboardApi
import com.monolith.app.data.leaderboard.MembershipStore
import com.monolith.app.data.leaderboard.UpdateMeRequest
import com.monolith.app.data.leaderboard.syncRequestOf
import com.monolith.app.data.leaderboard.toDomain
import com.monolith.app.data.leaderboard.toDto
import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.GroupMembership
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.model.andThen
import com.monolith.app.domain.model.map
import com.monolith.app.domain.repository.LeaderboardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeaderboardRepositoryImpl @Inject constructor(
    private val api: LeaderboardApi,
    private val store: MembershipStore,
) : LeaderboardRepository {

    override fun observeMembership(): Flow<GroupMembership?> = store.membership

    override suspend fun createGroup(displayName: String, share: ShareSettings): LeaderboardResult<Unit> {
        val name = displayName.trim()
        return api.createGroup(CreateGroupRequest(name, share.toDto()))
            .andThen { store.save(GroupMembership(it.token, name, it.inviteCode, share)) }
    }

    override suspend fun joinGroup(inviteCode: String, displayName: String, share: ShareSettings): LeaderboardResult<Unit> {
        val name = displayName.trim()
        return api.join(JoinRequest(inviteCode, name, share.toDto()))
            .andThen { store.save(GroupMembership(it.token, name, it.inviteCode, share)) }
    }

    override suspend fun restore(recoveryCode: String): LeaderboardResult<Unit> {
        val token = recoveryCode.trim()
        return api.me(token).andThen { store.save(GroupMembership(token, it.displayName, it.inviteCode, it.share.toDomain())) }
    }

    override suspend fun updateProfile(displayName: String?, share: ShareSettings?): LeaderboardResult<Unit> = authed { m ->
        api.updateMe(m.token, UpdateMeRequest(displayName?.trim(), share?.toDto()))
            .andThen { store.save(m.copy(displayName = it.displayName, share = it.share.toDomain())) }
    }

    override suspend fun sync(days: List<DayAggregate>, streakStartedAt: Long?): LeaderboardResult<Unit> = authed { m ->
        val request = syncRequestOf(days, streakStartedAt, m.share)
        val first = api.sync(m.token, request)
        // The server's flags drifted from ours (an update that failed offline). Ours are the
        // member's latest choice: push them, then retry once.
        if (first is LeaderboardResult.Err && first.error == LeaderboardError.HIDDEN_SIGNAL) {
            val updated = api.updateMe(m.token, UpdateMeRequest(share = m.share.toDto()))
            if (updated is LeaderboardResult.Err) return@authed updated
            api.sync(m.token, request)
        } else {
            first
        }
    }

    override suspend fun board(window: BoardWindow, today: LocalDate): LeaderboardResult<List<BoardRow>> = authed { m ->
        api.board(m.token, window, today).map { response -> response.rows.map(BoardRowDto::toDomain) }
    }

    override suspend fun leave(): LeaderboardResult<Unit> = authed { m ->
        api.leave(m.token).andThen { store.clear() }
    }

    private suspend fun <T> authed(block: suspend (GroupMembership) -> LeaderboardResult<T>): LeaderboardResult<T> {
        val membership = store.membership.first() ?: return LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        val result = block(membership)
        if (result is LeaderboardResult.Err && result.error == LeaderboardError.UNAUTHORIZED) store.clear()
        return result
    }
}
