package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveBlockedPackagesUseCase @Inject constructor(
    private val appRepository: AppRepository,
) {
    operator fun invoke(): Flow<Set<String>> = appRepository.observeBlockedPackages()
}
