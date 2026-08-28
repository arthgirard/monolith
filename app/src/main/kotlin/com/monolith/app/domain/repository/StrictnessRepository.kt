package com.monolith.app.domain.repository

import com.monolith.app.domain.model.StrictnessLevel
import kotlinx.coroutines.flow.Flow

interface StrictnessRepository {
    fun observeStrictness(): Flow<StrictnessLevel>

    suspend fun setStrictness(level: StrictnessLevel)
}
