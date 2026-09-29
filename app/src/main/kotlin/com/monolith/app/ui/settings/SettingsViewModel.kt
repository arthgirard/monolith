package com.monolith.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.DownloadState
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.domain.model.UpdateCheckResult
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.usecase.CanInstallPackagesUseCase
import com.monolith.app.domain.usecase.CheckForUpdateUseCase
import com.monolith.app.domain.usecase.DownloadUpdateUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.ObserveLinkedTagUseCase
import com.monolith.app.domain.usecase.ObserveStrictnessUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val strictness: StrictnessLevel = StrictnessLevel.DEFAULT,
    val linkedTag: NfcTagLink? = null,
    /**
     * True while Monolith is active. The rows that decide how hard it is to get out -- the level
     * itself, and which tag opens it -- are the tag's to change from then on, same rule the
     * blocked-app list already follows.
     */
    val isLocked: Boolean = false,
)

data class BackupUiState(
    val enabled: Boolean = false,
    val lastBackupAt: Long? = null,
    /** Null until an identity with a master exists; "Show recovery code" stays hidden until then. */
    val recoveryCode: String? = null,
    val busy: Boolean = false,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val versionName: String, val downloadUrl: String) : UpdateUiState
    data class NeedsInstallPermission(val versionName: String, val downloadUrl: String) : UpdateUiState
    data class Downloading(val fraction: Float?) : UpdateUiState
    data class ReadyToInstall(val file: File) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeStrictness: ObserveStrictnessUseCase,
    observeLinkedTag: ObserveLinkedTagUseCase,
    observeBlockState: ObserveBlockStateUseCase,
    private val checkForUpdate: CheckForUpdateUseCase,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val canInstallPackages: CanInstallPackagesUseCase,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    private val backupBusy = MutableStateFlow(false)

    val backupState: StateFlow<BackupUiState> = combine(
        backupRepository.observeBackupEnabled(),
        backupRepository.observeLastBackupAt(),
        backupRepository.observeRecoveryCode(),
        backupBusy,
    ) { enabled, lastBackupAt, code, busy ->
        BackupUiState(enabled = enabled, lastBackupAt = lastBackupAt, recoveryCode = code, busy = busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BackupUiState())

    private val _backupErrors = MutableSharedFlow<LeaderboardError>(extraBufferCapacity = 1)

    /** A toggle that didn't go through (turning on needs the server to register the identity). */
    val backupErrors: SharedFlow<LeaderboardError> = _backupErrors

    fun setBackupEnabled(enabled: Boolean) {
        if (backupBusy.value) return
        backupBusy.value = true
        viewModelScope.launch {
            try {
                val result = backupRepository.setBackupEnabled(enabled)
                if (result is LeaderboardResult.Err) _backupErrors.tryEmit(result.error)
            } finally {
                backupBusy.value = false
            }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        observeStrictness(),
        observeLinkedTag(),
        observeBlockState(),
    ) { strictness, linkedTag, blockState ->
        SettingsUiState(strictness = strictness, linkedTag = linkedTag, isLocked = blockState.isActive)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState

    fun checkForUpdates() {
        if (_updateState.value == UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            _updateState.value = when (val result = checkForUpdate()) {
                is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(result.versionName, result.downloadUrl)
                UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.Failure -> UpdateUiState.Failed(result.reason)
            }
        }
    }

    fun startDownload(versionName: String, downloadUrl: String) {
        if (!canInstallPackages()) {
            _updateState.value = UpdateUiState.NeedsInstallPermission(versionName, downloadUrl)
            return
        }
        viewModelScope.launch {
            downloadUpdate(downloadUrl).collect { state ->
                _updateState.value = when (state) {
                    is DownloadState.Progress -> UpdateUiState.Downloading(state.fraction)
                    is DownloadState.Complete -> UpdateUiState.ReadyToInstall(state.file)
                    is DownloadState.Failed -> UpdateUiState.Failed(state.reason)
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateState.value = UpdateUiState.Idle
    }
}
