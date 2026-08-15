package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Turns Monolith on without a tag. The counterpart to [ToggleBlockModeFromTagUseCase] deliberately
 * has no "off" direction: friction belongs on the way out, so starting a session is free and
 * ending one still costs a tap.
 */
class ActivateBlockModeUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
    private val appUnlockRepository: AppUnlockRepository,
) {
    /** True if this call is what turned Monolith on; false if it was already on. */
    suspend operator fun invoke(): Boolean {
        // Idempotent on purpose. Re-entering an already-running cycle must not restamp
        // SESSION_STARTED_AT (that would silently reset the streak) and must not clear a bypass or
        // per-app unlock the user is in the middle of.
        if (blockRepository.observeBlockState().first().isActive) return false

        blockRepository.setBlockModeActive(true)
        // Same fresh-cycle reset a tag tap performs: a bypass allowance or app unlock left over
        // from the previous cycle must not carry into this one.
        blockRepository.clearBypass()
        appUnlockRepository.clearUnlocks()
        return true
    }
}
