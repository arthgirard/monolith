package com.monolith.app.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.AppUpdate
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.service.AppBlockAccessibilityService
import com.monolith.app.ui.navigation.MonolithDestination
import com.monolith.app.ui.update.UpdateFlow
import com.monolith.app.ui.update.UpdateUiState
import com.monolith.app.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: MonolithPreferences,
    private val updateFlow: UpdateFlow,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    /** Null until the stored onboarding flag has been read; the NavHost waits for it. */
    private val _startRoute = MutableStateFlow<String?>(null)
    val startRoute: StateFlow<String?> = _startRoute

    private var onboardingCompleted = false

    init {
        viewModelScope.launch {
            onboardingCompleted = preferences.onboardingCompleted.first()
            _startRoute.value = when {
                permissionsGranted() -> MonolithDestination.Home.route
                // Setup was walked through once already; Android just took a permission back.
                // Ask for that permission alone instead of replaying app picking and tag linking.
                onboardingCompleted -> MonolithDestination.Permissions.route
                else -> MonolithDestination.Onboarding.route
            }
            // The first resume usually lands before the flag is read, and skips the check.
            checkForUpdateOnOpen()
        }
    }

    fun permissionsGranted(): Boolean =
        PermissionUtils.allPermissionsGranted(context, AppBlockAccessibilityService::class.java)

    /** True once setup has been completed, so a later permission loss can skip straight to it. */
    fun hasCompletedOnboarding(): Boolean = onboardingCompleted

    fun markOnboardingCompleted() {
        onboardingCompleted = true
        viewModelScope.launch {
            preferences.setOnboardingCompleted()
            // A new install backs up by default. Turned on here rather than at the tag step, so
            // the first upload carries the apps and schedule just set up, not an empty setup that
            // the three-hour throttle would then leave standing.
            backupRepository.enableByDefault()
        }
    }

    val updateState: StateFlow<UpdateUiState> = updateFlow.state

    private var lastUpdateCheckAt: Long? = null

    /**
     * Runs on every resume but asks GitHub at most every few hours, and never during setup: a
     * popup over onboarding would only be in the way. A release told "Later" stays quiet until a
     * newer one ships.
     */
    fun checkForUpdateOnOpen() {
        if (!onboardingCompleted || !permissionsGranted()) return
        val now = SystemClock.elapsedRealtime()
        lastUpdateCheckAt?.let { if (now - it < UPDATE_CHECK_INTERVAL_MILLIS) return }
        lastUpdateCheckAt = now
        updateFlow.checkQuietly(viewModelScope) { update ->
            preferences.dismissedUpdateVersion.first() != update.versionName
        }
    }

    fun startUpdateDownload(update: AppUpdate) = updateFlow.startDownload(viewModelScope, update)

    fun onUpdateResume() = updateFlow.onResume(viewModelScope)

    fun dismissUpdate() = updateFlow.dismiss()

    fun postponeUpdate() {
        val state = updateFlow.state.value
        if (state is UpdateUiState.Available) {
            viewModelScope.launch { preferences.setDismissedUpdateVersion(state.update.versionName) }
        }
        updateFlow.dismiss()
    }

    private companion object {
        const val UPDATE_CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
    }
}
