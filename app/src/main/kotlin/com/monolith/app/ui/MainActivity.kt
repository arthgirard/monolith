package com.monolith.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.rememberNavController
import com.monolith.app.nfc.NfcManager
import com.monolith.app.nfc.NfcTagBus
import com.monolith.app.service.AppBlockAccessibilityService
import com.monolith.app.ui.navigation.MonolithDestination
import com.monolith.app.ui.navigation.MonolithNavHost
import com.monolith.app.ui.theme.MonolithTheme
import com.monolith.app.util.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var nfcManager: NfcManager
    @Inject lateinit var nfcTagBus: NfcTagBus

    // Set by the home-screen widget's tap intent, consumed once the NavHost exists.
    private val openTimeSaved = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        val startDestination = if (PermissionUtils.allPermissionsGranted(this, AppBlockAccessibilityService::class.java)) {
            MonolithDestination.Home.route
        } else {
            MonolithDestination.Onboarding.route
        }

        setContent {
            MonolithTheme {
                val navController = rememberNavController()
                MonolithNavHost(navController = navController, startDestination = startDestination)

                val shouldOpenTimeSaved by openTimeSaved
                LaunchedEffect(shouldOpenTimeSaved) {
                    if (!shouldOpenTimeSaved) return@LaunchedEffect
                    openTimeSaved.value = false
                    // Onboarding owns the back stack until permissions are granted; the widget's
                    // stats aren't worth dropping someone into the middle of that.
                    if (startDestination == MonolithDestination.Home.route) {
                        navController.navigate(MonolithDestination.TimeSaved.route)
                    }
                }
            }
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
        if (intent.getBooleanExtra(EXTRA_OPEN_TIME_SAVED, false)) {
            openTimeSaved.value = true
            intent.removeExtra(EXTRA_OPEN_TIME_SAVED)
        }
    }

    companion object {
        const val EXTRA_OPEN_TIME_SAVED = "com.monolith.app.extra.OPEN_TIME_SAVED"
    }
}
