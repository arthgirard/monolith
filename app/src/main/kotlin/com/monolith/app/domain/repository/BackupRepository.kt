package com.monolith.app.domain.repository

import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.LeaderboardResult
import kotlinx.coroutines.flow.Flow

/**
 * The encrypted backup, kept under the same identity as the friends groups. The recovery code is
 * the identity's master: it derives both the server token and the key, so it restores both.
 */
interface BackupRepository {
    fun observeBackupEnabled(): Flow<Boolean>
    fun observeLastBackupAt(): Flow<Long?>

    /** The master, null until one exists (no identity, or one not migrated yet). */
    fun observeRecoveryCode(): Flow<String?>

    /** Registers a new identity if there is none, and migrates one from before backups. */
    suspend fun ensureIdentity(displayName: String?): LeaderboardResult<Unit>

    /** On makes sure an identity exists; off deletes the server's copy. */
    suspend fun setBackupEnabled(enabled: Boolean): LeaderboardResult<Unit>

    /** Does nothing while backup is off. */
    suspend fun upload(snapshot: BackupSnapshot, now: Long): LeaderboardResult<Unit>

    /** Reads what [recoveryCode] would restore, and writes nothing. */
    suspend fun fetch(recoveryCode: String): LeaderboardResult<FetchedBackup>
}

/** [master] is the recovery code in canonical form; [snapshot] is null when there is no backup. */
data class FetchedBackup(val master: String, val me: MeInfo, val snapshot: BackupSnapshot?)

data class MeInfo(val displayName: String, val groups: List<GroupInfo>, val backupAt: Long?)
