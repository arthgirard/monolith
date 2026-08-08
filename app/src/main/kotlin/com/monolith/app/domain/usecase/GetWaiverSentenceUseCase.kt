package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Builds the sentence [packageName]'s solver must copy exactly to confirm the unlock. */
class GetWaiverSentenceUseCase @Inject constructor(
    private val appRepository: AppRepository,
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(packageName: String): String {
        val label = appRepository.getAppLabel(packageName)
        val now = System.currentTimeMillis()
        val blockState = blockRepository.observeBlockState().first()
        val activeSessionStart = blockRepository.observeActiveSessionStart().first()
        val streakMillis = TimeSavedCalculator.ongoingSessions(blockState, activeSessionStart, now)
            .sumOf { it.durationMillis }
        return WaiverGenerator.generate(label, streakMillis)
    }
}
