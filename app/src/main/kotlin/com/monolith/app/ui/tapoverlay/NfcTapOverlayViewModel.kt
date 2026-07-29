package com.monolith.app.ui.tapoverlay

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.domain.usecase.ToggleBlockModeFromTagUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TapOverlayUiState {
    data object Pending : TapOverlayUiState
    data class Done(val result: NfcTapResult) : TapOverlayUiState
}

@HiltViewModel
class NfcTapOverlayViewModel @Inject constructor(
    private val toggleFromTag: ToggleBlockModeFromTagUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TapOverlayUiState>(TapOverlayUiState.Pending)
    val uiState: StateFlow<TapOverlayUiState> = _uiState

    fun handleTag(tag: Tag) {
        viewModelScope.launch {
            _uiState.value = TapOverlayUiState.Done(toggleFromTag(tag))
        }
    }
}
