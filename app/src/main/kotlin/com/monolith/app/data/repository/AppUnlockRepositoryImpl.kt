package com.monolith.app.data.repository

import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.repository.AppUnlockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUnlockRepositoryImpl @Inject constructor(
    private val preferences: MonolithPreferences,
) : AppUnlockRepository {

    override fun observeUnlockedPackages(): Flow<Map<String, Long>> = preferences.appUnlocks

    override fun observeCodeBreakers(): Flow<Map<String, CodeBreaker>> = preferences.codeBreakers

    override suspend fun saveCodeBreaker(packageName: String, codeBreaker: CodeBreaker) {
        preferences.saveCodeBreaker(packageName, codeBreaker)
    }

    override suspend fun grantUnlock(packageName: String, durationMillis: Long) {
        preferences.grantAppUnlock(packageName, durationMillis)
    }

    override suspend fun clearUnlocks() {
        preferences.clearAppUnlocks()
    }
}
