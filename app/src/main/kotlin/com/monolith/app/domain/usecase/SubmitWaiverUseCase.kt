package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.StrictnessRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Grants [packageName]'s unlock once its waiver text has been copied exactly (checked by the caller). */
class SubmitWaiverUseCase @Inject constructor(
    private val appUnlockRepository: AppUnlockRepository,
    private val strictnessRepository: StrictnessRepository,
) {
    suspend operator fun invoke(packageName: String, durationMillis: Long): Result<Unit> {
        // The wall offers no route here above STANDARD, but this is the call that actually spends
        // the streak, so it carries the check rather than trusting the screen that led to it.
        if (!strictnessRepository.observeStrictness().first().allowsAppUnlock) {
            return Result.failure(IllegalStateException("Strictness forbids per-app unlocks."))
        }
        appUnlockRepository.grantUnlock(packageName, durationMillis)
        return Result.success(Unit)
    }
}
