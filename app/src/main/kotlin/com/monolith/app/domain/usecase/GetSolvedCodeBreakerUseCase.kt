package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.repository.AppUnlockRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * [packageName]'s code-breaker only if it was already solved and is waiting on its waiver, null
 * otherwise. Peeks without generating anything, so the overlay can drop straight back into the
 * waiver step on re-open instead of showing the idle screen and its "solve a puzzle" entry point.
 */
class GetSolvedCodeBreakerUseCase @Inject constructor(
    private val appUnlockRepository: AppUnlockRepository,
) {
    suspend operator fun invoke(packageName: String): CodeBreaker? =
        appUnlockRepository.observeCodeBreakers().first()[packageName]?.takeIf { it.isSolved }
}
