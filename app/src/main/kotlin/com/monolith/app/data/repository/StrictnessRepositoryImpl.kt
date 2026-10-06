package com.monolith.app.data.repository

import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.domain.repository.StrictnessRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrictnessRepositoryImpl @Inject constructor(
    private val preferences: MonolithPreferences,
) : StrictnessRepository {

    override fun observeStrictness(): Flow<StrictnessLevel> = preferences.strictnessLevel

    override suspend fun setStrictness(level: StrictnessLevel) {
        preferences.setStrictnessLevel(level)
    }

    override fun observeUninstallGuard(): Flow<Boolean> = preferences.uninstallGuard

    override suspend fun setUninstallGuard(enabled: Boolean) {
        preferences.setUninstallGuard(enabled)
    }
}
