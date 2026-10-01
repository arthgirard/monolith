package com.monolith.app.service

import android.util.Log
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.LeaderboardRepository
import com.monolith.app.domain.usecase.SyncLeaderboardUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the leaderboard near-live without a retry queue. Idle until the member joins a group;
 * then any change to sessions, pauses, block state or blocked apps uploads once things settle. The process
 * stays warm through the accessibility service, so these triggers keep firing. A phone left alone
 * changes nothing, so [LeaderboardHeartbeatWorker] also uploads every few hours: the board stops
 * trusting a member 48 h after their last upload.
 */
@Singleton
class LeaderboardSyncer @Inject constructor(
    private val blockRepository: BlockRepository,
    private val appRepository: AppRepository,
    private val leaderboardRepository: LeaderboardRepository,
    private val syncLeaderboard: SyncLeaderboardUseCase,
) {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val immediateRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lastSynced = MutableStateFlow<Long?>(null)

    /** When the server last took an upload, so a board on screen can refetch its own row. */
    val lastSyncedAt: StateFlow<Long?> = lastSynced.asStateFlow()

    /**
     * An explicit upload, for app open and share changes. [immediate] skips the debounce, for a
     * screen that is about to show this member's own row.
     */
    fun requestSync(immediate: Boolean = false) {
        (if (immediate) immediateRequests else requests).tryEmit(Unit)
    }

    /** Whether the member is in a group, so anything uploading on its own knows to run. */
    fun observeJoined(): Flow<Boolean> = leaderboardRepository.observeIdentity()
        .map { it != null && it.groups.isNotEmpty() }
        .distinctUntilChanged()

    /** One upload now, outside the triggers, for [LeaderboardHeartbeatWorker]. True if the server took it. */
    suspend fun syncNow(): Boolean {
        val synced = syncLeaderboard().synced
        if (synced) lastSynced.value = System.currentTimeMillis()
        return synced
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        val debounced = observeJoined()
            .flatMapLatest { joined -> if (joined) merge(localChanges(), requests) else emptyFlow() }
            .debounce(DEBOUNCE_MILLIS)
            // This process also hosts enforcement: an unreadable identity must not crash it.
            .catch { Log.w(TAG, "Leaderboard triggers stopped", it) }
        // Not gated on the identity: a request sent right after a join can arrive before the
        // identity flow flips, and without an identity the sync makes no network call.
        val triggers = merge(debounced, immediateRequests)
        scope.launch {
            // collectLatest: a new trigger cancels a pending pause-end wait, since the upload it
            // causes schedules its own.
            triggers.collectLatest {
                val outcome = try {
                    syncLeaderboard()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Leaderboard sync failed", e)
                    return@collectLatest
                }
                if (outcome.synced) lastSynced.value = System.currentTimeMillis()
                val pauseEndsAt = outcome.pauseEndsAt ?: return@collectLatest
                delay(pauseEndsAt - System.currentTimeMillis() + PAUSE_END_SLACK_MILLIS)
                requestSync()
            }
        }
    }

    // Each source deduplicated on its own: DataStore re-emits every flow on any write, and a
    // block hit alone must not cost an upload. A failing source ends only itself, so explicit
    // requests keep working.
    private fun localChanges(): Flow<Unit> = merge(
        blockRepository.observeBlockSessions().distinctUntilChanged().map { },
        blockRepository.observePauses().distinctUntilChanged().map { },
        blockRepository.observeBlockState().distinctUntilChanged().map { },
        blockRepository.observeActiveSessionStart().distinctUntilChanged().map { },
        appRepository.observeBlockedPackages().distinctUntilChanged().map { },
    ).catch { Log.w(TAG, "Leaderboard local changes stopped", it) }

    private companion object {
        const val TAG = "LeaderboardSyncer"
        const val DEBOUNCE_MILLIS = 5_000L
        const val PAUSE_END_SLACK_MILLIS = 1_000L
    }
}
