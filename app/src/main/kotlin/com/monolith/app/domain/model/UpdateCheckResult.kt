package com.monolith.app.domain.model

/** A newer release on GitHub. [releaseNotes] are the release body's lines, headings and bullets stripped. */
data class AppUpdate(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: List<String>,
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val update: AppUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failure(val reason: String) : UpdateCheckResult
}
