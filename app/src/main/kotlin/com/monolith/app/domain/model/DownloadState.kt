package com.monolith.app.domain.model

import java.io.File

sealed interface DownloadState {
    /** [fraction] is null when the server didn't send a content-length (indeterminate progress). */
    data class Progress(val fraction: Float?) : DownloadState
    data class Complete(val file: File) : DownloadState
    data class Failed(val reason: String) : DownloadState
}
