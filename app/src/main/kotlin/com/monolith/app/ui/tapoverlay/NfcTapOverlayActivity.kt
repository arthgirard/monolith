package com.monolith.app.ui.tapoverlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.monolith.app.BuildConfig
import com.monolith.app.R
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.nfc.NfcManager
import com.monolith.app.util.AppLocale
import com.monolith.app.util.formatDuration
import com.monolith.app.ui.components.MIN_REPORTABLE_STREAK_MILLIS
import com.monolith.app.ui.components.MonolithFlash
import com.monolith.app.ui.theme.MonolithMotion
import com.monolith.app.ui.theme.MonolithTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Landing spot for a background NFC tap: the OS Tag Dispatch System launches this directly
 * (see the manifest's monolith://tag intent-filter) instead of routing through MainActivity, so
 * a tap flashes its result over whatever app the user was already in and hands control straight
 * back, it never becomes a full app switch.
 */
@AndroidEntryPoint
class NfcTapOverlayActivity : ComponentActivity() {

    @Inject lateinit var nfcManager: NfcManager

    private val viewModel: NfcTapOverlayViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Same reasoning as BlockOverlayActivity, including the debug carve-out: this flashes
        // Monolith's lock state over whatever app was open, so in release it stays out of
        // screenshots, recordings and Recents.
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

        setContent {
            MonolithTheme {
                NfcTapOverlayScreen(onDone = { finish() })
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
        nfcManager.extractTagFromIntent(intent)?.let { viewModel.handleTag(it) }
    }
}

@Composable
private fun NfcTapOverlayScreen(
    onDone: () -> Unit,
    viewModel: NfcTapOverlayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Nothing resolves a Pending state except a tag actually being read, and the effect below
    // only dismisses on Done. An intent that carries no readable tag -- a failed read, or this
    // exported Activity being started by anything else -- would otherwise leave an empty slab
    // parked over whatever the user was doing, with no way out but Back.
    LaunchedEffect(Unit) {
        delay(PENDING_TIMEOUT_MILLIS)
        if (viewModel.uiState.value is TapOverlayUiState.Pending) onDone()
    }

    LaunchedEffect(uiState) {
        val state = uiState
        if (state !is TapOverlayUiState.Done) return@LaunchedEffect
        // An unrecognised tag gets no screen and no dwell. Monolith is not the only thing the
        // phone taps against, and announcing "this isn't mine" over whatever the user was
        // actually doing is noise Monolith has no business making.
        if (state.result is NfcTapResult.UnknownTag) {
            onDone()
            return@LaunchedEffect
        }
        delay(DISMISS_DELAY_MILLIS)
        onDone()
    }

    val revealed = uiState is TapOverlayUiState.Done
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(revealed) {
        if (!revealed) return@LaunchedEffect
        val state = uiState
        // Compose 1.6 offers only these two. A real toggle gets the solid one; an
        // unrecognised tag and a tag that was never linked are both "nothing happened" and get
        // the light tick, so they don't feel like the tap that locks the phone down.
        haptics.performHapticFeedback(
            if (state is TapOverlayUiState.Done && state.result is NfcTapResult.Toggled) {
                HapticFeedbackType.LongPress
            } else {
                HapticFeedbackType.TextHandleMove
            },
        )
    }

    val done = uiState as? TapOverlayUiState.Done
    val copy = done?.result?.flashCopy()

    // Nothing is drawn until there is something to say, and nothing is ever drawn for a tag that
    // isn't Monolith's. The window itself is transparent (Theme.Monolith.Overlay.Transparent),
    // so an unrecognised tap is invisible rather than a dark flash over the foreground app.
    if (copy == null) return

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        MonolithFlash(
            icon = copy.icon,
            headline = stringResource(copy.headline),
            supporting = stringResource(copy.body),
            // Only a tap that ends a streak has a figure to report.
            statCaption = stringResource(R.string.overlay_stat_held)
                .takeIf { (done?.bankedStreakMillis ?: 0L) >= MIN_REPORTABLE_STREAK_MILLIS },
            statValue = done?.bankedStreakMillis
                ?.takeIf { it >= MIN_REPORTABLE_STREAK_MILLIS }
                ?.let { formatDuration(it) },
        )
    }
}

/** Glyph and copy for one tap outcome. */
private data class FlashCopy(val icon: ImageVector, val headline: Int, val body: Int)

private fun NfcTapResult.flashCopy(): FlashCopy? = when (this) {
    is NfcTapResult.Toggled -> if (nowActive) {
        FlashCopy(
            Icons.Filled.Lock,
            R.string.tap_overlay_locked,
            R.string.tap_overlay_locked_body,
        )
    } else {
        FlashCopy(
            Icons.Filled.LockOpen,
            R.string.tap_overlay_unlocked,
            R.string.tap_overlay_unlocked_body,
        )
    }
    // No copy: an unrecognised tag is dismissed without ever being drawn.
    NfcTapResult.UnknownTag -> null
    NfcTapResult.NoTagLinked -> FlashCopy(
        Icons.Filled.LinkOff,
        R.string.tap_overlay_no_tag_linked,
        R.string.tap_overlay_no_tag_linked_body,
    )
}

private val DISMISS_DELAY_MILLIS = MonolithMotion.FlashHoldMillis

/** How long a tag read gets before the overlay gives up and hands the screen back. */
private const val PENDING_TIMEOUT_MILLIS = 4000L
