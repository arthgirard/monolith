package com.monolith.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.DownloadState
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.domain.model.UpdateCheckResult
import com.monolith.app.domain.usecase.CanInstallPackagesUseCase
import com.monolith.app.domain.usecase.CheckForUpdateUseCase
import com.monolith.app.domain.usecase.DownloadUpdateUseCase
import com.monolith.app.domain.usecase.ObserveActiveSessionStartUseCase
import com.monolith.app.domain.usecase.ObserveBlockSessionsUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.ObserveLinkedTagUseCase
import com.monolith.app.domain.usecase.StartBypassUseCase
import com.monolith.app.domain.usecase.TimeSavedCalculator
import com.monolith.app.domain.usecase.ToggleBlockModeFromTagUseCase
import com.monolith.app.nfc.NfcBusMode
import com.monolith.app.nfc.NfcTagBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class HomeUiState(
    val blockState: BlockState = BlockState(),
    val linkedTag: NfcTagLink? = null,
    val todaySavedMillis: Long = 0L,
    val todayBuckets: List<TimeSavedBucket> = emptyList(),
    val nowMillis: Long = System.currentTimeMillis(),
) {
    val bypassSecondsRemaining: Long
        get() {
            val expiresAt = blockState.bypassExpiresAtMillis ?: return 0
            return ((expiresAt - nowMillis) / 1000).coerceAtLeast(0)
        }
}

sealed interface HomeEvent {
    data object UnknownTag : HomeEvent
    data object NoTagLinked : HomeEvent
    data class Toggled(val nowActive: Boolean) : HomeEvent
}

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
class HomeViewModel @Inject constructor(
    observeBlockState: ObserveBlockStateUseCase,
    observeLinkedTag: ObserveLinkedTagUseCase,
    observeBlockSessions: ObserveBlockSessionsUseCase,
    observeActiveSessionStart: ObserveActiveSessionStartUseCase,
    private val startBypass: StartBypassUseCase,
    private val toggleFromTag: ToggleBlockModeFromTagUseCase,
    private val nfcTagBus: NfcTagBus,
    private val checkForUpdate: CheckForUpdateUseCase,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val canInstallPackages: CanInstallPackagesUseCase,
) : ViewModel() {

    private val ticker = MutableStateFlow(System.currentTimeMillis())
    private val zone = ZoneId.systemDefault()

    val uiState: StateFlow<HomeUiState> = combine(
        observeBlockState(),
        observeLinkedTag(),
        observeBlockSessions(),
        observeActiveSessionStart(),
        ticker,
    ) { blockState, linkedTag, sessions, activeSessionStart, now ->
        val ongoing = if (blockState.isActive && activeSessionStart != null) {
            BlockSession(activeSessionStart, now)
        } else {
            null
        }
        val todayBuckets = TimeSavedCalculator.bucketsFor(TimePeriodType.DAY, LocalDate.now(zone), sessions, ongoing)
        HomeUiState(
            blockState = blockState,
            linkedTag = linkedTag,
            todaySavedMillis = todayBuckets.sumOf { it.durationMillis },
            todayBuckets = todayBuckets,
            nowMillis = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState

    init {
        // Countdown ticks once a second only matter while a bypass is running; cheap either way.
        viewModelScope.launch {
            while (true) {
                delay(1000)
                ticker.value = System.currentTimeMillis()
            }
        }

        nfcTagBus.tagEvents.onEach { tag ->
            if (nfcTagBus.mode.value != NfcBusMode.TOGGLE) return@onEach
            when (val result = toggleFromTag(tag)) {
                is NfcTapResult.Toggled -> _events.tryEmit(HomeEvent.Toggled(result.nowActive))
                NfcTapResult.UnknownTag -> _events.tryEmit(HomeEvent.UnknownTag)
                NfcTapResult.NoTagLinked -> _events.tryEmit(HomeEvent.NoTagLinked)
            }
        }.launchIn(viewModelScope)
    }

    fun startEmergencyBypass() {
        viewModelScope.launch { startBypass() }
    }

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
