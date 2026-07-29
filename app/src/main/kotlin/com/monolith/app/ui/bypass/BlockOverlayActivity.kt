package com.monolith.app.ui.bypass

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.nfc.NfcManager
import com.monolith.app.nfc.NfcTagBus
import com.monolith.app.ui.theme.MonolithTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BlockOverlayActivity : ComponentActivity() {

    @Inject lateinit var nfcManager: NfcManager
    @Inject lateinit var nfcTagBus: NfcTagBus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            MonolithTheme(darkTheme = true) {
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
    }

    override fun onPause() {
        nfcManager.disableForegroundDispatch(this)
        super.onPause()
    }

    private fun handleIntent(intent: Intent) {
        nfcManager.extractTagFromIntent(intent)?.let { nfcTagBus.emit(it) }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
    }
}

@Composable
private fun BlockOverlayScreen(
    onGoHome: () -> Unit,
    onUnlocked: () -> Unit,
    viewModel: BlockOverlayViewModel = hiltViewModel(),
) {
    val blockState by viewModel.blockState.collectAsState()

    LaunchedEffect(blockState) {
        if (!blockState.isEnforcing(System.currentTimeMillis())) onUnlocked()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.height(64.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.overlay_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.overlay_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = onGoHome) {
                Text(stringResource(R.string.overlay_go_home))
            }
        }
    }
}
