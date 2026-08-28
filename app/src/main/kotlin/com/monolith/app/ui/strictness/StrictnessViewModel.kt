package com.monolith.app.ui.strictness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.ObserveStrictnessUseCase
import com.monolith.app.domain.usecase.SaveStrictnessLevelUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StrictnessUiState(
    val level: StrictnessLevel = StrictnessLevel.DEFAULT,
    /** True while Monolith is active, when the choice is the tag's to change and not the screen's. */
    val isLocked: Boolean = false,
)

@HiltViewModel
class StrictnessViewModel @Inject constructor(
    observeStrictness: ObserveStrictnessUseCase,
    observeBlockState: ObserveBlockStateUseCase,
    private val saveStrictnessLevel: SaveStrictnessLevelUseCase,
) : ViewModel() {

    val uiState: StateFlow<StrictnessUiState> = combine(
        observeStrictness(),
        observeBlockState(),
    ) { level, blockState ->
        StrictnessUiState(level = level, isLocked = blockState.isActive)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StrictnessUiState())

    /**
     * Writes on selection rather than behind a save button, the same way the app selector commits
     * each toggle. The refusal path is silent: the rows are already disabled while Monolith is
     * active, so a failure here means a tag was tapped mid-selection, and the state the screen
     * re-renders from is the truthful answer to that.
     */
    fun select(level: StrictnessLevel) {
        viewModelScope.launch { saveStrictnessLevel(level) }
    }
}
