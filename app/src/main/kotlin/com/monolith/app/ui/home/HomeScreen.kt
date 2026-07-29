package com.monolith.app.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.TagLinkMode
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onManageApps: () -> Unit,
    onLinkTag: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showBypassConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                HomeEvent.UnknownTag -> "That tag isn't linked to Monolith."
                HomeEvent.NoTagLinked -> "Link a tag first from the home screen."
                is HomeEvent.Toggled -> if (event.nowActive) "Block Mode locked in." else "Block Mode unlocked."
            }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)

                IconButton(
                    onClick = viewModel::checkForUpdates,
                    enabled = updateState != UpdateUiState.Checking,
                ) {
                    if (updateState == UpdateUiState.Checking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.SystemUpdate,
                            contentDescription = stringResource(R.string.update_check_cta),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            BlockStatusCard(
                isActive = uiState.blockState.isActive,
                bypassSecondsRemaining = uiState.bypassSecondsRemaining,
            )

            Text(
                text = uiState.linkedTag?.let {
                    val modeLabel = if (it.mode == TagLinkMode.SMART_NDEF) "smart tag" else "generic tag"
                    "Linked to a $modeLabel"
                } ?: stringResource(R.string.no_tag_linked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = onLinkTag, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Nfc, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (uiState.linkedTag == null) {
                        stringResource(R.string.link_tag_cta)
                    } else {
                        "Re-link tag"
                    },
                )
            }

            OutlinedButton(onClick = onManageApps, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.manage_apps_cta))
            }

            if (uiState.blockState.isActive && !uiState.blockState.bypassUsed) {
                Button(
                    onClick = { showBypassConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.emergency_bypass))
                }
            }
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
private fun BlockStatusCard(isActive: Boolean, bypassSecondsRemaining: Long) {
    val background = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val onBackground = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(20.dp))
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
    }
}
