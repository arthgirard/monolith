package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.ScheduleRepository
import javax.inject.Inject

/**
 * A scheduled fire landing. Activation itself is [ActivateBlockModeUseCase]; this adds the
 * watermark that stops the same occurrence being replayed by catch-up later.
 */
class ActivateScheduledBlockUseCase @Inject constructor(
    private val activateBlockMode: ActivateBlockModeUseCase,
    private val scheduleRepository: ScheduleRepository,
) {
    /** [fireMillis] is the occurrence's own scheduled instant, not the moment it was handled. */
    suspend operator fun invoke(fireMillis: Long): Boolean {
        val activated = activateBlockMode()
        // Stamped whether or not activation did anything. An occurrence that arrived while
        // Monolith was already on has still been dealt with, and catch-up must not revisit it.
        scheduleRepository.setLastHandledFire(fireMillis)
        return activated
    }
}
