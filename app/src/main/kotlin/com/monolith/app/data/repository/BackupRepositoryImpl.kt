package com.monolith.app.data.repository

import com.monolith.app.data.backup.BackupCrypto
import com.monolith.app.data.backup.BackupCryptoException
import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.data.backup.BackupSnapshotCodec
import com.monolith.app.data.leaderboard.GroupDto
import com.monolith.app.data.leaderboard.IdentityStore
import com.monolith.app.data.leaderboard.LeaderboardApi
import com.monolith.app.data.leaderboard.toDomain
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.andThen
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.repository.FetchedBackup
import com.monolith.app.domain.repository.MeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val api: LeaderboardApi,
    private val store: IdentityStore,
    private val identities: IdentityManager,
) : BackupRepository {

    // Turning backup off while an upload is in flight must not leave that upload on the server.
    private val mutex = Mutex()

    override fun observeBackupEnabled(): Flow<Boolean> = store.backupEnabled

    override fun observeLastBackupAt(): Flow<Long?> = store.lastBackupAt

    override fun observeRecoveryCode(): Flow<String?> = store.identity.map { it?.master }.distinctUntilChanged()

    override suspend fun ensureIdentity(displayName: String?): LeaderboardResult<Unit> = identities.ensureIdentity(displayName)

    override suspend fun setBackupEnabled(enabled: Boolean): LeaderboardResult<Unit> = mutex.withLock {
        if (enabled) identities.ensureIdentity(null).andThen { store.setBackupEnabled(true) } else disable()
    }

    private suspend fun disable(): LeaderboardResult<Unit> {
        val identity = store.identity.first()
        if (identity == null) {
            store.setBackupEnabled(false)
            store.setLastBackupAt(null)
            return LeaderboardResult.Ok(Unit)
        }
        return identities.guarded(identity) { api.deleteBackup(identity.token) }.andThen {
            store.setBackupEnabled(false)
            store.setLastBackupAt(null)
            // The server deletes a user with no group and no backup. Ask it rather than the cache:
            // a group joined on another phone may not be cached yet. A 401 is that deletion.
            if (identity.groups.isEmpty()) {
                val me = api.me(identity.token)
                if (me is LeaderboardResult.Err && me.error == LeaderboardError.UNAUTHORIZED &&
                    store.identity.first()?.token == identity.token
                ) {
                    store.clear()
                }
            }
        }
    }

    override suspend fun upload(snapshot: BackupSnapshot, now: Long): LeaderboardResult<Unit> = mutex.withLock {
        if (!store.backupEnabled.first()) return@withLock LeaderboardResult.Ok(Unit)
        val identity = store.identity.first()
        val master = identity?.master?.let(BackupCrypto::decodeCode)
            ?: return@withLock LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        val blob = BackupCrypto.encrypt(BackupCrypto.deriveKey(master), BackupSnapshotCodec.encode(snapshot))
        identities.guarded(identity) { api.putBackup(identity.token, blob) }.andThen {
            if (store.identity.first()?.token == identity.token) store.setLastBackupAt(now)
        }
    }

    override suspend fun fetch(recoveryCode: String): LeaderboardResult<FetchedBackup> {
        val master = BackupCrypto.decodeCode(recoveryCode) ?: return LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        val token = BackupCrypto.deriveToken(master)
        val me = when (val result = api.me(token)) {
            is LeaderboardResult.Ok -> result.value
            is LeaderboardResult.Err -> return result
        }
        val snapshot = when (val result = api.getBackup(token)) {
            is LeaderboardResult.Ok -> try {
                BackupSnapshotCodec.decode(BackupCrypto.decrypt(BackupCrypto.deriveKey(master), result.value))
            } catch (_: BackupCryptoException) {
                return LeaderboardResult.Err(LeaderboardError.INVALID)
            }
            is LeaderboardResult.Err -> if (result.error == LeaderboardError.NO_BACKUP) null else return result
        }
        val info = MeInfo(me.displayName, me.groups.map(GroupDto::toDomain), me.backupAt)
        return LeaderboardResult.Ok(FetchedBackup(BackupCrypto.encodeCode(master), info, snapshot))
    }
}
