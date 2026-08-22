package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppRepository
import javax.inject.Inject

/** Builds the sentence [packageName]'s solver must copy exactly to confirm the unlock. */
class GetWaiverSentenceUseCase @Inject constructor(
    private val appRepository: AppRepository,
    private val getCurrentStreak: GetCurrentStreakUseCase,
) {
    suspend operator fun invoke(packageName: String): String {
        val label = appRepository.getAppLabel(packageName)
        // Shared with the block overlay's "Held" readout: the sentence names the same streak the
        // wall above it is showing, so the two can never quote different numbers.
        return WaiverGenerator.generate(label, getCurrentStreak.current())
    }
}
