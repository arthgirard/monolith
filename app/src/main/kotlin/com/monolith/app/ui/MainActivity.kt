package com.monolith.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.monolith.app.nfc.NfcManager
import com.monolith.app.nfc.NfcTagBus
import com.monolith.app.ui.navigation.MonolithDestination
import com.monolith.app.ui.navigation.MonolithNavHost
import com.monolith.app.ui.theme.MonolithTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var nfcManager: NfcManager
    @Inject lateinit var nfcTagBus: NfcTagBus

    private val viewModel: MainViewModel by viewModels()

    // Set by the home-screen widget's tap intent, consumed once the NavHost exists.
    private val openTimeSaved = mutableStateOf(false)
    // Set by notifications to ensure tapping a notification opens Monolith's home screen.
    private val openHome = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        setContent {
            MonolithTheme {
                // Null while the stored onboarding flag is being read: the NavHost can't change
                // its start destination later, so it waits rather than guessing at onboarding.
                val startDestination by viewModel.startRoute.collectAsState()
                val route = startDestination ?: return@MonolithTheme

                val navController = rememberNavController()
                MonolithNavHost(
                    navController = navController,
                    startDestination = route,
                    onOnboardingCompleted = viewModel::markOnboardingCompleted,
                )

                RecheckPermissionsOnResume(navController)

                val shouldOpenTimeSaved by openTimeSaved
                LaunchedEffect(shouldOpenTimeSaved) {
                    if (!shouldOpenTimeSaved) return@LaunchedEffect
                    openTimeSaved.value = false
                    // Onboarding owns the back stack until permissions are granted; the widget's
                    // stats aren't worth dropping someone into the middle of that.
                    if (route == MonolithDestination.Home.route) {
                        navController.navigate(MonolithDestination.TimeSaved.route)
                    }
                }

                val shouldOpenHome by openHome
                LaunchedEffect(shouldOpenHome) {
                    if (!shouldOpenHome) return@LaunchedEffect
                    openHome.value = false
                    if (route == MonolithDestination.Home.route) {
                        val popped = navController.popBackStack(MonolithDestination.Home.route, inclusive = false)
                        if (!popped && navController.currentDestination?.route != MonolithDestination.Home.route) {
                            navController.navigate(MonolithDestination.Home.route) {
                                popUpTo(MonolithDestination.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Android can revoke a permission while Monolith sits in the background -- an accessibility
     * service switched off, notification access pulled. Someone who already finished setup gets
     * the permission step back on their next resume, not the whole tour.
     */
    @Composable
    private fun RecheckPermissionsOnResume(navController: NavHostController) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, navController) {
            val observer = LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
                if (!viewModel.hasCompletedOnboarding()) return@LifecycleEventObserver
                if (viewModel.permissionsGranted()) return@LifecycleEventObserver
                val current = navController.currentDestination?.route
                if (current in SETUP_ROUTES) return@LifecycleEventObserver
                navController.navigate(MonolithDestination.Permissions.route) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcManager.enableForegroundDispatch(this)
    }

    override fun onPause() {
        nfcManager.disableForegroundDispatch(this)
        super.onPause()
    }

    private fun handleIntent(intent: Intent) {
        nfcManager.extractTagFromIntent(intent)?.let { nfcTagBus.emit(it) }
        if (intent.action == ACTION_OPEN_TIME_SAVED || intent.getBooleanExtra(EXTRA_OPEN_TIME_SAVED, false)) {
            openTimeSaved.value = true
            openHome.value = false
            intent.removeExtra(EXTRA_OPEN_TIME_SAVED)
        } else if (intent.action == ACTION_OPEN_HOME || intent.getBooleanExtra(EXTRA_OPEN_HOME, false)) {
            openHome.value = true
            openTimeSaved.value = false
            intent.removeExtra(EXTRA_OPEN_HOME)
        }
    }

    companion object {
        const val ACTION_OPEN_HOME = "com.monolith.app.action.OPEN_HOME"
        const val ACTION_OPEN_TIME_SAVED = "com.monolith.app.action.OPEN_TIME_SAVED"
        const val EXTRA_OPEN_TIME_SAVED = "com.monolith.app.extra.OPEN_TIME_SAVED"
        const val EXTRA_OPEN_HOME = "com.monolith.app.extra.OPEN_HOME"

        /** Screens that already handle missing permissions themselves. */
        private val SETUP_ROUTES = setOf(
            MonolithDestination.Onboarding.route,
            MonolithDestination.OnboardingAppSelector.route,
            MonolithDestination.OnboardingNfcLink.route,
            MonolithDestination.OnboardingComplete.route,
            MonolithDestination.Permissions.route,
        )
    }
}
