package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.StrictnessRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Returns [packageName]'s already-solved code-breaker if one is waiting on its waiver, and a
 * freshly generated one in every other case. Null when the current strictness level has no
 * per-app escape at all, so nothing is generated and nothing is written.
 *
 * Half-finished puzzles deliberately do not survive: walking away mid-guess and coming back costs
 * the whole puzzle again, otherwise the cheapest bypass is chipping at one secret a guess at a
 * time across a dozen re-openings. A solved one does survive -- that price is already paid and
 * only the waiver is left (see [SubmitWaiverUseCase], whose grant clears the row).
 */
class GetCodeBreakerChallengeUseCase @Inject constructor(
    private val appUnlockRepository: AppUnlockRepository,
    private val strictnessRepository: StrictnessRepository,
) {
    suspend operator fun invoke(packageName: String): CodeBreaker? {
        if (!strictnessRepository.observeStrictness().first().allowsAppUnlock) return null

        appUnlockRepository.observeCodeBreakers().first()[packageName]
            ?.takeIf { it.isSolved }
            ?.let { return it }

        val codeBreaker = CodeBreakerGenerator.generate()
        appUnlockRepository.saveCodeBreaker(packageName, codeBreaker)
        return codeBreaker
    }
}
