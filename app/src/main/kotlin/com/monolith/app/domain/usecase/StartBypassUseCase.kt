package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockState.Companion.BYPASS_DURATION_MILLIS
import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.BlockRepository
import javax.inject.Inject

class StartBypassUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
    private val appUnlockRepository: AppUnlockRepository,
) {
    suspend operator fun invoke(durationMillis: Long = BYPASS_DURATION_MILLIS) {
        blockRepository.startBypass(durationMillis)
        // Enforcement stops outright for the length of the bypass, so everything bought against
        // the old enforcement window dies with it -- same reset a tag tap performs (see
        // [ToggleBlockModeFromTagUseCase]). Without this a puzzle solved but never waived stays
        // on disk, and the overlay re-opens straight onto that stale waiver once the bypass runs
        // out instead of the block screen; a five-minute app unlock would likewise carry over.
        appUnlockRepository.clearUnlocks()
    }
}
