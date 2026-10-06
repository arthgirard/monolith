package com.monolith.app.ui.guard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.monolith.app.BuildConfig
import com.monolith.app.R
import com.monolith.app.service.BlockOverlayGuard
import com.monolith.app.ui.components.MonolithFlash
import com.monolith.app.ui.theme.MonolithMotion
import com.monolith.app.ui.theme.MonolithTheme
import com.monolith.app.util.AppLocale
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Said over a screen the uninstall guard caught, then handed to the home screen. Going home is
 * done from here rather than by the accessibility service, for the race BlockOverlayActivity
 * documents: GLOBAL_ACTION_HOME lands asynchronously and could bury this flash behind the
 * launcher before it was ever read.
 *
 * Back leaves the same way. Returning to the page underneath would only be caught again.
 */
@AndroidEntryPoint
class UninstallGuardActivity : ComponentActivity() {

    @Inject lateinit var overlayGuard: BlockOverlayGuard

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Same reasoning as the other overlays, debug carve-out included.
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            MonolithTheme {
                LaunchedEffect(Unit) {
                    delay(MonolithMotion.FlashHoldMillis)
                    goHome()
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MonolithFlash(
                        icon = Icons.Filled.Lock,
                        headline = stringResource(R.string.uninstall_guard_headline),
                        supporting = stringResource(R.string.uninstall_guard_body),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The cover the service painted while this was starting has been taken over by now.
        overlayGuard.hide()
    }

    private fun goHome() {
        if (isFinishing) return
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        finish()
    }
}
