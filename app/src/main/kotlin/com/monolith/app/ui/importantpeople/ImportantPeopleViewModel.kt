package com.monolith.app.ui.importantpeople

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.AppInfo
import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.usecase.AddImportantPersonUseCase
import com.monolith.app.domain.usecase.GetInstalledAppsUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.ObserveBlockedPackagesUseCase
import com.monolith.app.domain.usecase.ObserveImportantPeopleUseCase
import com.monolith.app.domain.usecase.RemoveImportantPersonUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportantPeopleUiState(
    val people: List<ImportantPerson> = emptyList(),
    val installedApps: List<AppInfo> = emptyList(),
    val blockedPackages: Set<String> = emptySet(),
    val isLocked: Boolean = false,
    val isLoading: Boolean = true,
) {
    /** Only apps currently blocked make sense to add someone against. */
    val blockableApps: List<AppInfo>
        get() = installedApps.filter { it.packageName in blockedPackages }
}

@HiltViewModel
class ImportantPeopleViewModel @Inject constructor(
    observeImportantPeople: ObserveImportantPeopleUseCase,
    observeBlockState: ObserveBlockStateUseCase,
    observeBlockedPackages: ObserveBlockedPackagesUseCase,
    private val getInstalledApps: GetInstalledAppsUseCase,
    private val addImportantPerson: AddImportantPersonUseCase,
    private val removeImportantPerson: RemoveImportantPersonUseCase,
) : ViewModel() {

    private val installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val isLoading = MutableStateFlow(true)

    val uiState: StateFlow<ImportantPeopleUiState> = combine(
        observeImportantPeople(),
        observeBlockState(),
        observeBlockedPackages(),
        installedApps,
        isLoading,
    ) { people, blockState, blockedPackages, apps, loading ->
        ImportantPeopleUiState(
            people = people,
            installedApps = apps,
            blockedPackages = blockedPackages,
            isLocked = blockState.isActive,
            isLoading = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImportantPeopleUiState())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors

    init {
        viewModelScope.launch {
            installedApps.value = getInstalledApps()
            isLoading.value = false
        }
    }

    fun addPerson(packageName: String, name: String?, handle: String?) {
        viewModelScope.launch {
            val result = addImportantPerson(ImportantPerson(packageName, name?.trim(), handle?.trim()))
            result.exceptionOrNull()?.message?.let { _errors.tryEmit(it) }
        }
    }

    fun removePerson(person: ImportantPerson) {
        viewModelScope.launch {
            val result = removeImportantPerson(person)
            result.exceptionOrNull()?.message?.let { _errors.tryEmit(it) }
        }
    }
}
