package com.monolith.app.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.monolith.app.R
import com.monolith.app.domain.model.AppUpdate

/**
 * Every dialog an update passes through, from "there's a new version" to handing the APK to
 * Android's installer. Up to date and a failed check are left to the caller, which knows whether
 * anyone asked.
 */
@Composable
fun UpdateDialogs(
    state: UpdateUiState,
    onDownload: (AppUpdate) -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
) {
    val context = LocalContext.current

    val currentOnResume by rememberUpdatedState(onResume)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentOnResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state) {
        if (state !is UpdateUiState.ReadyToInstall) return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", state.file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        onDismiss()
    }

    when (state) {
        UpdateUiState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_check_cta)) },
            text = { CircularProgressIndicator(modifier = Modifier.size(32.dp)) },
            confirmButton = {},
        )
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onLater,
            title = { Text(stringResource(R.string.update_available_title)) },
            text = { ReleaseNotes(state.update) },
            confirmButton = {
                TextButton(onClick = { onDownload(state.update) }) {
                    Text(stringResource(R.string.update_download_install))
                }
            },
            dismissButton = {
                TextButton(onClick = onLater) { Text(stringResource(R.string.not_now_cta)) }
            },
        )
        // Stays up while Android's permission screen is open; coming back resumes the download
        // through onResume, or leaves this here to cancel.
        is UpdateUiState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = onDismiss,
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
                }) {
                    Text(stringResource(R.string.update_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading)) },
            text = {
                if (state.fraction != null) {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
        is UpdateUiState.Failed -> if (state.update != null) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_available_title)) },
                text = { Text(stringResource(R.string.update_download_failed, state.message)) },
                confirmButton = {
                    TextButton(onClick = { onDownload(state.update) }) {
                        Text(stringResource(R.string.update_download_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now_cta)) }
                },
            )
        }
        else -> Unit
    }
}

@Composable
private fun ReleaseNotes(update: AppUpdate) {
    Column(
        modifier = Modifier
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.update_available_body, update.versionName))
        update.releaseNotes.forEach { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
