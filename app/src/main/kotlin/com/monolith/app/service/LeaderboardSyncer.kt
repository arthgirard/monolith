package com.monolith.app.service

import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.LeaderboardRepository
import com.monolith.app.domain.usecase.SyncLeaderboardUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
 * then any change to sessions, pauses or block state uploads once things settle. The process
 * stays warm through the accessibility service, so these triggers keep firing.
 */
@Singleton
class LeaderboardSyncer @Inject constructor(
    private val blockRepository: BlockRepository,
    private val leaderboardRepository: LeaderboardRepository,
    private val syncLeaderboard: SyncLeaderboardUseCase,
) {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** An explicit upload, for app open and share changes. */
    fun requestSync() {
        requests.tryEmit(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        val triggers = leaderboardRepository.observeMembership()
            .map { it != null }
            .distinctUntilChanged()
            .flatMapLatest { joined -> if (joined) merge(localChanges(), requests) else emptyFlow() }
            .debounce(DEBOUNCE_MILLIS)
        scope.launch {
            // collectLatest: a new trigger cancels a pending pause-end wait, since the upload it
            // causes schedules its own.
            triggers.collectLatest {
                val pauseEndsAt = syncLeaderboard() ?: return@collectLatest
                delay(pauseEndsAt - System.currentTimeMillis() + PAUSE_END_SLACK_MILLIS)
                requestSync()
            }
        }
    }

    // Each source deduplicated on its own: DataStore re-emits every flow on any write, and a
    // block hit alone must not cost an upload.
    private fun localChanges(): Flow<Unit> = merge(
        blockRepository.observeBlockSessions().distinctUntilChanged().map { },
        blockRepository.observePauses().distinctUntilChanged().map { },
        blockRepository.observeBlockState().distinctUntilChanged().map { },
        blockRepository.observeActiveSessionStart().distinctUntilChanged().map { },
    )

    private companion object {
        const val DEBOUNCE_MILLIS = 5_000L
        const val PAUSE_END_SLACK_MILLIS = 1_000L
    }
}
