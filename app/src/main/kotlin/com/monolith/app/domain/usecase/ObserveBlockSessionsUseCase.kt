package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveBlockSessionsUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
) {
    operator fun invoke(): Flow<List<BlockSession>> = blockRepository.observeBlockSessions()
}
