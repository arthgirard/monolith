package com.monolith.app.ui.appselector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.AppInfo
import com.monolith.app.domain.usecase.GetEssentialPackagesUseCase
import com.monolith.app.domain.usecase.GetInstalledAppsUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.ObserveBlockedPackagesUseCase
import com.monolith.app.domain.usecase.SaveBlockedAppsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppSelectorUiState(
    val allApps: List<AppInfo> = emptyList(),
    val blockedPackages: Set<String> = emptySet(),
    val query: String = "",
    val isLocked: Boolean = false,
    val isLoading: Boolean = true,
) {
    val filteredApps: List<AppInfo>
        get() = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }

    val allSelected: Boolean
        get() = allApps.isNotEmpty() && blockedPackages.containsAll(allApps.map { it.packageName })
}

@HiltViewModel
class AppSelectorViewModel @Inject constructor(
    private val getInstalledApps: GetInstalledAppsUseCase,
    private val saveBlockedApps: SaveBlockedAppsUseCase,
    private val getEssentialPackages: GetEssentialPackagesUseCase,
    observeBlockedPackages: ObserveBlockedPackagesUseCase,
    observeBlockState: ObserveBlockStateUseCase,
) : ViewModel() {

    private val allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val query = MutableStateFlow("")
    private val isLoading = MutableStateFlow(true)

    val uiState: StateFlow<AppSelectorUiState> = combine(
        allApps,
        observeBlockedPackages(),
        observeBlockState(),
        query,
        isLoading,
    ) { apps, blocked, blockState, q, loading ->
        AppSelectorUiState(
            allApps = apps,
            blockedPackages = blocked,
            query = q,
            isLocked = blockState.isActive,
            isLoading = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSelectorUiState())

    init {
        viewModelScope.launch {
            allApps.value = getInstalledApps()
            isLoading.value = false
        }
    }

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    fun toggleApp(packageName: String) {
        val state = uiState.value
        if (state.isLocked) return

        val newSelection = if (packageName in state.blockedPackages) {
            state.blockedPackages - packageName
        } else {
            state.blockedPackages + packageName
        }
        viewModelScope.launch { saveBlockedApps(newSelection) }
    }

    /** Toggles between blocking every app and clearing the selection entirely. */
    fun toggleSelectAll() {
        val state = uiState.value
        if (state.isLocked) return

        val allPackages = state.allApps.map { it.packageName }.toSet()
        val newSelection = if (state.blockedPackages.containsAll(allPackages)) emptySet() else allPackages
        viewModelScope.launch { saveBlockedApps(newSelection) }
    }

    /** Blocks every installed app except the device's default SMS, dialer, and clock apps. */
    fun applyDumbPhoneMode() {
        val state = uiState.value
        if (state.isLocked) return

        viewModelScope.launch {
            val essential = getEssentialPackages()
            val blocked = state.allApps.map { it.packageName }.toSet() - essential
            saveBlockedApps(blocked)
        }
    }
}
