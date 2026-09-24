package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Puts the wall back up before a pause runs out on its own: the emergency bypass and every live
 * app unlock end now. No gate in front of it, since it only ever makes blocking stricter.
 *
 * Returns whether anything was actually paused. Off, or already holding, it writes nothing: a
 * notification action tapped a moment after the window closed must not rewrite the stores.
 */
class ResumeBlockingUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
    private val appUnlockRepository: AppUnlockRepository,
) {
    suspend operator fun invoke(): Boolean {
        val now = System.currentTimeMillis()
        val state = blockRepository.observeBlockState().first()
        if (!state.isActive) return false
        val unlockLive = appUnlockRepository.observeUnlockedPackages().first().values.any { it > now }
        if (!state.isBypassActive(now) && !unlockLive) return false
        blockRepository.endPauses()
        return true
    }
}
