package com.monolith.app.ui.bypass

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.domain.usecase.GetCodeBreakerChallengeUseCase
import com.monolith.app.domain.usecase.GetSolvedCodeBreakerUseCase
import com.monolith.app.domain.usecase.GetWaiverSentenceUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.SubmitCodeBreakerGuessUseCase
import com.monolith.app.domain.usecase.SubmitWaiverUseCase
import com.monolith.app.domain.usecase.ToggleBlockModeFromTagUseCase
import com.monolith.app.nfc.NfcTagBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UnlockChallengeUiState(
    val codeBreaker: CodeBreaker? = null,
    val isLoading: Boolean = false,
    // True for exactly as long as the board on screen is a replacement for one that just ran out
    // of tries -- cleared by the next guess, so the notice doesn't outlive the moment it explains.
    val codeRestarted: Boolean = false,
    // Set once codeBreaker.isSolved -- the sentence this attempt's solver must copy exactly.
    val waiverTarget: String? = null,
    val waiverInput: String = "",
    val unlocked: Boolean = false,
    // Set alongside unlocked -- which package to explicitly launch now that it's exempted.
    val unlockedPackage: String? = null,
    // A tag tap mid-challenge turns Monolith off entirely (see handleTagTap): true briefly while
    // that "Monolith disabled" feedback is shown, before the blocked app is launched.
    val tagDisabled: Boolean = false,
) {
    val waiverMatches: Boolean get() = waiverTarget != null && waiverInput == waiverTarget
}

@HiltViewModel
class BlockOverlayViewModel @Inject constructor(
    observeBlockState: ObserveBlockStateUseCase,
    private val toggleFromTag: ToggleBlockModeFromTagUseCase,
    private val nfcTagBus: NfcTagBus,
    private val getCodeBreakerChallenge: GetCodeBreakerChallengeUseCase,
    private val getSolvedCodeBreaker: GetSolvedCodeBreakerUseCase,
    private val submitCodeBreakerGuess: SubmitCodeBreakerGuessUseCase,
    private val getWaiverSentence: GetWaiverSentenceUseCase,
    private val submitWaiver: SubmitWaiverUseCase,
) : ViewModel() {

    // Pushed by the Activity from both onCreate and onNewIntent: the accessibility service can
    // relaunch this Activity with CLEAR_TOP into an already-alive instance (e.g. the user swipes
    // straight from one blocked app to another without dismissing the overlay first), so a value
    // captured once at construction would go stale and target the wrong app's unlock.
    private var blockedPackage: String? = null

    // Seeded isActive=true: this activity is only ever launched by AppBlockAccessibilityService
    // after it already confirmed isEnforcing()==true. DataStore's first real read is async, so a
    // BlockState() (isActive=false) placeholder here would read as "not enforcing" on the first
    // frame and instantly self-finish the overlay before the real value loads, letting the
    // blocked app win the race every time.
    val blockState: StateFlow<BlockState> =
        observeBlockState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BlockState(isActive = true))

    private val _challengeState = MutableStateFlow(UnlockChallengeUiState())
    val challengeState: StateFlow<UnlockChallengeUiState> = _challengeState

    init {
        nfcTagBus.tagEvents.onEach { tag -> handleTagTap(tag) }.launchIn(viewModelScope)
    }

    // A registered tag tapped while the overlay is up always turns Monolith fully off (it's only
    // ever active in here), same as anywhere else -- skip whatever's left of this app's own
    // puzzle/waiver and go straight to "Monolith disabled" feedback, then the app itself.
    private suspend fun handleTagTap(tag: Tag) {
        val result = toggleFromTag(tag)
        if (result is NfcTapResult.Toggled && !result.nowActive) {
            _challengeState.value = _challengeState.value.copy(
                tagDisabled = true,
                unlockedPackage = blockedPackage,
            )
        }
    }

    fun setBlockedPackage(packageName: String) {
        if (blockedPackage == packageName) return
        blockedPackage = packageName
        _challengeState.value = UnlockChallengeUiState()
        // An unsolved puzzle is gone for good by now (see GetCodeBreakerChallengeUseCase), so the
        // only thing worth restoring is a puzzle already beaten whose waiver was never confirmed:
        // that reopens straight onto the waiver instead of the idle screen.
        viewModelScope.launch {
            val pending = getSolvedCodeBreaker(packageName) ?: return@launch
            // The service can retarget this same overlay at another app while this read is in
            // flight -- dropping the result then keeps one app's waiver off another app's screen.
            if (blockedPackage != packageName) return@launch
            applyCodeBreaker(packageName, pending)
        }
    }

    fun startChallenge() {
        val packageName = blockedPackage ?: return
        if (_challengeState.value.codeBreaker != null || _challengeState.value.isLoading) return
        _challengeState.value = _challengeState.value.copy(isLoading = true)
        viewModelScope.launch {
            val codeBreaker = getCodeBreakerChallenge(packageName)
            applyCodeBreaker(packageName, codeBreaker)
        }
    }

    fun submitGuess(values: List<Int>) {
        val packageName = blockedPackage ?: return
        val current = _challengeState.value.codeBreaker ?: return
        if (current.isSolved) return
        viewModelScope.launch {
            val result = submitCodeBreakerGuess(packageName, current, values)
            applyCodeBreaker(packageName, result.codeBreaker, restarted = result.restarted)
        }
    }

    fun updateWaiverInput(text: String) {
        _challengeState.value = _challengeState.value.copy(waiverInput = text)
    }

    /**
     * Called when the overlay stops being the foreground window (screen off, app switch, home).
     * Whatever was typed into the waiver is dropped, and an unsolved puzzle is abandoned back to
     * the idle block screen -- the next attempt starts over on a freshly generated code. A solved
     * puzzle stays put, so returning lands back on the waiver with an empty field.
     */
    fun onOverlayLeft() {
        val state = _challengeState.value
        val abandonPuzzle = state.codeBreaker?.isSolved == false
        _challengeState.value = if (abandonPuzzle) {
            UnlockChallengeUiState()
        } else {
            state.copy(waiverInput = "")
        }
    }

    fun confirmWaiver() {
        val packageName = blockedPackage ?: return
        val state = _challengeState.value
        if (!state.waiverMatches) return
        viewModelScope.launch {
            submitWaiver(packageName, APP_UNLOCK_DURATION_MILLIS)
            _challengeState.value = state.copy(unlocked = true, unlockedPackage = packageName)
        }
    }

    // Also covers the case where the overlay was dismissed after solving but before the waiver
    // was confirmed: the persisted code-breaker comes back already solved, so its waiver sentence
    // needs fetching immediately rather than waiting on a guess that will never come.
    private suspend fun applyCodeBreaker(packageName: String, codeBreaker: CodeBreaker, restarted: Boolean = false) {
        val waiverTarget = if (codeBreaker.isSolved) getWaiverSentence(packageName) else null
        _challengeState.value = _challengeState.value.copy(
            codeBreaker = codeBreaker,
            isLoading = false,
            waiverTarget = waiverTarget,
            codeRestarted = restarted,
        )
    }

    companion object {
        const val APP_UNLOCK_DURATION_MILLIS: Long = 5 * 60 * 1000L
    }
}
