package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.StrictnessRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Rejects changes while Monolith is active, for the reason [SaveStrictnessLevelUseCase] gives.
 * Here it is also the whole point: a guard that could be switched off mid-session would only
 * move the escape one screen over.
 */
class SaveUninstallGuardUseCase @Inject constructor(
    private val strictnessRepository: StrictnessRepository,
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(enabled: Boolean): Result<Unit> {
        if (blockRepository.observeBlockState().first().isActive) {
            return Result.failure(IllegalStateException("Monolith is active; unlock with your tag first."))
        }
        strictnessRepository.setUninstallGuard(enabled)
        return Result.success(Unit)
    }
}
