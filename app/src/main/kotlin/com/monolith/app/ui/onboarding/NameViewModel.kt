package com.monolith.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.LeaderboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The name step of setup, and the greeting at its end. */
@HiltViewModel
class NameViewModel @Inject constructor(
    private val repository: LeaderboardRepository,
) : ViewModel() {

    /** Null until read, so the field doesn't start empty and then jump when going back to it. */
    val displayName: StateFlow<String?> = repository.observeDisplayName()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed

    fun save(name: String, onSaved: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _failed.value = false
        viewModelScope.launch {
            try {
                // Only phone-side during a first setup: there is no identity for a server to rename yet.
                when (repository.setDisplayName(name)) {
                    is LeaderboardResult.Ok -> onSaved()
                    is LeaderboardResult.Err -> _failed.value = true
                }
            } finally {
                _busy.value = false
            }
        }
    }
}
