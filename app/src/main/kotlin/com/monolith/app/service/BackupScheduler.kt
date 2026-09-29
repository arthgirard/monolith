package com.monolith.app.service

import android.util.Log
import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.repository.LeaderboardRepository
import com.monolith.app.domain.usecase.BackupThrottle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the encrypted backup recent without a retry queue. Idle until backup is turned on; then a
 * change to history or setup uploads once things settle, at most once per three hours. Turning
 * backup on uploads right away, so a new recovery code always has something behind it.
 */
@Singleton
class BackupScheduler @Inject constructor(
    private val preferences: MonolithPreferences,
    private val backupRepository: BackupRepository,
    private val leaderboardRepository: LeaderboardRepository,
) {
    private enum class Trigger { CHANGE, APP_OPEN, ENABLED }

    private val appOpens = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Uploads if one is due, and migrates an identity from before backups. */
    fun onAppOpen() {
        appOpens.tryEmit(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        val enabled = backupRepository.observeBackupEnabled()
            .distinctUntilChanged()
            // This process also hosts enforcement: an unreadable store must not crash it.
            .catch { Log.w(TAG, "Backup triggers stopped", it) }
        val changes = enabled
            .flatMapLatest { on -> if (on) localChanges() else emptyFlow() }
            .debounce(DEBOUNCE_MILLIS)
            .map { Trigger.CHANGE }
        // Off to on only: the value read at process start is not a user turning it on.
        var previous: Boolean? = null
        val enables = enabled.transform { on ->
            if (previous == false && on) emit(Trigger.ENABLED)
            previous = on
        }
        val triggers = merge(changes, enables, appOpens.map { Trigger.APP_OPEN })
        scope.launch {
            // collectLatest: a newer trigger cancels an upload in flight, and still finds the
            // throttle due (the cancelled one stamped nothing), so the fresher data goes up.
            triggers.collectLatest { trigger ->
                try {
                    handle(trigger)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Backup failed", e)
                }
            }
        }
    }

    private suspend fun handle(trigger: Trigger) {
        if (trigger == Trigger.APP_OPEN) migrateLegacyIdentity()
        if (!backupRepository.observeBackupEnabled().first()) return
        val now = System.currentTimeMillis()
        if (trigger != Trigger.ENABLED && !BackupThrottle.due(backupRepository.observeLastBackupAt().first(), now)) return
        val result = backupRepository.upload(preferences.exportSnapshot(), now)
        if (result is LeaderboardResult.Err) Log.w(TAG, "Backup upload refused: ${result.error}")
    }

    // An identity from before backups has no recovery code until it moves to a phone-made token.
    // Every app open tries again until that succeeds, whether or not backup is on.
    private suspend fun migrateLegacyIdentity() {
        val identity = leaderboardRepository.observeIdentity().first() ?: return
        if (identity.master != null) return
        val result = backupRepository.ensureIdentity(null)
        if (result is LeaderboardResult.Err) Log.w(TAG, "Identity migration failed: ${result.error}")
    }

    // Each source deduplicated on its own: DataStore re-emits every flow on any write, and a
    // block hit or a pause must not count as a change. A failing source ends only itself.
    private fun localChanges(): Flow<Unit> = merge(
        preferences.blockSessions.distinctUntilChanged().map { },
        preferences.blockedPackages.distinctUntilChanged().map { },
        preferences.importantPeople.distinctUntilChanged().map { },
        preferences.blockSchedules.distinctUntilChanged().map { },
        preferences.strictnessLevel.distinctUntilChanged().map { },
    ).catch { Log.w(TAG, "Backup local changes stopped", it) }

    private companion object {
        const val TAG = "BackupScheduler"
        const val DEBOUNCE_MILLIS = 30_000L
    }
}
