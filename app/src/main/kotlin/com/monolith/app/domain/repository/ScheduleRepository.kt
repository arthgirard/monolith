package com.monolith.app.domain.repository

import com.monolith.app.domain.model.BlockSchedule
import kotlinx.coroutines.flow.Flow

interface ScheduleRepository {
    fun observeSchedules(): Flow<List<BlockSchedule>>

    /** Inserts [schedule], or replaces the stored rule carrying the same id. */
    suspend fun saveSchedule(schedule: BlockSchedule)

    suspend fun deleteSchedule(id: String)

    fun observeLastHandledFire(): Flow<Long?>

    suspend fun setLastHandledFire(millis: Long)
}
