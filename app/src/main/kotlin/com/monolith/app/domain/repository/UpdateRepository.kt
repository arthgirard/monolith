package com.monolith.app.domain.repository

import com.monolith.app.domain.model.DownloadState
import com.monolith.app.domain.model.UpdateCheckResult
import kotlinx.coroutines.flow.Flow

interface UpdateRepository {
    suspend fun checkForUpdate(): UpdateCheckResult

    fun downloadApk(url: String): Flow<DownloadState>

    fun canInstallPackages(): Boolean
}
