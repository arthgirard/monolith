package com.monolith.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.AppLanguage
import com.monolith.app.ui.components.MonolithSnackbarHost
import com.monolith.app.ui.components.SettingsDivider
import com.monolith.app.ui.components.SettingsGroup
import com.monolith.app.ui.components.SettingsRow
import com.monolith.app.ui.strictness.labelRes
import com.monolith.app.util.AppLocale
import kotlinx.coroutines.launch

/**
 * The settings that are about Monolith itself rather than about what it blocks. Two of them
 * decide how hard it is to get out, so they follow the tag rule; the language and the update
 * check don't, and stay live either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEditStrictness: () -> Unit,
    onLinkTag: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }
    // Read rather than collected: below API 33 the choice lives in SharedPreferences, and either
    // way the activity is recreated the moment it changes, which re-reads this.
    val language = remember(context) { AppLocale.selected(context) }

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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { MonolithSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.isLocked) {
                // One banner for the whole group rather than a reason on each greyed row: the
                // rule is the same for all of them, and repeating it three times reads as three
                // separate obstacles.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_locked_banner),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Filled.Lock,
                        label = stringResource(R.string.strictness_title),
                        value = stringResource(uiState.strictness.labelRes),
                        enabled = !uiState.isLocked,
                        onClick = onEditStrictness,
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.Language,
                        label = stringResource(R.string.settings_language),
                        value = language?.displayName ?: stringResource(R.string.settings_language_system),
                        onClick = { showLanguagePicker = true },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.Nfc,
                        label = stringResource(
                            if (uiState.linkedTag == null) R.string.link_tag_cta else R.string.relink_tag_cta,
                        ),
                        enabled = !uiState.isLocked,
                        onClick = onLinkTag,
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.SystemUpdate,
                        label = stringResource(R.string.update_check_cta),
                        onClick = viewModel::checkForUpdates,
                    )
                }
            }
        }
    }

    if (showLanguagePicker) {
        LanguagePicker(
            selected = language,
            onSelect = { picked ->
                showLanguagePicker = false
                if (picked == language) return@LanguagePicker
                AppLocale.select(context, picked)
                // From API 33 the platform recreates the visible activities itself once the
                // per-app locale is set; below it, nothing does.
                if (AppLocale.needsManualRecreate) activity?.recreate()
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    if (updateState == UpdateUiState.Checking) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_check_cta)) },
            text = { CircularProgressIndicator(modifier = Modifier.size(32.dp)) },
            confirmButton = {},
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

/**
 * The language list. Every entry is written in its own language, including the app's own English,
 * so it stays readable from inside a language the reader can't -- which is the state anyone
 * opening this dialog a second time is most likely in.
 */
@Composable
private fun LanguagePicker(
    selected: AppLanguage?,
    onSelect: (AppLanguage?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LanguageOption(
                    label = stringResource(R.string.settings_language_system),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
                AppLanguage.entries.forEach { language ->
                    LanguageOption(
                        label = language.displayName,
                        selected = language == selected,
                        onClick = { onSelect(language) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The Activity behind a Compose context, which recreate() needs and LocalContext doesn't promise. */
private fun Context.findActivity(): Activity? = generateSequence(this) {
    (it as? ContextWrapper)?.baseContext
}.filterIsInstance<Activity>().firstOrNull()
