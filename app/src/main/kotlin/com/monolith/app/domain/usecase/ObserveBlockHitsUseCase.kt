package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockHit
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** The whole block-hit log, pruned to [BlockHitLog.RETENTION_MILLIS] on write. */
class ObserveBlockHitsUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
) {
    operator fun invoke(): Flow<List<BlockHit>> = blockRepository.observeBlockHits()
}
