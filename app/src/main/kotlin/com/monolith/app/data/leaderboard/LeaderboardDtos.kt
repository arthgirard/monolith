package com.monolith.app.data.leaderboard

import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.ShareSettings
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Nulls are omitted, which is how a hidden signal stays off the wire. */
@OptIn(ExperimentalSerializationApi::class)
val LeaderboardJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable data class ShareDto(val saved: Boolean, val streak: Boolean, val pauses: Boolean)

@Serializable
data class GroupDto(
    val id: String,
    val inviteCode: String,
    val name: String? = null,
    val memberCount: Int,
    val otherMembers: List<String> = emptyList(),
    val share: ShareDto,
)

/** A phone-generated [token]; without [displayName] the identity stays nameless until its first group. */
@Serializable data class RegisterRequest(val token: String, val displayName: String? = null)
@Serializable data class RotateTokenRequest(val token: String)

/** [displayName] only for a nameless identity: a named one keeps its name. */
@Serializable data class CreateGroupRequest(val displayName: String? = null, val share: ShareDto)
@Serializable data class JoinRequest(val inviteCode: String, val displayName: String? = null, val share: ShareDto)

@Serializable data class GroupResponse(val group: GroupDto)
@Serializable data class MeResponse(val displayName: String, val groups: List<GroupDto>, val backupAt: Long? = null)
@Serializable data class UpdateMeRequest(val displayName: String)

/** An empty [name] clears the group's name. */
@Serializable data class UpdateGroupRequest(val share: ShareDto? = null, val name: String? = null)
@Serializable data class SyncDayDto(val date: String, val savedMs: Long? = null, val bypassCount: Int? = null, val unlockCount: Int? = null)
@Serializable data class SyncRequest(val days: List<SyncDayDto>, val streakStartedAt: Long? = null)
@Serializable data class StreakDto(val startedAt: Long? = null)

@Serializable
data class BoardRowDto(
    val name: String,
    val isMe: Boolean,
    val rank: Int? = null,
    val savedMs: Long? = null,
    val streak: StreakDto? = null,
    val bypassCount: Int? = null,
    val unlockCount: Int? = null,
    val lastSyncAt: Long? = null,
)

@Serializable data class BoardResponse(val rows: List<BoardRowDto>)
@Serializable data class ErrorResponse(val error: String)

fun ShareSettings.toDto() = ShareDto(saved, streak, pauses)
fun ShareDto.toDomain() = ShareSettings(saved, streak, pauses)
fun GroupDto.toDomain() = GroupInfo(id, inviteCode, name, memberCount, otherMembers, share.toDomain())
fun GroupInfo.toDto() = GroupDto(id, inviteCode, name, memberCount, otherMembers, share.toDto())

fun BoardRowDto.toDomain() = BoardRow(
    name = name,
    isMe = isMe,
    rank = rank,
    savedMillis = savedMs,
    streakVisible = streak != null,
    streakStartedAt = streak?.startedAt,
    bypassCount = bypassCount,
    unlockCount = unlockCount,
    lastSyncAt = lastSyncAt,
)

/** The upload body, with every signal [share] hides left out before it reaches the network. */
fun syncRequestOf(days: List<DayAggregate>, streakStartedAt: Long?, share: ShareSettings) = SyncRequest(
    days = days.map {
        SyncDayDto(
            date = it.date.toString(),
            savedMs = it.savedMillis.takeIf { share.saved },
            bypassCount = it.bypassCount.takeIf { share.pauses },
            unlockCount = it.unlockCount.takeIf { share.pauses },
        )
    },
    streakStartedAt = streakStartedAt.takeIf { share.streak },
)

fun errorOf(code: String?): LeaderboardError = when (code) {
    "unauthorized" -> LeaderboardError.UNAUTHORIZED
    "invite_not_found" -> LeaderboardError.INVITE_NOT_FOUND
    "group_full" -> LeaderboardError.GROUP_FULL
    "too_many_groups" -> LeaderboardError.TOO_MANY_GROUPS
    "already_member" -> LeaderboardError.ALREADY_MEMBER
    "name_needs_three" -> LeaderboardError.NAME_NEEDS_THREE
    "not_member" -> LeaderboardError.NOT_MEMBER
    "hidden_signal" -> LeaderboardError.HIDDEN_SIGNAL
    "invalid_body" -> LeaderboardError.INVALID
    "token_taken" -> LeaderboardError.TOKEN_TAKEN
    "no_backup" -> LeaderboardError.NO_BACKUP
    "too_large" -> LeaderboardError.TOO_LARGE
    else -> LeaderboardError.SERVER
}
