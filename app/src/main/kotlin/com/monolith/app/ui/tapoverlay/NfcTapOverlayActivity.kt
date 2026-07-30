package com.monolith.app.ui.tapoverlay

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.nfc.NfcManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        handleIntent(intent)

        setContent {
            MonolithTheme(darkTheme = true) {
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

    LaunchedEffect(uiState) {
        if (uiState is TapOverlayUiState.Done) {
            delay(DISMISS_DELAY_MILLIS)
            onDone()
        }
    }

    val revealed = uiState is TapOverlayUiState.Done
    val scale by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.6f,
        animationSpec = tween(durationMillis = 260),
        label = "tap_overlay_scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "tap_overlay_alpha",
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().alpha(alpha).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val (icon, label) = uiState.iconAndLabel()
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.height(72.dp).scale(scale),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(label),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun TapOverlayUiState.iconAndLabel(): Pair<ImageVector, Int> = when (this) {
    is TapOverlayUiState.Pending -> Icons.Filled.Lock to R.string.tap_overlay_locked
    is TapOverlayUiState.Done -> when (result) {
        is NfcTapResult.Toggled -> if (result.nowActive) {
            Icons.Filled.Lock to R.string.tap_overlay_locked
        } else {
            Icons.Filled.LockOpen to R.string.tap_overlay_unlocked
        }
        NfcTapResult.UnknownTag -> Icons.Filled.ErrorOutline to R.string.tap_overlay_unknown_tag
        NfcTapResult.NoTagLinked -> Icons.Filled.LinkOff to R.string.tap_overlay_no_tag_linked
    }
}

private const val DISMISS_DELAY_MILLIS = 1200L
