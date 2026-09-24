package com.monolith.app.ui.home

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.ui.components.MonolithSnackbarHost
import com.monolith.app.ui.components.SettingsDivider
import com.monolith.app.ui.components.SettingsGroup
import com.monolith.app.ui.components.SettingsRow
import com.monolith.app.service.EnforcementStatus
import com.monolith.app.ui.theme.MonolithButtonShape
import com.monolith.app.ui.theme.tabular
import com.monolith.app.ui.timesaved.TimeSavedBarChart
import com.monolith.app.util.formatDuration
import com.monolith.app.util.formatNextFire
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

@Composable
fun HomeScreen(
    onManageApps: () -> Unit,
    onManageImportantPeople: () -> Unit,
    onLinkTag: () -> Unit,
    onViewTimeSaved: () -> Unit,
    onManageSchedules: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showBypassConfirm by remember { mutableStateOf(false) }
    var showActivateConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = context.getString(
                when (event) {
                    HomeEvent.UnknownTag -> R.string.snack_tag_unknown
                    HomeEvent.NoTagLinked -> R.string.snack_tag_none_linked
                    HomeEvent.BypassStarted -> R.string.snack_bypass_started
                    HomeEvent.BypassEnded -> R.string.snack_bypass_ended
                    HomeEvent.Resumed -> R.string.snack_blocking_resumed
                    is HomeEvent.Toggled ->
                        if (event.nowActive) R.string.snack_tag_locked else R.string.snack_tag_unlocked
                },
            )
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    Scaffold(
        snackbarHost = { MonolithSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                // Height pinned because the wordmark no longer sets it. The old asset carried
                // ~16dp of blank artboard above and below its ink, which was quietly holding
                // this header open; cropping the viewport took that away.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_monolith_wordmark),
                    contentDescription = stringResource(R.string.home_title),
                    // The height of the logo itself now, not of a half-empty artboard. Same
                    // 28dp the block overlay uses, so the mark is one size across the app.
                    //
                    // The start inset replaces the blank space the old asset carried on its left
                    // (54.5 of 616 units, ~16dp at the size this used to draw). Cropping the
                    // viewport pulled the mark flush against the screen padding; restoring it as
                    // real padding keeps the header looking as it did, and keeps it deliberate
                    // rather than a side effect of the artboard. The block overlay wants the
                    // opposite -- flush, on the same rule as its text -- so it sets no inset.
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .height(28.dp),
                )
                Spacer(Modifier.weight(1f))
                // Same corner the overflow menu used to sit in. Its two items live in Settings
                // now, so the corner leads there instead of opening a menu on the way.
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            BlockStatusCard(
                isActive = uiState.blockState.isActive,
                pause = uiState.pause,
                nowMillis = uiState.nowMillis,
                linkedTag = uiState.linkedTag,
                nextScheduledFire = uiState.nextScheduledFire,
                // Only while off, and only with a tag linked: the card's own caption already
                // says there is none, and offering a lock with no key is offering a trap. There
                // is no tap-to-deactivate counterpart either; that stays tag-only.
                onActivate = { showActivateConfirm = true }
                    .takeIf { !uiState.blockState.isActive && uiState.linkedTag != null },
                onResume = viewModel::resumeBlocking,
            )

            TimeSavedTodayCard(
                todaySavedMillis = uiState.todaySavedMillis,
                todayBuckets = uiState.todayBuckets,
                onClick = onViewTimeSaved,
            )

            // One panel rather than a stack of outlined buttons: these are places to go, not
            // actions to take, and stacking them as full-width buttons made the screen read as a
            // form. Sharing a surface with the cards above ties the whole column together.
            SettingsGroup {
                if (uiState.linkedTag == null) {
                    SettingsRow(
                        icon = Icons.Filled.Nfc,
                        label = stringResource(R.string.link_tag_cta),
                        onClick = onLinkTag,
                    )
                    SettingsDivider()
                }
                SettingsRow(
                    icon = Icons.Filled.Apps,
                    label = stringResource(R.string.manage_apps_cta),
                    onClick = onManageApps,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Filled.People,
                    label = stringResource(R.string.manage_important_people_cta),
                    onClick = onManageImportantPeople,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Filled.Schedule,
                    label = stringResource(R.string.schedules_cta),
                    // A schedule is a lock that arrives on its own. Without a tag it would arrive
                    // with nothing to open it, and activation refuses it anyway, so the row says
                    // what is missing rather than leading to schedules that would never fire.
                    enabled = uiState.linkedTag != null,
                    value = stringResource(R.string.needs_tag_value).takeIf { uiState.linkedTag == null },
                    onClick = onManageSchedules,
                )
            }

            if (uiState.blockState.isActive && uiState.strictness.allowsEmergencyBypass) {
                // Spent bypasses leave the button in place, disabled, with the rule underneath.
                // It used to disappear outright, which reads as a bug rather than as a limit --
                // the user is left wondering where the escape hatch went, at the exact moment
                // they were looking for it.
                val bypassUsed = uiState.blockState.bypassUsed
                Button(
                    shape = MonolithButtonShape,
                    onClick = { showBypassConfirm = true },
                    enabled = !bypassUsed,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.emergency_bypass))
                }
                if (bypassUsed) {
                    // No extra Spacer here: the Column's own 16dp arrangement already separates
                    // this from the button above it, matching the gap everywhere else in the
                    // column instead of doubling up.
                    Text(
                        stringResource(R.string.bypass_used_caption),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.copyright_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showBypassConfirm) {
        AlertDialog(
            onDismissRequest = { showBypassConfirm = false },
            title = { Text(stringResource(R.string.bypass_confirm_title)) },
            text = { Text(stringResource(R.string.bypass_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.startEmergencyBypass()
                    showBypassConfirm = false
                }) {
                    Text(stringResource(R.string.bypass_confirm_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBypassConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showActivateConfirm) {
        AlertDialog(
            onDismissRequest = { showActivateConfirm = false },
            title = { Text(stringResource(R.string.activate_confirm_title)) },
            // Turning on is free, turning off costs a tap. Worth one deliberate confirmation so
            // nobody locks themselves out of their phone by brushing a button.
            text = { Text(stringResource(R.string.activate_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.activate()
                    showActivateConfirm = false
                }) {
                    Text(stringResource(R.string.activate_confirm_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showActivateConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun BlockStatusCard(
    isActive: Boolean,
    pause: EnforcementStatus?,
    nowMillis: Long,
    linkedTag: NfcTagLink?,
    nextScheduledFire: ZonedDateTime?,
    onActivate: (() -> Unit)?,
    onResume: () -> Unit,
) {
    val background = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val onBackground = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            // Clip before clickable so the ripple follows the rounded corners rather than
            // spilling into the square bounds.
            .then(if (onActivate != null) Modifier.clickable(onClick = onActivate) else Modifier)
            // The pause row is 48dp tall for the touch target, which leaves ~14dp under its text
            // already; the full 24dp on top of that left the card bottom-heavy. Trimmed so the
            // text ends 24dp from the edge, matching the title's distance from the top.
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = if (pause != null) 10.dp else 24.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isActive) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = onBackground,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isActive) stringResource(R.string.block_mode_active) else stringResource(R.string.block_mode_inactive),
                    style = MaterialTheme.typography.headlineMedium,
                    color = onBackground,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                // Without a tag the card is not tappable, so it must not keep offering the tap.
                // It asks for the tag instead, which is the one thing standing in the way.
                text = when {
                    isActive -> stringResource(R.string.block_mode_active_desc)
                    linkedTag == null -> stringResource(R.string.block_mode_needs_tag_desc)
                    else -> stringResource(R.string.block_mode_inactive_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = onBackground.copy(alpha = 0.7f),
            )
            // Only while Monolith is off: once it's on, the next fire is a no-op and saying so
            // would read as a promise that something changes then.
            if (!isActive && nextScheduledFire != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${stringResource(R.string.next_scheduled_prefix)} ${formatNextFire(nextScheduledFire)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onBackground.copy(alpha = 0.7f),
                )
            }
            if (pause != null) {
                Spacer(Modifier.height(16.dp))
                PauseRow(pause = pause, nowMillis = nowMillis, contentColor = onBackground, onResume = onResume)
            }
        }

        if (linkedTag != null) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    Icons.Filled.Nfc,
                    contentDescription = "Tag linked",
                    tint = onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The pause running now, and the way out of it, as one quiet line under the card's status. It
 * used to be a large countdown plus a full outlined button, two loud things on a card whose job
 * is to say one thing; now the pause is named, its time left is data, and resuming is a trailing
 * action rather than a second call to action. The whole row is the target.
 */
@Composable
private fun PauseRow(
    pause: EnforcementStatus,
    nowMillis: Long,
    contentColor: Color,
    onResume: () -> Unit,
) {
    val remaining = formatCountdown(((pause.expiresAtMillis ?: nowMillis) - nowMillis).coerceAtLeast(0L))
    val label = when (pause) {
        is EnforcementStatus.AppUnlocked ->
            stringResource(R.string.home_pause_unlock, rememberAppLabel(pause.packageName), remaining)
        else -> stringResource(R.string.home_pause_bypass, remaining)
    }

    Column {
        HorizontalDivider(thickness = Dp.Hairline, color = contentColor.copy(alpha = 0.15f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onResume),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = contentColor.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.resume_short),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** m:ss, rounded down: the notification's chronometer reads the same second the same way. */
private fun formatCountdown(millis: Long): String {
    val seconds = millis / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The name the user knows the app by; the package name if it's gone since it was unlocked. */
@Composable
private fun rememberAppLabel(packageName: String): String {
    val packageManager = LocalContext.current.packageManager
    return remember(packageName) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}

@Composable
private fun TimeSavedTodayCard(
    todaySavedMillis: Long,
    todayBuckets: List<TimeSavedBucket>,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Filled like every other card on this screen rather than outlined. This used to be
            // border-only because surface and surfaceVariant were the same value in light, so a
            // filled card swallowed the chart's track; they are distinct now.
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                stringResource(R.string.time_saved_today_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDuration(todaySavedMillis),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(16.dp))
        TimeSavedBarChart(
            buckets = todayBuckets,
            periodType = TimePeriodType.DAY,
            modifier = Modifier.weight(1f),
            chartHeight = 40.dp,
            showLabels = false,
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = stringResource(R.string.time_saved_cta),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
