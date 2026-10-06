package com.monolith.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.AppUpdate
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.domain.model.TagCodeState
import com.monolith.app.domain.model.tagCodeState
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.repository.LeaderboardRepository
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.ObserveLinkedTagUseCase
import com.monolith.app.domain.usecase.ObserveStrictnessUseCase
import com.monolith.app.ui.update.UpdateFlow
import com.monolith.app.ui.update.UpdateUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    /** Null unless the Settings row should offer to put the code on the tag. */
    val tagCode: TagCodeState? = null,
    /** The tag carries a code, so the privacy caption names it too. */
    val tagCarriesCode: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeStrictness: ObserveStrictnessUseCase,
    observeLinkedTag: ObserveLinkedTagUseCase,
    observeBlockState: ObserveBlockStateUseCase,
    private val updateFlow: UpdateFlow,
    private val backupRepository: BackupRepository,
    private val leaderboardRepository: LeaderboardRepository,
) : ViewModel() {

    val displayName: StateFlow<String> = leaderboardRepository.observeDisplayName()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _nameBusy = MutableStateFlow(false)
    val nameBusy: StateFlow<Boolean> = _nameBusy

    /** Friends see the new name once the server has it; offline, nothing changes. */
    fun setDisplayName(name: String, onSaved: () -> Unit) {
        if (_nameBusy.value) return
        _nameBusy.value = true
        viewModelScope.launch {
            try {
                when (val result = leaderboardRepository.setDisplayName(name)) {
                    is LeaderboardResult.Ok -> onSaved()
                    is LeaderboardResult.Err -> _errors.tryEmit(result.error)
                }
            } finally {
                _nameBusy.value = false
            }
        }
    }

    private val backupBusy = MutableStateFlow(false)

    private val linkedTag = observeLinkedTag()

    val backupState: StateFlow<BackupUiState> = combine(
        backupRepository.observeBackupEnabled(),
        backupRepository.observeLastBackupAt(),
        backupRepository.observeRecoveryCode(),
        backupBusy,
        linkedTag,
    ) { enabled, lastBackupAt, code, busy, tag ->
        val state = tagCodeState(tag, enabled, code)
        BackupUiState(
            enabled = enabled,
            lastBackupAt = lastBackupAt,
            recoveryCode = code,
            busy = busy,
            // CURRENT needs no row: the tag already does its job.
            tagCode = state?.takeIf { it != TagCodeState.CURRENT },
            tagCarriesCode = tag?.code != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BackupUiState())

    private val _errors = MutableSharedFlow<LeaderboardError>(extraBufferCapacity = 1)

    /**
     * A backup toggle or a rename that didn't go through: turning backup on needs the server to
     * register the identity, and a rename needs it to take the new name.
     */
    val errors: SharedFlow<LeaderboardError> = _errors

    fun setBackupEnabled(enabled: Boolean) {
        if (backupBusy.value) return
        backupBusy.value = true
        viewModelScope.launch {
            try {
                val result = backupRepository.setBackupEnabled(enabled)
                if (result is LeaderboardResult.Err) _errors.tryEmit(result.error)
            } finally {
                backupBusy.value = false
            }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        observeStrictness(),
        linkedTag,
        observeBlockState(),
    ) { strictness, linkedTag, blockState ->
        SettingsUiState(strictness = strictness, linkedTag = linkedTag, isLocked = blockState.isActive)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    val updateState: StateFlow<UpdateUiState> = updateFlow.state

    fun checkForUpdates() = updateFlow.check(viewModelScope)

    fun startDownload(update: AppUpdate) = updateFlow.startDownload(viewModelScope, update)

    fun onUpdateResume() = updateFlow.onResume(viewModelScope)

    fun dismissUpdateDialog() = updateFlow.dismiss()
}
