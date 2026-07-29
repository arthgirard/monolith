package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppRepository
import javax.inject.Inject

class GetEssentialPackagesUseCase @Inject constructor(
    private val appRepository: AppRepository,
) {
    suspend operator fun invoke(): Set<String> = appRepository.getEssentialPackages()
}
