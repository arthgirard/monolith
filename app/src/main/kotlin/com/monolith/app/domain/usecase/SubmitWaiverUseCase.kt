package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppUnlockRepository
import javax.inject.Inject

/** Grants [packageName]'s unlock once its waiver text has been copied exactly (checked by the caller). */
class SubmitWaiverUseCase @Inject constructor(
    private val appUnlockRepository: AppUnlockRepository,
) {
    suspend operator fun invoke(packageName: String, durationMillis: Long) {
        appUnlockRepository.grantUnlock(packageName, durationMillis)
    }
}
