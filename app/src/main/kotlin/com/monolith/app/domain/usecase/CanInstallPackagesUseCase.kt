package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.UpdateRepository
import javax.inject.Inject

class CanInstallPackagesUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
) {
    operator fun invoke(): Boolean = updateRepository.canInstallPackages()
}
