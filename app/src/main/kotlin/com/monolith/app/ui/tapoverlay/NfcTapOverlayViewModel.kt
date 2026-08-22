package com.monolith.app.ui.tapoverlay

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.domain.usecase.GetCurrentStreakUseCase
import com.monolith.app.domain.usecase.ToggleBlockModeFromTagUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TapOverlayUiState {
    data object Pending : TapOverlayUiState

    /**
     * [bankedStreakMillis] is how long the streak the tap just ended had run, and is only set
     * when the tap turned Monolith off. Zero everywhere else.
     */
    data class Done(
        val result: NfcTapResult,
        val bankedStreakMillis: Long = 0L,
    ) : TapOverlayUiState
}

@HiltViewModel
class NfcTapOverlayViewModel @Inject constructor(
    private val toggleFromTag: ToggleBlockModeFromTagUseCase,
    private val getCurrentStreak: GetCurrentStreakUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TapOverlayUiState>(TapOverlayUiState.Pending)
    val uiState: StateFlow<TapOverlayUiState> = _uiState

    fun handleTag(tag: Tag) {
        viewModelScope.launch {
            // Read before the toggle, not after. Turning Monolith off commits the running
            // segment and drops SESSION_STARTED_AT, so by the time the result comes back the
            // streak this tap just ended reads as zero -- which is exactly the number the flash
            // is trying to report.
            val streakBeforeTap = getCurrentStreak.current()
            val result = toggleFromTag(tag)
            val banked = if (result is NfcTapResult.Toggled && !result.nowActive) {
                streakBeforeTap
            } else {
                0L
            }
            _uiState.value = TapOverlayUiState.Done(result, banked)
        }
    }
}
