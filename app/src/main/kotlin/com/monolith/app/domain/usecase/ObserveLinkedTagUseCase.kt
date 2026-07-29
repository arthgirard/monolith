package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveLinkedTagUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
) {
    operator fun invoke(): Flow<NfcTagLink?> = blockRepository.observeLinkedTag()
}
