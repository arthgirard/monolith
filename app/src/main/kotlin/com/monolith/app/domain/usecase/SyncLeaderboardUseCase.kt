package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.SharedApp
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.LeaderboardRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * What one upload did: whether the server took it, and when the running pause ends, so the
 * caller can upload the resumed streak then.
 */
data class SyncOutcome(val synced: Boolean, val pauseEndsAt: Long?)

/**
 * Uploads the last 35 days. Always the whole window, so a failed sync needs no retry: the next
 * one carries everything it would have.
 */
class SyncLeaderboardUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
    private val leaderboardRepository: LeaderboardRepository,
    private val appRepository: AppRepository,
) {
    suspend operator fun invoke(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): SyncOutcome {
        val blockState = blockRepository.observeBlockState().first()
        val activeSessionStart = blockRepository.observeActiveSessionStart().first()
        val sessions = blockRepository.observeBlockSessions().first()
        val pauses = blockRepository.observePauses().first()
        val ongoing = TimeSavedCalculator.ongoingSessions(blockState, activeSessionStart, now)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val result = leaderboardRepository.sync(
            days = DailyAggregates.build(sessions, ongoing, pauses, today, zone),
            streakStartedAt = DailyAggregates.streakStartedAt(ongoing, now),
            blockedApps = blockedApps(),
        )
        return SyncOutcome(
            synced = result is LeaderboardResult.Ok,
            pauseEndsAt = DailyAggregates.pauseEndsAt(blockState, activeSessionStart, now),
        )
    }

    /**
     * Named as this phone names them, within what the server takes. A package this phone can't
     * name (uninstalled since, or restored from another phone) blocks nothing here and stays out.
     */
    private suspend fun blockedApps(): List<SharedApp> =
        appRepository.observeBlockedPackages().first()
            .mapNotNull { packageName ->
                appRepository.findAppLabel(packageName)?.let { SharedApp(packageName, it.take(MAX_LABEL_LENGTH)) }
            }
            .sortedBy { it.label.lowercase() }
            .take(MAX_SHARED_APPS)

    private companion object {
        const val MAX_SHARED_APPS = 200
        const val MAX_LABEL_LENGTH = 64
    }
}
