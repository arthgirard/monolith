package com.monolith.app.data.repository

import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleRepositoryImpl @Inject constructor(
    private val preferences: MonolithPreferences,
) : ScheduleRepository {

    override fun observeSchedules(): Flow<List<BlockSchedule>> = preferences.blockSchedules

    override suspend fun saveSchedule(schedule: BlockSchedule) {
        val current = preferences.blockSchedules.first()
        val updated = current.filterNot { it.id == schedule.id } + schedule
        preferences.setBlockSchedules(updated.sortedBy { it.startTime })
    }

    override suspend fun deleteSchedule(id: String) {
        val current = preferences.blockSchedules.first()
        preferences.setBlockSchedules(current.filterNot { it.id == id })
    }

    override fun observeLastHandledFire(): Flow<Long?> = preferences.scheduleLastFire

    override suspend fun setLastHandledFire(millis: Long) {
        preferences.setScheduleLastFire(millis)
    }
}
