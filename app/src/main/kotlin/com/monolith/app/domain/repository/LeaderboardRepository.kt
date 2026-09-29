package com.monolith.app.domain.repository

import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.GroupMembership
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The friends leaderboard. Any call answered with UNAUTHORIZED clears the stored membership:
 * the member left on another phone, or was removed as inactive, and this token is dead.
 */
interface LeaderboardRepository {
    fun observeMembership(): Flow<GroupMembership?>
    suspend fun createGroup(displayName: String, share: ShareSettings): LeaderboardResult<Unit>
    suspend fun joinGroup(inviteCode: String, displayName: String, share: ShareSettings): LeaderboardResult<Unit>
    suspend fun restore(recoveryCode: String): LeaderboardResult<Unit>
    suspend fun updateProfile(displayName: String?, share: ShareSettings?): LeaderboardResult<Unit>
    suspend fun sync(days: List<DayAggregate>, streakStartedAt: Long?): LeaderboardResult<Unit>
    suspend fun board(window: BoardWindow, today: LocalDate): LeaderboardResult<List<BoardRow>>
    suspend fun leave(): LeaderboardResult<Unit>
}
