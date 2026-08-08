package com.monolith.app.ui.bypass

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.SlotResult
import com.monolith.app.domain.usecase.CodeBreakerGenerator
import com.monolith.app.nfc.NfcManager
import com.monolith.app.nfc.NfcTagBus
import com.monolith.app.service.BlockOverlayGuard
import com.monolith.app.ui.theme.MonolithAmber
import com.monolith.app.ui.theme.MonolithGray
import com.monolith.app.ui.theme.MonolithGrayDark
import com.monolith.app.ui.theme.MonolithTheme
import com.monolith.app.ui.theme.MonolithWhite
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
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
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

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

@Composable
private fun BlockOverlayScreen(
    onGoHome: () -> Unit,
    onUnlocked: () -> Unit,
    viewModel: BlockOverlayViewModel = hiltViewModel(),
) {
    val blockState by viewModel.blockState.collectAsState()
    val challengeState by viewModel.challengeState.collectAsState()
    val context = LocalContext.current

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
        launchAppAndFinish(context, challengeState.unlockedPackage, onUnlocked)
    }
    // A tag tap mid-challenge disables Monolith outright: hold the "Monolith disabled" feedback
    // on screen briefly (matching NfcTapOverlayActivity's own tap feedback elsewhere), then open
    // the app the same way Continue does.
    LaunchedEffect(challengeState.tagDisabled) {
        if (!challengeState.tagDisabled) return@LaunchedEffect
        delay(TAG_DISABLED_DISPLAY_MILLIS)
        launchAppAndFinish(context, challengeState.unlockedPackage, onUnlocked)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val codeBreaker = challengeState.codeBreaker
        val waiverTarget = challengeState.waiverTarget
        when {
            challengeState.tagDisabled -> {
                TagDisabledFeedback(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
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
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                )
            }
            codeBreaker != null -> {
                CodeBreakerBoard(
                    codeBreaker = codeBreaker,
                    codeRestarted = challengeState.codeRestarted,
                    onSubmitGuess = viewModel::submitGuess,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_monolith_mark),
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
                    Spacer(Modifier.height(16.dp))
                    if (challengeState.isLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                    } else {
                        BreakCodeButton(onClick = viewModel::startChallenge)
                    }
                }
            }
        }
    }
}

/**
 * The escape hatch, deliberately quieter than "Go home" above it: no panel, no ring, no fill --
 * one muted line naming what it costs (a puzzle) and what it buys (five minutes), led by the same
 * open-lock glyph the "Monolith disabled" feedback uses, small enough that it has to be looked
 * for rather than offering itself.
 */
@Composable
private fun BreakCodeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.LockOpen,
            contentDescription = null,
            tint = MonolithGray,
            modifier = Modifier.size(14.dp),
        )
        Text(
            stringResource(R.string.overlay_break_code_title),
            style = MaterialTheme.typography.labelMedium,
            color = MonolithGray,
        )
        Text(
            stringResource(R.string.overlay_break_code_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MonolithGray,
        )
    }
}

private const val TAG_DISABLED_DISPLAY_MILLIS = 1200L

/** Resolves [packageName]'s own launcher intent and starts it, then finishes the overlay. */
private fun launchAppAndFinish(context: Context, packageName: String?, onUnlocked: () -> Unit) {
    packageName
        ?.let { context.packageManager.getLaunchIntentForPackage(it) }
        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ?.let { context.startActivity(it) }
    onUnlocked()
}

/** Mirrors NfcTapOverlayActivity's own "Monolith disabled" tap feedback, shown from inside this overlay instead. */
@Composable
private fun TagDisabledFeedback(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.LockOpen,
            contentDescription = null,
            // Icon size and text style track NfcTapOverlayActivity's feedback exactly -- the two
            // render the same "Monolith disabled" moment and must not differ in weight.
            modifier = Modifier.height(72.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.tap_overlay_unlocked),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

private val SlotSize = 40.dp
private val KeySize = 44.dp
private val LegendSwatchSize = 14.dp

/** What each tile color in a past guess means -- a legend reads faster than a sentence. */
@Composable
private fun CodeBreakerLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(color = MonolithAmber, label = stringResource(R.string.codebreaker_legend_exact))
        LegendItem(color = MonolithGray, label = stringResource(R.string.codebreaker_legend_partial))
        LegendItem(color = MonolithGrayDark, label = stringResource(R.string.codebreaker_legend_miss))
    }
}

@Composable
private fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(LegendSwatchSize)
                .clip(RectangleShape)
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodeBreakerBoard(
    codeBreaker: CodeBreaker,
    codeRestarted: Boolean,
    onSubmitGuess: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf<List<Int>>(emptyList()) }
    val slotCount = codeBreaker.secret.size

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.codebreaker_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // Amber only in the moment the last board burned -- the one state on this screen the
        // solver has to notice, since every deduction they'd made no longer applies.
        Text(
            if (codeRestarted) {
                stringResource(R.string.codebreaker_out_of_tries, codeBreaker.triesLeft)
            } else {
                stringResource(R.string.codebreaker_tries_left, codeBreaker.triesLeft, CodeBreaker.MAX_GUESSES)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (codeRestarted) MonolithAmber else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        CodeBreakerLegend()
        Spacer(Modifier.height(20.dp))

        codeBreaker.guesses.forEach { guess ->
            GuessRow(guess = guess)
            Spacer(Modifier.height(8.dp))
        }
        if (codeBreaker.guesses.isNotEmpty()) Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until slotCount).forEach { index ->
                GuessSlot(symbol = current.getOrNull(index))
            }
        }
        Spacer(Modifier.height(20.dp))
        MonolithSymbolPicker(
            onSymbolPress = { symbol -> if (current.size < slotCount) current = current + symbol },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { current = emptyList() }, enabled = current.isNotEmpty()) {
                Text(stringResource(R.string.codebreaker_clear))
            }
            OutlinedButton(
                onClick = {
                    onSubmitGuess(current)
                    current = emptyList()
                },
                enabled = current.size == slotCount,
            ) {
                Text(stringResource(R.string.codebreaker_submit))
            }
        }
    }
}

@Composable
private fun GuessRow(guess: CodeBreaker.Guess, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        guess.values.forEachIndexed { index, symbol ->
            GuessSlot(symbol = symbol, result = guess.slotResults[index])
        }
    }
}

/** Amber = right symbol, right slot. Gray = right symbol, wrong slot. Dark = not in the code. */
@Composable
private fun GuessSlot(symbol: Int?, result: SlotResult? = null, modifier: Modifier = Modifier) {
    val background = when (result) {
        SlotResult.EXACT -> MonolithAmber
        SlotResult.PARTIAL -> MonolithGray
        SlotResult.MISS, null -> MonolithGrayDark
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SlotSize)
            .clip(RectangleShape)
            .background(background),
    ) {
        if (symbol != null) {
            SymbolSwatch(symbol = symbol, modifier = Modifier.size(SlotSize * 0.6f))
        }
    }
}

private const val SYMBOL_PICKER_COLUMNS = 4

/** Fixed-grid, sharp-cornered, monochrome-block symbol picker -- wraps to a second row once the symbol count outgrows one. */
@Composable
private fun MonolithSymbolPicker(onSymbolPress: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until CodeBreakerGenerator.SYMBOL_COUNT).chunked(SYMBOL_PICKER_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { symbol ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(KeySize)
                            .clip(RectangleShape)
                            .background(MonolithGrayDark)
                            .clickable { onSymbolPress(symbol) },
                    ) {
                        SymbolSwatch(symbol = symbol, modifier = Modifier.size(KeySize * 0.55f))
                    }
                }
            }
        }
    }
}

/**
 * One of [CodeBreakerGenerator.SYMBOL_COUNT] fill patterns of the same rectangle -- distinguished
 * by silhouette, not color, so the puzzle stays inside Monolith's deliberately monochrome,
 * all-right-angles visual language (see [com.monolith.app.ui.theme.MonolithBlack] and
 * `ic_monolith_mark.xml`) instead of borrowing colored pegs from real Mastermind.
 */
@Composable
private fun SymbolSwatch(symbol: Int, modifier: Modifier = Modifier, tint: Color = MonolithWhite) {
    Canvas(modifier = modifier) {
        when (symbol) {
            0 -> drawRect(color = tint)
            1 -> drawRect(color = tint, style = Stroke(width = size.minDimension * 0.16f))
            2 -> drawRect(color = tint, topLeft = Offset(0f, size.height / 2f), size = Size(size.width, size.height / 2f))
            3 -> drawRect(color = tint, size = Size(size.width, size.height / 2f))
            4 -> drawRect(color = tint, size = Size(size.width / 2f, size.height))
            5 -> drawRect(color = tint, topLeft = Offset(size.width / 2f, 0f), size = Size(size.width / 2f, size.height))
            6 -> {
                // Two opposite quadrants -- a diagonal split, distinct from the half-fills above.
                drawRect(color = tint, size = Size(size.width / 2f, size.height / 2f))
                drawRect(
                    color = tint,
                    topLeft = Offset(size.width / 2f, size.height / 2f),
                    size = Size(size.width / 2f, size.height / 2f),
                )
            }
            else -> {
                // Inset center square -- a "dot", distinct from the full/half/quadrant fills above.
                val inset = size.minDimension * 0.28f
                drawRect(
                    color = tint,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2f, size.height - inset * 2f),
                )
            }
        }
    }
}

@Composable
private fun WaiverStep(
    sentence: String,
    input: String,
    matches: Boolean,
    onInputChange: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.waiver_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RectangleShape)
                .background(MonolithGrayDark)
                .padding(16.dp),
        ) {
            Text(sentence, style = MaterialTheme.typography.bodyLarge, color = MonolithWhite)
        }
        Spacer(Modifier.height(20.dp))
        // No paste: the point is typing the sentence out, not lifting it in from the clipboard.
        // Hiding the selection toolbar removes the visible Paste action; rejecting any
        // onValueChange that inserts more than one character in one shot catches paste attempts
        // that don't go through the toolbar (external keyboard shortcuts, IME word-commit,
        // drag-and-drop) without blocking normal one-key-at-a-time typing or deletes.
        val noToolbar = remember {
            object : TextToolbar {
                override val status: TextToolbarStatus = TextToolbarStatus.Hidden
                override fun showMenu(
                    rect: Rect,
                    onCopyRequested: (() -> Unit)?,
                    onPasteRequested: (() -> Unit)?,
                    onCutRequested: (() -> Unit)?,
                    onSelectAllRequested: (() -> Unit)?,
                ) = Unit
                override fun hide() = Unit
            }
        }
        CompositionLocalProvider(LocalTextToolbar provides noToolbar) {
            OutlinedTextField(
                value = input,
                onValueChange = { newText -> if (newText.length - input.length <= 1) onInputChange(newText) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.waiver_placeholder)) },
                keyboardOptions = KeyboardOptions(autoCorrect = false, capitalization = KeyboardCapitalization.None),
                isError = input.isNotEmpty() && !matches,
                singleLine = false,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (matches) {
            OutlinedButton(onClick = onContinue) {
                Text(stringResource(R.string.waiver_continue))
            }
        }
    }
}
