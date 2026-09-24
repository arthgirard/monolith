package com.monolith.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.earliestNextFire
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.domain.usecase.ActivateBlockModeUseCase
import com.monolith.app.domain.usecase.ObserveActiveSessionStartUseCase
import com.monolith.app.domain.usecase.ObserveBlockSchedulesUseCase
import com.monolith.app.domain.usecase.ObserveBlockSessionsUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.ObserveLinkedTagUseCase
import com.monolith.app.domain.usecase.ObserveStrictnessUseCase
import com.monolith.app.domain.usecase.ObserveUnlockedPackagesUseCase
import com.monolith.app.domain.usecase.ResumeBlockingUseCase
import com.monolith.app.domain.usecase.StartBypassUseCase
import com.monolith.app.domain.usecase.TimeSavedCalculator
import com.monolith.app.domain.usecase.ToggleBlockModeFromTagUseCase
import com.monolith.app.nfc.NfcBusMode
import com.monolith.app.nfc.NfcTagBus
import com.monolith.app.service.EnforcementSegments
import com.monolith.app.service.EnforcementStatus
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
    val strictness: StrictnessLevel = StrictnessLevel.DEFAULT,
    /** packageName -> unlock expiry millis, stale entries included. */
    val unlockedPackages: Map<String, Long> = emptyMap(),
) {
    /**
     * What Monolith is letting through right now, or null while it holds (or is off). Decided by
     * the same rule the enforcement notification uses, so the card and the shade never name
     * different pauses: a bypass outranks any unlock, and of several unlocks the last to expire.
     */
    val pause: EnforcementStatus?
        get() {
            if (!blockState.isActive) return null
            return EnforcementSegments.statusFor(blockState, unlockedPackages, nowMillis)
                .takeIf { it !is EnforcementStatus.Enforcing }
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

    /** The user ended the pause early. Stands in for [BypassEnded], which would say it ran out. */
    data object Resumed : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeBlockState: ObserveBlockStateUseCase,
    observeLinkedTag: ObserveLinkedTagUseCase,
    observeBlockSessions: ObserveBlockSessionsUseCase,
    observeActiveSessionStart: ObserveActiveSessionStartUseCase,
    observeBlockSchedules: ObserveBlockSchedulesUseCase,
    observeStrictness: ObserveStrictnessUseCase,
    observeUnlockedPackages: ObserveUnlockedPackagesUseCase,
    private val startBypass: StartBypassUseCase,
    private val resumeBlocking: ResumeBlockingUseCase,
    private val activateBlockMode: ActivateBlockModeUseCase,
    private val toggleFromTag: ToggleBlockModeFromTagUseCase,
    private val nfcTagBus: NfcTagBus,
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
        observeStrictness(),
        observeUnlockedPackages(),
    ) { state, schedules, strictness, unlocks ->
        // Recomputed off the same one-second ticker that drives coreState, so the label rolls over
        // to the following occurrence the moment the current one fires.
        state.copy(
            nextScheduledFire = schedules.earliestNextFire(ZonedDateTime.now(zone)),
            strictness = strictness,
            unlockedPackages = unlocks,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events

    // Set around a resume so the tick below doesn't also announce the bypass as having run out.
    // Main-thread only, like the tick itself.
    private var resuming = false

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
                if (bypassWasActive && !bypassActive && !resuming) _events.tryEmit(HomeEvent.BypassEnded)
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
            // Silent on refusal: the only way to reach this with a strictness that forbids it is
            // a level change racing the dialog, and there is nothing useful to tell the user
            // about a button that should already have been gone.
            if (startBypass().isSuccess) _events.tryEmit(HomeEvent.BypassStarted)
        }
    }

    /** Ends a bypass or app unlock early. Only ever stricter, so no confirmation stands in front. */
    fun resumeBlocking() {
        viewModelScope.launch {
            resuming = true
            try {
                if (resumeBlocking.invoke()) _events.tryEmit(HomeEvent.Resumed)
                // The once-a-second tick notices the bypass is gone within the next second; hold
                // the flag past that so it reads the change as ours rather than as a run-out.
                delay(1500)
            } finally {
                resuming = false
            }
        }
    }

    /** Turns Monolith on without a tag. There is no matching "deactivate" -- that stays tag-only. */
    fun activate() {
        viewModelScope.launch {
            if (activateBlockMode()) _events.tryEmit(HomeEvent.Toggled(nowActive = true))
        }
    }
}
