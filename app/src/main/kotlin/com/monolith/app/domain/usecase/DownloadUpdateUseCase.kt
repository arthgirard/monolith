package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.DownloadState
import com.monolith.app.domain.repository.UpdateRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class DownloadUpdateUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
) {
    operator fun invoke(url: String): Flow<DownloadState> = updateRepository.downloadApk(url)
}
