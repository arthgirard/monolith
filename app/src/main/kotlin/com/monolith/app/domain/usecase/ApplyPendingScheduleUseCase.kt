package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.pendingFire
import com.monolith.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Activates Monolith if a scheduled occurrence is currently owed, and does nothing otherwise.
 *
 * Every trigger routes through here -- the alarm landing on time, a post-boot catch-up, a plain
 * app start -- because they all ask the same question, and the last-handled watermark makes the
 * answer idempotent no matter how many of them fire at once.
 */
class ApplyPendingScheduleUseCase @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val activateScheduledBlock: ActivateScheduledBlockUseCase,
) {
    /**
     * True only when this call is what switched Monolith on -- not when nothing was owed, and not
     * when the occurrence landed on an already-running session. Callers use it to decide whether
     * there's anything worth telling the user about.
     */
    suspend operator fun invoke(): Boolean {
        val schedules = scheduleRepository.observeSchedules().first()
        val lastHandled = scheduleRepository.observeLastHandledFire().first()
        val fire = schedules.pendingFire(ZonedDateTime.now(), lastHandled) ?: return false
        return activateScheduledBlock(fire.toInstant().toEpochMilli())
    }
}
