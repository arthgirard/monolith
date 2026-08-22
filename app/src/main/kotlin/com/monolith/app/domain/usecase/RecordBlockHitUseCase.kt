package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.BlockRepository
import javax.inject.Inject

/**
 * Records that the wall was met. Called by the accessibility service at the same moment it
 * decides to show the overlay, so the count the overlay reads back is the count of times it
 * actually appeared -- not of times an app was merely opened.
 */
class RecordBlockHitUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(packageName: String) = blockRepository.recordBlockHit(packageName)
}
