package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** How many times [packageName] has been blocked so far today, in the device's local day. */
class GetBlockHitsTodayUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
) {
    operator fun invoke(packageName: String): Flow<Int> =
        blockRepository.observeBlockHits().map { hits ->
            BlockHitLog.countToday(hits, packageName, System.currentTimeMillis())
        }
}
