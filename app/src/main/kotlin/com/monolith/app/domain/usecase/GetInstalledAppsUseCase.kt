package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.AppInfo
import com.monolith.app.domain.repository.AppRepository
import javax.inject.Inject

class GetInstalledAppsUseCase @Inject constructor(
    private val appRepository: AppRepository,
) {
    suspend operator fun invoke(): List<AppInfo> =
        appRepository.getInstalledApps().sortedBy { it.label.lowercase() }
}
