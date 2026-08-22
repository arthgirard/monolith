package com.monolith.app.ui.bypass

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.monolith.app.BuildConfig
import com.monolith.app.nfc.NfcManager
import com.monolith.app.nfc.NfcTagBus
import com.monolith.app.service.BlockOverlayGuard
import com.monolith.app.ui.theme.MonolithTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BlockOverlayActivity : ComponentActivity() {

    @Inject lateinit var nfcManager: NfcManager
    @Inject lateinit var nfcTagBus: NfcTagBus
    @Inject lateinit var overlayGuard: BlockOverlayGuard

    // Same instance Compose's hiltViewModel() resolves to below, since both are scoped to this
    // Activity's ViewModelStore. Held here so handleIntent() can push package updates into it.
    private val viewModel: BlockOverlayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The waiver step shows text the user is meant to type, not lift with a screenshot or
        // Circle to Search. FLAG_SECURE is the actual OS-level block for that -- it blanks
        // screenshots and screen recording, blacks out this window's Recents thumbnail, and
        // denies any screen-capture-based text selection over it. There's no reliable way to
        // "detect" a screenshot attempt and react after the fact; this prevents the capture itself.
        //
        // Debug builds skip FLAG_SECURE so the overlay can be screenshotted with
        // `adb exec-out screencap` during design work -- the flag blanks captures, which is the
        // whole point of it and also makes these two surfaces the only ones nobody can review.
        // Release keeps it: that is the build that ships, and the protection below is real.
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        // True full screen, the modern way. android:windowFullscreen in the theme is deprecated
        // and silently ignored from API 30 on: the window came back 1080x2251 on a 1080x2424
        // screen, so the slab stopped 173px short of the top and left a hard black status-bar
        // strip above it. Hiding the bars outright is the intent -- a wall you can still read the
        // clock through is less of a wall -- and swipe still brings them back transiently.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        handleIntent(intent)

        // Blocked by design: back must not dismiss the overlay. Route to the home launcher instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            MonolithTheme {
                BlockOverlayScreen(
                    onGoHome = { goHome() },
                    onUnlocked = { finish() },
                )
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
        // This Activity's own window has taken over the screen by now, so the temporary
        // opaque cover the accessibility service painted while this was launching can go.
        overlayGuard.hide()
    }

    override fun onPause() {
        nfcManager.disableForegroundDispatch(this)
        // Leaving the overlay (screen off, app switch, ...) discards progress: the waiver has to
        // be typed in one sitting, and an unsolved puzzle is abandoned outright so the next
        // attempt faces a new code rather than the same one worn down across re-openings.
        viewModel.onOverlayLeft()
        super.onPause()
    }

    private fun handleIntent(intent: Intent) {
        nfcManager.extractTagFromIntent(intent)?.let { nfcTagBus.emit(it) }
        intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)?.let { viewModel.setBlockedPackage(it) }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
        // This instance isn't needed once dismissed: enforcement lives in the accessibility
        // service, not here, and it'll be relaunched fresh next time a blocked app resurfaces.
        // Without finishing, this Activity (and its ViewModel, NFC dispatch registration, and
        // Compose composition) would linger indefinitely in the background on every "Go home"
        // tap, since it's launched from a Service via FLAG_ACTIVITY_NEW_TASK and can't reliably
        // count on task-affinity reuse to clean up a prior un-finished instance.
        finish()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
    }
}
