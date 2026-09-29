package com.monolith.app.domain.repository

import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The friends leaderboard: one identity in up to ten groups. Any call answered with UNAUTHORIZED
 * clears the stored identity: the user was removed as inactive, or left their last group on
 * another phone, and this token is dead. That removal leaves a notice behind until dismissed or
 * a new identity is saved, since the background sync usually meets the 401 before the screen does.
 */
interface LeaderboardRepository {
    fun observeIdentity(): Flow<Identity?>
    fun observeSelectedGroupId(): Flow<String?>
    suspend fun selectGroup(groupId: String)
    fun observeRemovedNotice(): Flow<Boolean>
    suspend fun dismissRemovedNotice()

    /** [displayName] is used only when there is no identity yet. */
    suspend fun createGroup(displayName: String?, share: ShareSettings): LeaderboardResult<Unit>
    suspend fun joinGroup(inviteCode: String, displayName: String?, share: ShareSettings): LeaderboardResult<Unit>
    suspend fun restore(recoveryCode: String): LeaderboardResult<Unit>
    suspend fun rename(displayName: String): LeaderboardResult<Unit>
    suspend fun refreshGroups(): LeaderboardResult<Unit>

    /** Null leaves a field alone; an empty [name] clears it. */
    suspend fun updateGroup(groupId: String, share: ShareSettings? = null, name: String? = null): LeaderboardResult<Unit>
    suspend fun leaveGroup(groupId: String): LeaderboardResult<Unit>
    suspend fun sync(days: List<DayAggregate>, streakStartedAt: Long?): LeaderboardResult<Unit>
    suspend fun board(groupId: String, window: BoardWindow, today: LocalDate): LeaderboardResult<List<BoardRow>>
}
