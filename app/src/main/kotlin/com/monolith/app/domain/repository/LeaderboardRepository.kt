package com.monolith.app.domain.repository

import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.model.SharedApp
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

    /** The name friends see: the one given during setup. Empty until one is set. */
    fun observeDisplayName(): Flow<String>

    /** Stored on the phone, and on the server too once there is an identity; a failure there changes nothing. */
    suspend fun setDisplayName(displayName: String): LeaderboardResult<Unit>

    /** Both go by the name from [observeDisplayName]. */
    suspend fun createGroup(share: ShareSettings): LeaderboardResult<Unit>
    suspend fun joinGroup(inviteCode: String, share: ShareSettings): LeaderboardResult<Unit>
    suspend fun restore(recoveryCode: String): LeaderboardResult<Unit>
    suspend fun refreshGroups(): LeaderboardResult<Unit>

    /** Null leaves a field alone; an empty [name] clears it. */
    suspend fun updateGroup(groupId: String, share: ShareSettings? = null, name: String? = null): LeaderboardResult<Unit>
    suspend fun leaveGroup(groupId: String): LeaderboardResult<Unit>
    suspend fun sync(days: List<DayAggregate>, streakStartedAt: Long?, blockedApps: List<SharedApp>): LeaderboardResult<Unit>
    suspend fun board(groupId: String, window: BoardWindow, today: LocalDate): LeaderboardResult<List<BoardRow>>
}
