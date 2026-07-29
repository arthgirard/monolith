package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Rejects edits while Block Mode is active, so the tag stays the only way out. */
class SaveBlockedAppsUseCase @Inject constructor(
    private val appRepository: AppRepository,
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(packages: Set<String>): Result<Unit> {
        val state = blockRepository.observeBlockState().first()
        if (state.isActive) {
            return Result.failure(IllegalStateException("Block Mode is active; unlock with your tag first."))
        }
        appRepository.setBlockedPackages(packages)
        return Result.success(Unit)
    }
}
