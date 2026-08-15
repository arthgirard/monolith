package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.ScheduleRepository
import com.monolith.app.service.ScheduleAlarmScheduler
import javax.inject.Inject

/** Removes a rule and re-arms; deleting the last one leaves no alarm pending at all. */
class DeleteBlockScheduleUseCase @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val alarmScheduler: ScheduleAlarmScheduler,
) {
    suspend operator fun invoke(id: String) {
        scheduleRepository.deleteSchedule(id)
        alarmScheduler.rearm()
    }
}
