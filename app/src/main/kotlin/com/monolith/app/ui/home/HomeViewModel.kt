package com.monolith.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.DownloadState
import com.monolith.app.domain.model.earliestNextFire
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.domain.model.UpdateCheckResult
import com.monolith.app.domain.usecase.ActivateBlockModeUseCase
import com.monolith.app.domain.usecase.CanInstallPackagesUseCase
import com.monolith.app.domain.usecase.CheckForUpdateUseCase
import com.monolith.app.domain.usecase.DownloadUpdateUseCase
import com.monolith.app.domain.usecase.ObserveActiveSessionStartUseCase
import com.monolith.app.domain.usecase.ObserveBlockSchedulesUseCase
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
import kotlinx.coroutines.flow.Flow
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
import java.time.ZonedDateTime
import javax.inject.Inject

data class HomeUiState(
    val blockState: BlockState = BlockState(),
    val linkedTag: NfcTagLink? = null,
    val todaySavedMillis: Long = 0L,
    val todayBuckets: List<TimeSavedBucket> = emptyList(),
    val nowMillis: Long = System.currentTimeMillis(),
    /** Next armed schedule, or null when none is set. Only worth showing while Monolith is off. */
    val nextScheduledFire: ZonedDateTime? = null,
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
    data object BypassStarted : HomeEvent

    /**
     * The bypass window closed. Nothing used to mark this: the countdown on the status card
     * simply vanished, and the next blocked app produced a block screen with no explanation of
     * where the bypass had gone.
     */
    data object BypassEnded : HomeEvent
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
    observeBlockSchedules: ObserveBlockSchedulesUseCase,
    private val startBypass: StartBypassUseCase,
    private val activateBlockMode: ActivateBlockModeUseCase,
    private val toggleFromTag: ToggleBlockModeFromTagUseCase,
    private val nfcTagBus: NfcTagBus,
    private val checkForUpdate: CheckForUpdateUseCase,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val canInstallPackages: CanInstallPackagesUseCase,
) : ViewModel() {

    private val ticker = MutableStateFlow(System.currentTimeMillis())
    private val zone = ZoneId.systemDefault()

    // Split in two only because combine tops out at five typed flows; nesting keeps the types
    // rather than dropping to the vararg overload's Array<Any?>.
    private val coreState: Flow<HomeUiState> = combine(
        observeBlockState(),
        observeLinkedTag(),
        observeBlockSessions(),
        observeActiveSessionStart(),
        ticker,
    ) { blockState, linkedTag, sessions, activeSessionStart, now ->
        val ongoing = TimeSavedCalculator.ongoingSessions(blockState, activeSessionStart, now)
        val todayBuckets = TimeSavedCalculator.bucketsFor(TimePeriodType.DAY, LocalDate.now(zone), sessions, ongoing)
        HomeUiState(
            blockState = blockState,
            linkedTag = linkedTag,
            todaySavedMillis = todayBuckets.sumOf { it.durationMillis },
            todayBuckets = todayBuckets,
            nowMillis = now,
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        coreState,
        observeBlockSchedules(),
    ) { state, schedules ->
        // Recomputed off the same one-second ticker that drives coreState, so the label rolls over
        // to the following occurrence the moment the current one fires.
        state.copy(nextScheduledFire = schedules.earliestNextFire(ZonedDateTime.now(zone)))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState

    init {
        // Countdown ticks once a second only matter while a bypass is running; cheap either way.
        viewModelScope.launch {
            // Watched from the same tick that drives the countdown rather than from a timer in
            // the enforcement service: a snackbar is only worth emitting while Home is actually
            // on screen, which is exactly when this ViewModel is alive.
            var bypassWasActive = false
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                ticker.value = now
                val bypassActive = uiState.value.blockState.isBypassActive(now)
                if (bypassWasActive && !bypassActive) _events.tryEmit(HomeEvent.BypassEnded)
                bypassWasActive = bypassActive
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
        viewModelScope.launch {
            startBypass()
            _events.tryEmit(HomeEvent.BypassStarted)
        }
    }

    /** Turns Monolith on without a tag. There is no matching "deactivate" -- that stays tag-only. */
    fun activate() {
        viewModelScope.launch {
            if (activateBlockMode()) _events.tryEmit(HomeEvent.Toggled(nowActive = true))
        }
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
