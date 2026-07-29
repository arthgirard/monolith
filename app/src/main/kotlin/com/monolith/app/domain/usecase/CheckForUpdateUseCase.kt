package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.UpdateCheckResult
import com.monolith.app.domain.repository.UpdateRepository
import javax.inject.Inject

class CheckForUpdateUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
) {
    suspend operator fun invoke(): UpdateCheckResult = updateRepository.checkForUpdate()
}
