package com.monolith.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.monolith.app.R
import com.monolith.app.domain.model.TagCodeState
import com.monolith.app.ui.components.SettingsDivider
import com.monolith.app.ui.components.SettingsGroup
import com.monolith.app.ui.components.SettingsRow
import com.monolith.app.ui.components.SettingsToggleRow
import com.monolith.app.ui.components.copySensitive
import com.monolith.app.ui.theme.MonolithMonoFamily
import com.monolith.app.ui.theme.mono
import com.monolith.app.util.appLocale
import com.monolith.app.util.formatRelativeTime

/**
 * The encrypted backup: whether it runs, when it last did, the code that brings it back, and the
 * way in on a new phone. Restore follows the tag rule, since it replaces the blocked apps.
 */
@Composable
fun BackupSection(
    state: BackupUiState,
    isLocked: Boolean,
    onToggle: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onSaveToTag: () -> Unit,
) {
    var showCode by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.backup_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp, start = 4.dp),
        )
        SettingsGroup {
            SettingsToggleRow(
                label = stringResource(R.string.backup_auto),
                checked = state.enabled,
                onCheckedChange = onToggle,
                enabled = !state.busy,
            )
            if (state.enabled) {
                Text(
                    backupCaption(state.lastBackupAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                )
            }
            state.recoveryCode?.let { code ->
                SettingsDivider(startInset = 20.dp)
                if (showCode) RecoveryCode(code) else SettingsRow(
                    icon = Icons.Filled.Key,
                    label = stringResource(R.string.backup_show_code),
                    onClick = { showCode = true },
                )
            }
            state.tagCode?.let { tagCode ->
                SettingsDivider(startInset = 20.dp)
                SettingsRow(
                    icon = Icons.Filled.Nfc,
                    label = stringResource(R.string.backup_save_to_tag),
                    enabled = !isLocked,
                    onClick = onSaveToTag,
                )
                Text(
                    stringResource(
                        when {
                            isLocked -> R.string.backup_restore_refused
                            tagCode == TagCodeState.STALE -> R.string.backup_tag_stale
                            else -> R.string.backup_tag_missing
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                )
            }
            SettingsDivider(startInset = 20.dp)
            SettingsRow(
                icon = Icons.Filled.Restore,
                label = stringResource(R.string.backup_restore),
                enabled = !isLocked,
                onClick = onRestore,
            )
        }
        Text(
            stringResource(if (state.tagCarriesCode) R.string.backup_code_warning_tag else R.string.backup_code_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** "Last backed up 5 min. ago", the time set in mono as the data it is. */
@Composable
private fun backupCaption(lastBackupAt: Long?): AnnotatedString {
    if (lastBackupAt == null) return AnnotatedString(stringResource(R.string.backup_never))
    val age = formatRelativeTime(lastBackupAt, System.currentTimeMillis(), appLocale())
    val text = stringResource(R.string.backup_last, age)
    val start = text.indexOf(age)
    return buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(SpanStyle(fontFamily = MonolithMonoFamily), start, start + age.length)
    }
}

@Composable
private fun RecoveryCode(code: String) {
    val context = LocalContext.current
    val label = stringResource(R.string.friends_recovery_label)
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.friends_recovery_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(code, style = MaterialTheme.typography.bodyMedium.mono())
            }
            IconButton(onClick = { copySensitive(context, label, code) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.friends_copy))
            }
        }
    }
}
