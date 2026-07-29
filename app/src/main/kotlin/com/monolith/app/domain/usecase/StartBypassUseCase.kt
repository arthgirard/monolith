package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockState.Companion.BYPASS_DURATION_MILLIS
import com.monolith.app.domain.repository.BlockRepository
import javax.inject.Inject

class StartBypassUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(durationMillis: Long = BYPASS_DURATION_MILLIS) {
        blockRepository.startBypass(durationMillis)
    }
}
