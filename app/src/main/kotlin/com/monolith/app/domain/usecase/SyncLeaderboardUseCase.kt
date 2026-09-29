package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.LeaderboardRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Uploads the last 35 days. Always the whole window, so a failed sync needs no retry: the next
 * one carries everything it would have.
 */
class SyncLeaderboardUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
    private val leaderboardRepository: LeaderboardRepository,
) {
    /** Returns when the running pause ends, so the caller can upload the resumed streak then. */
    suspend operator fun invoke(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val blockState = blockRepository.observeBlockState().first()
        val activeSessionStart = blockRepository.observeActiveSessionStart().first()
        val sessions = blockRepository.observeBlockSessions().first()
        val pauses = blockRepository.observePauses().first()
        val ongoing = TimeSavedCalculator.ongoingSessions(blockState, activeSessionStart, now)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        leaderboardRepository.sync(
            days = DailyAggregates.build(sessions, ongoing, pauses, today, zone),
            streakStartedAt = DailyAggregates.streakStartedAt(ongoing, now),
        )
        return DailyAggregates.pauseEndsAt(blockState, activeSessionStart, now)
    }
}
