package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveBlockSchedulesUseCase @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
) {
    operator fun invoke(): Flow<List<BlockSchedule>> = scheduleRepository.observeSchedules()
}
