package com.monolith.app.ui.update

import com.monolith.app.domain.model.AppUpdate
import com.monolith.app.domain.model.DownloadState
import com.monolith.app.domain.model.UpdateCheckResult
import com.monolith.app.domain.usecase.CanInstallPackagesUseCase
import com.monolith.app.domain.usecase.CheckForUpdateUseCase
import com.monolith.app.domain.usecase.DownloadUpdateUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val update: AppUpdate) : UpdateUiState
    data class NeedsInstallPermission(val update: AppUpdate) : UpdateUiState
    data class Downloading(val fraction: Float?) : UpdateUiState
    data class ReadyToInstall(val file: File) : UpdateUiState
    /** [update] is set when the download itself failed, so the dialog can offer a retry. */
    data class Failed(val message: String, val update: AppUpdate? = null) : UpdateUiState
}

/**
 * Check, download, install: the steps an update goes through, shared by the check in Settings and
 * the prompt on launch. Each ViewModel gets its own instance and drives it from its own scope.
 */
class UpdateFlow @Inject constructor(
    private val checkForUpdate: CheckForUpdateUseCase,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val canInstallPackages: CanInstallPackagesUseCase,
) {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state

    /** The tapped check: every outcome, up to date or failed included, is the caller's to show. */
    fun check(scope: CoroutineScope) {
        if (_state.value == UpdateUiState.Checking) return
        _state.value = UpdateUiState.Checking
        scope.launch {
            _state.value = when (val result = checkForUpdate()) {
                is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(result.update)
                UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.Failure -> UpdateUiState.Failed(result.reason)
            }
        }
    }

    /**
     * The check nobody asked for: only a release [shouldOffer] accepts ever surfaces. Being up to
     * date or offline isn't worth interrupting anyone over.
     */
    fun checkQuietly(scope: CoroutineScope, shouldOffer: suspend (AppUpdate) -> Boolean) {
        if (_state.value != UpdateUiState.Idle) return
        scope.launch {
            val result = checkForUpdate() as? UpdateCheckResult.UpdateAvailable ?: return@launch
            if (_state.value == UpdateUiState.Idle && shouldOffer(result.update)) {
                _state.value = UpdateUiState.Available(result.update)
            }
        }
    }

    fun startDownload(scope: CoroutineScope, update: AppUpdate) {
        if (!canInstallPackages()) {
            _state.value = UpdateUiState.NeedsInstallPermission(update)
            return
        }
        scope.launch {
            downloadUpdate(update.downloadUrl).collect { state ->
                _state.value = when (state) {
                    is DownloadState.Progress -> UpdateUiState.Downloading(state.fraction)
                    is DownloadState.Complete -> UpdateUiState.ReadyToInstall(state.file)
                    is DownloadState.Failed -> UpdateUiState.Failed(state.reason, update)
                }
            }
        }
    }

    /** Back from Android's install-permission screen: carry on with the download if it was granted. */
    fun onResume(scope: CoroutineScope) {
        val state = _state.value
        if (state is UpdateUiState.NeedsInstallPermission && canInstallPackages()) {
            startDownload(scope, state.update)
        }
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }
}
