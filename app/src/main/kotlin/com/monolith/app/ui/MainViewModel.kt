package com.monolith.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.service.AppBlockAccessibilityService
import com.monolith.app.ui.navigation.MonolithDestination
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
        }
    }

    fun permissionsGranted(): Boolean =
        PermissionUtils.allPermissionsGranted(context, AppBlockAccessibilityService::class.java)

    /** True once setup has been completed, so a later permission loss can skip straight to it. */
    fun hasCompletedOnboarding(): Boolean = onboardingCompleted

    fun markOnboardingCompleted() {
        onboardingCompleted = true
        viewModelScope.launch { preferences.setOnboardingCompleted() }
    }
}
