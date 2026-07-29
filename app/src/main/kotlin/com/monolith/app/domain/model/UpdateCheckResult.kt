package com.monolith.app.domain.model

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val versionName: String, val downloadUrl: String) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failure(val reason: String) : UpdateCheckResult
}
