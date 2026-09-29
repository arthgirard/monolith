package com.monolith.app.ui.friends

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.monolith.app.R
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.ui.components.SettingsGroup
import com.monolith.app.ui.theme.mono

/** Settings for the selected [group], then for the member across all their groups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(
    identity: Identity,
    group: GroupInfo,
    busy: Boolean,
    message: FriendsMessage?,
    onRenameGroup: (String) -> Unit,
    onShareChange: (ShareSettings) -> Unit,
    onLeave: () -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Leaving the last group deletes the member on the server, so it gets the stronger wording.
    val lastGroup = identity.groups.size <= 1
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            message?.let {
                Text(stringResource(it.text), color = MaterialTheme.colorScheme.error)
            }

            SectionHeader(stringResource(R.string.friends_section_group))
            InviteCodeCard(group.inviteCode)
            GroupNameField(group, busy, onRenameGroup)
            ShareToggles(group.share, enabled = !busy, onChange = onShareChange)
            TextButton(
                onClick = { confirmLeave = true },
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(if (lastGroup) R.string.friends_leave else R.string.friends_leave_group))
            }

            SectionHeader(stringResource(R.string.friends_section_you))
            DisplayNameField(identity.displayName, busy, onRename)
            RecoveryCard(identity.token)
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = {
                Text(stringResource(if (lastGroup) R.string.friends_leave_confirm_title else R.string.friends_leave_group_confirm_title))
            },
            text = {
                Text(stringResource(if (lastGroup) R.string.friends_leave_confirm_body else R.string.friends_leave_group_confirm_body))
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onLeave() }, enabled = !busy) { Text(stringResource(R.string.friends_leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun InviteCodeCard(inviteCode: String) {
    val clipboard = LocalClipboardManager.current
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.friends_invite_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(inviteCode, style = MaterialTheme.typography.headlineSmall.mono())
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(inviteCode)) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.friends_copy))
            }
        }
    }
}

/** Only groups of three or more have a name; smaller ones go by who's in them. */
@Composable
private fun GroupNameField(group: GroupInfo, busy: Boolean, onSave: (String) -> Unit) {
    if (group.memberCount < 3) {
        Text(
            stringResource(R.string.friends_group_name_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var name by rememberSaveable(group.id) { mutableStateOf(group.name.orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(32) },
            label = { Text(stringResource(R.string.friends_group_name_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        // Empty is allowed: it clears the name and the group goes back to its members' names.
        OutlinedButton(
            onClick = { onSave(name) },
            enabled = !busy && name.trim() != group.name.orEmpty(),
        ) { Text(stringResource(R.string.friends_rename)) }
    }
}

@Composable
private fun DisplayNameField(displayName: String, busy: Boolean, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(displayName) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            label = { Text(stringResource(R.string.friends_name_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onSave(name) },
            enabled = !busy && name.trim().length in 1..24 && name.trim() != displayName,
        ) { Text(stringResource(R.string.friends_rename)) }
    }
}

@Composable
private fun RecoveryCard(token: String) {
    val context = LocalContext.current
    var showRecovery by rememberSaveable { mutableStateOf(false) }
    SettingsGroup {
        if (showRecovery) {
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
                        Text(token, style = MaterialTheme.typography.bodyMedium.mono())
                    }
                    IconButton(onClick = { copyRecoveryCode(context, token) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.friends_copy))
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.friends_recovery_show),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { showRecovery = true }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

/**
 * Copies the recovery code marked sensitive, so Android 13+ hides it from the clipboard preview
 * and keyboards don't offer it as a suggestion. The code is the account.
 */
private fun copyRecoveryCode(context: Context, token: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(context.getString(R.string.friends_recovery_label), token)
    val sensitiveKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        "android.content.extra.IS_SENSITIVE"
    }
    clip.description.extras = PersistableBundle().apply { putBoolean(sensitiveKey, true) }
    clipboard.setPrimaryClip(clip)
}
