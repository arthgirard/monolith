package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppUnlockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveUnlockedPackagesUseCase @Inject constructor(
    private val appUnlockRepository: AppUnlockRepository,
) {
    /** packageName -> unlock expiry millis. Entries in the past are stale but harmless. */
    operator fun invoke(): Flow<Map<String, Long>> = appUnlockRepository.observeUnlockedPackages()
}
