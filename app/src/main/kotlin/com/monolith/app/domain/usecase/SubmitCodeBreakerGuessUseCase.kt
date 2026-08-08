package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.repository.AppUnlockRepository
import javax.inject.Inject

/**
 * Applies one guess to [packageName]'s code-breaker and persists it. Solving it only unlocks the
 * waiver stage -- it does not grant the unlock by itself, see [SubmitWaiverUseCase].
 *
 * Spending the last of [CodeBreaker.MAX_GUESSES] without solving it throws the whole board away
 * and starts a fresh secret, so a burned puzzle costs every deduction made against it rather than
 * leaving the solver one guess short of a code they've already half-mapped.
 */
class SubmitCodeBreakerGuessUseCase @Inject constructor(
    private val appUnlockRepository: AppUnlockRepository,
) {
    suspend operator fun invoke(packageName: String, current: CodeBreaker, values: List<Int>): Result {
        val updated = current.withGuess(values)
        if (!updated.isOutOfTries) {
            appUnlockRepository.saveCodeBreaker(packageName, updated)
            return Result(updated, restarted = false)
        }
        val fresh = CodeBreakerGenerator.generate()
        appUnlockRepository.saveCodeBreaker(packageName, fresh)
        return Result(fresh, restarted = true)
    }

    /** [restarted] means [codeBreaker] is a brand-new secret replacing one that ran out of tries. */
    data class Result(val codeBreaker: CodeBreaker, val restarted: Boolean)
}
