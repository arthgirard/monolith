package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.domain.repository.ScheduleRepository
import com.monolith.app.service.ScheduleAlarmScheduler
import javax.inject.Inject

/** Persists a rule and immediately re-arms, so the pending alarm always reflects what's stored. */
class SaveBlockScheduleUseCase @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val alarmScheduler: ScheduleAlarmScheduler,
) {
    suspend operator fun invoke(schedule: BlockSchedule) {
        scheduleRepository.saveSchedule(schedule)
        // Close the watermark at the moment of the edit, or a rule created at 23:00 for "22:00,
        // today" would look to catch-up like a fire the device slept through, and Monolith would
        // switch itself on the next time the process starts. Nothing before an edit is owed.
        // The store only ever moves this forward, so an already-later watermark is untouched.
        scheduleRepository.setLastHandledFire(System.currentTimeMillis())
        alarmScheduler.rearm()
    }
}
