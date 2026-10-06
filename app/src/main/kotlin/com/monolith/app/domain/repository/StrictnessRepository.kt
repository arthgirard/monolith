package com.monolith.app.domain.repository

import com.monolith.app.domain.model.StrictnessLevel
import kotlinx.coroutines.flow.Flow

interface StrictnessRepository {
    fun observeStrictness(): Flow<StrictnessLevel>

    suspend fun setStrictness(level: StrictnessLevel)

    /** Whether an active Monolith keeps the user out of its own uninstall and settings pages. */
    fun observeUninstallGuard(): Flow<Boolean>

    suspend fun setUninstallGuard(enabled: Boolean)
}
