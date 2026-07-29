package com.monolith.app.ui.bypass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.ToggleBlockModeFromTagUseCase
import com.monolith.app.nfc.NfcTagBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BlockOverlayViewModel @Inject constructor(
    observeBlockState: ObserveBlockStateUseCase,
    private val toggleFromTag: ToggleBlockModeFromTagUseCase,
    private val nfcTagBus: NfcTagBus,
) : ViewModel() {

    // Seeded isActive=true: this activity is only ever launched by AppBlockAccessibilityService
    // after it already confirmed isEnforcing()==true. DataStore's first real read is async, so a
    // BlockState() (isActive=false) placeholder here would read as "not enforcing" on the first
    // frame and instantly self-finish the overlay before the real value loads — letting the
    // blocked app win the race every time.
    val blockState: StateFlow<BlockState> =
        observeBlockState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BlockState(isActive = true))

    init {
        // A registered tag tapped while the overlay is up unlocks immediately, same as anywhere else.
        nfcTagBus.tagEvents.onEach { tag ->
            toggleFromTag(tag)
        }.launchIn(viewModelScope)
    }
}
