package com.monolith.app.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import com.monolith.app.service.AppBlockAccessibilityService
import com.monolith.app.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class OnboardingUiState(
    val usageAccessGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
) {
    val allGranted: Boolean
        get() = usageAccessGranted && overlayGranted && accessibilityGranted && notificationAccessGranted

    val anyGranted: Boolean
        get() = usageAccessGranted || overlayGranted || accessibilityGranted || notificationAccessGranted
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState

    fun refresh() {
        _uiState.value = OnboardingUiState(
            usageAccessGranted = PermissionUtils.hasUsageAccess(context),
            overlayGranted = PermissionUtils.hasOverlayPermission(context),
            accessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(
                context,
                AppBlockAccessibilityService::class.java,
            ),
            notificationAccessGranted = PermissionUtils.hasNotificationAccess(context),
        )
    }
}
