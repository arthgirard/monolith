package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.StrictnessRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Rejects changes while Monolith is active, the same rule [SaveBlockedAppsUseCase] applies.
 *
 * Tightening mid-session would be harmless on its own, but the rule is worth more than the
 * exception: a setting that is sometimes editable is one the user has to reason about at the
 * moment they least want to. Off means editable, on means not, for the block list and this alike.
 */
class SaveStrictnessLevelUseCase @Inject constructor(
    private val strictnessRepository: StrictnessRepository,
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(level: StrictnessLevel): Result<Unit> {
        if (blockRepository.observeBlockState().first().isActive) {
            return Result.failure(IllegalStateException("Monolith is active; unlock with your tag first."))
        }
        strictnessRepository.setStrictness(level)
        return Result.success(Unit)
    }
}
