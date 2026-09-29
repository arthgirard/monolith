package com.monolith.app.domain.model

import java.time.LocalDate

/** Which signals this member shares. Hiding one also hides it on everyone else's row. */
data class ShareSettings(val saved: Boolean, val streak: Boolean, val pauses: Boolean, val apps: Boolean)

/**
 * Lets friends' boards keep crediting this member between uploads. [resumesInMillis] is 0 while a
 * block runs, or how long until a running pause ends; [utcOffsetMinutes] places that time in this
 * member's own days.
 */
data class Accrual(val resumesInMillis: Long, val utcOffsetMinutes: Int)

/** One app a member blocks, as named on their phone. */
data class SharedApp(val packageName: String, val label: String)

/** One group as this member sees it, with the signals this member shares in it. */
data class GroupInfo(
    val id: String,
    val inviteCode: String,
    val name: String?,
    val memberCount: Int,
    val otherMembers: List<String>,
    val share: ShareSettings,
)

/**
 * One person across all their groups. [master] is the recovery code, and [token] derives from it;
 * it is null for an identity from a build before backups, until that one is migrated. [backupAt]
 * is when the server last held a backup, as of the last restore.
 */
data class Identity(
    val token: String,
    val displayName: String,
    val groups: List<GroupInfo>,
    val master: String? = null,
    val backupAt: Long? = null,
)

/** The server takes 1 to 24 characters, trimmed. */
const val DISPLAY_NAME_MAX_LENGTH = 24

fun isValidDisplayName(name: String): Boolean = name.trim().length in 1..DISPLAY_NAME_MAX_LENGTH

/** What a group is called on screen; [isCode] while it is still just its invite code. */
data class GroupLabel(val text: String, val isCode: Boolean)

enum class BoardWindow(val apiName: String) { DAY("day"), WEEK("week"), MONTH("month") }

/**
 * One member on the board. A null value means that signal is hidden for this viewer.
 * [streakVisible] separates "hidden" from "visible but not enforcing" ([streakStartedAt] null).
 * [blockedApps] is empty, not null, when shared but not uploaded yet.
 */
data class BoardRow(
    val name: String,
    val isMe: Boolean,
    val rank: Int?,
    val savedMillis: Long?,
    val streakVisible: Boolean,
    val streakStartedAt: Long?,
    val bypassCount: Int?,
    val unlockCount: Int?,
    val lastSyncAt: Long?,
    val blockedApps: List<SharedApp>? = null,
)

/** One local day as uploaded to the leaderboard. */
data class DayAggregate(
    val date: LocalDate,
    val savedMillis: Long,
    val bypassCount: Int,
    val unlockCount: Int,
)

enum class LeaderboardError {
    NETWORK,
    UNAUTHORIZED,
    INVITE_NOT_FOUND,
    GROUP_FULL,
    TOO_MANY_GROUPS,
    ALREADY_MEMBER,
    NAME_NEEDS_THREE,
    NOT_MEMBER,
    HIDDEN_SIGNAL,
    INVALID,
    TOKEN_TAKEN,
    NO_BACKUP,
    TOO_LARGE,
    SERVER,
}

sealed interface LeaderboardResult<out T> {
    data class Ok<T>(val value: T) : LeaderboardResult<T>
    data class Err(val error: LeaderboardError) : LeaderboardResult<Nothing>
}

inline fun <T, R> LeaderboardResult<T>.map(transform: (T) -> R): LeaderboardResult<R> = when (this) {
    is LeaderboardResult.Ok -> LeaderboardResult.Ok(transform(value))
    is LeaderboardResult.Err -> this
}

/** Runs [action] on success and drops the value. Inline, so [action] may suspend. */
inline fun <T> LeaderboardResult<T>.andThen(action: (T) -> Unit): LeaderboardResult<Unit> = when (this) {
    is LeaderboardResult.Ok -> { action(value); LeaderboardResult.Ok(Unit) }
    is LeaderboardResult.Err -> this
}
