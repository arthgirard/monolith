package com.monolith.app.ui.bypass

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.ui.theme.MonolithMotion
import com.monolith.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Routes between the four things the overlay can be -- the wall, the puzzle, the waiver, and a
 * terminal feedback flash -- and owns the moments where it hands the screen back.
 */
@Composable
fun BlockOverlayScreen(
    onGoHome: () -> Unit,
    onUnlocked: () -> Unit,
    viewModel: BlockOverlayViewModel = hiltViewModel(),
) {
    val blockState by viewModel.blockState.collectAsState()
    val challengeState by viewModel.challengeState.collectAsState()
    val wallState by viewModel.wallState.collectAsState()
    val context = LocalContext.current

    // Survives the Activity being retargeted at a second blocked app (singleInstance +
    // CLEAR_TOP reuses this composition). Replaying the slab's fall there would read as a fresh
    // interruption instead of the same wall changing what it names.
    var slabLanded by rememberSaveable { mutableStateOf(false) }

    // Drives the hand-back fade. The overlay leaves under its own power on an unlock rather than
    // being cut away, so the thing the user just worked for gets a moment of acknowledgement.
    val exitAlpha = remember { Animatable(1f) }

    LaunchedEffect(blockState, challengeState.tagDisabled) {
        // A tag tap already owns closing the overlay via the effect below (it needs to show
        // feedback first) -- this generic "enforcement stopped" path would otherwise race it and
        // close things immediately, skipping that feedback.
        if (challengeState.tagDisabled) return@LaunchedEffect
        if (!blockState.isEnforcing(System.currentTimeMillis())) onUnlocked()
    }
    // Solving unlocks only the one app that triggered this overlay: blockState above stays
    // "enforcing" throughout (every other blocked app is still blocked), so this is the only
    // signal that dismisses the overlay once the waiver is confirmed. Finishing this
    // singleInstance overlay (its own dedicated task) usually falls back to whichever task was
    // in front before it launched, but that's implicit -- explicitly launching the unlocked
    // app's own launcher intent guarantees Continue actually opens it, not just "whatever's
    // behind this."
    LaunchedEffect(challengeState.unlocked) {
        if (!challengeState.unlocked) return@LaunchedEffect
        delay(FEEDBACK_HOLD_MILLIS)
        // The app starts first and the fade runs over its launch, so the acknowledgement costs
        // nothing in perceived latency.
        launchApp(context, challengeState.unlockedPackage)
        exitAlpha.animateTo(0f, tween(MonolithMotion.SlabLiftMillis))
        onUnlocked()
    }
    // A tag tap mid-challenge disables Monolith outright: hold the "Monolith disabled" feedback
    // on screen briefly (matching NfcTapOverlayActivity's own tap feedback elsewhere), then open
    // the app the same way Continue does.
    LaunchedEffect(challengeState.tagDisabled) {
        if (!challengeState.tagDisabled) return@LaunchedEffect
        delay(FEEDBACK_HOLD_MILLIS)
        launchApp(context, challengeState.unlockedPackage)
        onUnlocked()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha.value },
        color = MaterialTheme.colorScheme.background,
    ) {
        val codeBreaker = challengeState.codeBreaker
        val waiverTarget = challengeState.waiverTarget
        when {
            challengeState.tagDisabled -> {
                // The flash owns its own slab and inset, so no padding here -- the plinth has to
                // run to the screen edges the way the wall's does.
                TagDisabledFeedback(
                    bankedStreakMillis = challengeState.bankedStreakMillis,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            challengeState.unlocked -> {
                UnlockGrantedFeedback(
                    appLabel = wallState.appLabel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            codeBreaker != null && waiverTarget != null -> {
                WaiverStep(
                    sentence = waiverTarget,
                    input = challengeState.waiverInput,
                    matches = challengeState.waiverMatches,
                    onInputChange = viewModel::updateWaiverInput,
                    onContinue = viewModel::confirmWaiver,
                    modifier = Modifier
                        .fillMaxSize()
                        // The wall's content is bottom-aligned inside its slab, so bleeding to
                        // the top edge is free. These two start at the top, and with the system
                        // bars hidden that top edge is now the physical one -- without this the
                        // title runs under the camera cutout.
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.xl),
                )
            }
            codeBreaker != null -> {
                CodeBreakerBoard(
                    codeBreaker = codeBreaker,
                    codeRestarted = challengeState.codeRestarted,
                    onSubmitGuess = viewModel::submitGuess,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
            }
            else -> {
                BlockWall(
                    wallState = wallState,
                    isLoading = challengeState.isLoading,
                    slabLanded = slabLanded,
                    onSlabLanded = { slabLanded = true },
                    onGoHome = onGoHome,
                    onBreakCode = viewModel::startChallenge,
                )
            }
        }
    }
}

/** Resolves [packageName]'s own launcher intent and starts it. */
private fun launchApp(context: Context, packageName: String?) {
    packageName
        ?.let { context.packageManager.getLaunchIntentForPackage(it) }
        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ?.let { context.startActivity(it) }
}

/**
 * How long a terminal feedback flash is held. Matches NfcTapOverlayActivity's own dismiss delay:
 * the two render the same kind of moment and must not feel differently paced.
 */
private const val FEEDBACK_HOLD_MILLIS = 1200L
