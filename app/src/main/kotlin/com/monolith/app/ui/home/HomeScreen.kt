package com.monolith.app.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.ui.components.MonolithSnackbarHost
import com.monolith.app.ui.theme.MonolithButtonShape
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
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showBypassConfirm by remember { mutableStateOf(false) }
    var showActivateConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = context.getString(
                when (event) {
                    HomeEvent.UnknownTag -> R.string.snack_tag_unknown
                    HomeEvent.NoTagLinked -> R.string.snack_tag_none_linked
                    HomeEvent.BypassStarted -> R.string.snack_bypass_started
                    HomeEvent.BypassEnded -> R.string.snack_bypass_ended
                    is HomeEvent.Toggled ->
                        if (event.nowActive) R.string.snack_tag_locked else R.string.snack_tag_unlocked
                },
            )
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    LaunchedEffect(updateState) {
        when (val state = updateState) {
            UpdateUiState.UpToDate -> {
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.update_up_to_date)) }
                viewModel.dismissUpdateDialog()
            }
            is UpdateUiState.Failed -> {
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.update_check_failed, state.message))
                }
                viewModel.dismissUpdateDialog()
            }
            is UpdateUiState.ReadyToInstall -> {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", state.file)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
                viewModel.dismissUpdateDialog()
            }
            else -> Unit
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
                // this header open; cropping the viewport took that away and collapsed the row
                // onto the overflow button's 48dp.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
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

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        if (updateState == UpdateUiState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options_cta))
                        }
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.update_check_cta)) },
                            enabled = updateState != UpdateUiState.Checking,
                            onClick = {
                                showMenu = false
                                viewModel.checkForUpdates()
                            },
                        )
                        if (uiState.linkedTag != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.relink_tag_cta)) },
                                onClick = {
                                    showMenu = false
                                    onLinkTag()
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            BlockStatusCard(
                isActive = uiState.blockState.isActive,
                bypassSecondsRemaining = uiState.bypassSecondsRemaining,
                linkedTag = uiState.linkedTag,
                nextScheduledFire = uiState.nextScheduledFire,
                // Only while off. There is no tap-to-deactivate counterpart: that stays tag-only.
                onActivate = { showActivateConfirm = true }.takeIf { !uiState.blockState.isActive },
            )

            TimeSavedTodayCard(
                todaySavedMillis = uiState.todaySavedMillis,
                todayBuckets = uiState.todayBuckets,
                onClick = onViewTimeSaved,
            )

            // One panel rather than three separate outlined buttons: these are places to go, not
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
                    onClick = onManageSchedules,
                )
            }

            if (uiState.blockState.isActive) {
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

    when (val state = updateState) {
        is UpdateUiState.Available -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdateDialog,
                title = { Text(stringResource(R.string.update_available_title)) },
                text = { Text(stringResource(R.string.update_available_body, state.versionName)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.startDownload(state.versionName, state.downloadUrl) }) {
                        Text(stringResource(R.string.update_download_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissUpdateDialog) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        is UpdateUiState.NeedsInstallPermission -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdateDialog,
                title = { Text(stringResource(R.string.update_install_permission_title)) },
                text = { Text(stringResource(R.string.update_install_permission_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                        viewModel.dismissUpdateDialog()
                    }) {
                        Text(stringResource(R.string.update_open_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissUpdateDialog) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        is UpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_downloading)) },
                text = {
                    if (state.fraction != null) {
                        LinearProgressIndicator(
                            progress = { state.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {},
            )
        }
        else -> Unit
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Inset past the icon column, so the rows read as one list rather than stacked slices. */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 54.dp),
    )
}

@Composable
private fun BlockStatusCard(
    isActive: Boolean,
    bypassSecondsRemaining: Long,
    linkedTag: NfcTagLink?,
    nextScheduledFire: ZonedDateTime?,
    onActivate: (() -> Unit)?,
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
            .padding(24.dp),
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
                text = if (isActive) stringResource(R.string.block_mode_active_desc) else stringResource(R.string.block_mode_inactive_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = onBackground.copy(alpha = 0.7f),
            )
            if (linkedTag == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.no_tag_linked),
                    style = MaterialTheme.typography.bodySmall,
                    color = onBackground.copy(alpha = 0.7f),
                )
            }
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
            if (bypassSecondsRemaining > 0) {
                Spacer(Modifier.height(12.dp))
                val minutes = bypassSecondsRemaining / 60
                val seconds = bypassSecondsRemaining % 60
                Text(
                    text = "${stringResource(R.string.bypass_active_prefix)} %d:%02d".format(minutes, seconds),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
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
