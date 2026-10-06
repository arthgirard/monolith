package com.monolith.app.domain.usecase

import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.repository.FetchedBackup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeBackupRepository(
    var fetchResult: LeaderboardResult<FetchedBackup> = LeaderboardResult.Err(LeaderboardError.NO_BACKUP),
    enabled: Boolean = false,
) : BackupRepository {
    val enabled = MutableStateFlow(enabled)
    var localCode: String? = null
    var enabledByDefault = 0
        private set
    val fetchCalls = mutableListOf<String>()
    val enabledCalls = mutableListOf<Boolean>()

    override fun observeBackupEnabled(): Flow<Boolean> = enabled
    override fun observeLastBackupAt(): Flow<Long?> = MutableStateFlow(null)
    override fun observeRecoveryCode(): Flow<String?> = MutableStateFlow(null)
    override suspend fun ensureIdentity(displayName: String?) = LeaderboardResult.Ok(Unit)
    override suspend fun setBackupEnabled(enabled: Boolean): LeaderboardResult<Unit> {
        enabledCalls += enabled
        return LeaderboardResult.Ok(Unit)
    }
    override suspend fun upload(snapshot: BackupSnapshot, now: Long) = LeaderboardResult.Ok(Unit)
    override suspend fun fetch(recoveryCode: String): LeaderboardResult<FetchedBackup> {
        fetchCalls += recoveryCode
        return fetchResult
    }
    override suspend fun localRecoveryCode(): String? = localCode
    override suspend fun enableByDefault() {
        enabledByDefault++
        enabled.value = true
    }
}

class RecordingWriter : SnapshotWriter {
    val writes = mutableListOf<BackupSnapshot>()
    override suspend fun write(snapshot: BackupSnapshot) { writes += snapshot }
}

class CountingAfterRestore : AfterRestore {
    var runs = 0
    override suspend fun run() { runs++ }
}
