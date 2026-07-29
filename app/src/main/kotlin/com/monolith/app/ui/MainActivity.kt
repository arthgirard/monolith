package com.monolith.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    }
}
