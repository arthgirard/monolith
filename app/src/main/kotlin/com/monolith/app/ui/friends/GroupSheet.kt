package com.monolith.app.ui.friends

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.monolith.app.R
import com.monolith.app.domain.model.GroupMembership
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.ui.theme.mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(
    membership: GroupMembership,
    busy: Boolean,
    message: FriendsMessage?,
    onRename: (String) -> Unit,
    onShareChange: (ShareSettings) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(membership.displayName) }
    var showRecovery by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            message?.let {
                Text(stringResource(it.text), color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.friends_invite_label), style = MaterialTheme.typography.labelMedium)
                    Text(membership.inviteCode, style = MaterialTheme.typography.headlineSmall.mono())
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(membership.inviteCode)) }) {
                    Text(stringResource(R.string.friends_copy))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text(stringResource(R.string.friends_name_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { onRename(name) },
                    enabled = !busy && name.trim().length in 1..24 && name.trim() != membership.displayName,
                ) { Text(stringResource(R.string.friends_rename)) }
            }

            ShareToggles(membership.share, enabled = !busy, onChange = onShareChange)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.friends_recovery_label), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.friends_recovery_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showRecovery) {
                    SelectionContainer { Text(membership.token, style = MaterialTheme.typography.bodyMedium.mono()) }
                    TextButton(onClick = { copyRecoveryCode(context, membership.token) }) {
                        Text(stringResource(R.string.friends_copy))
                    }
                } else {
                    TextButton(onClick = { showRecovery = true }) { Text(stringResource(R.string.friends_recovery_show)) }
                }
            }

            OutlinedButton(onClick = { confirmLeave = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.friends_leave))
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.friends_leave_confirm_title)) },
            text = { Text(stringResource(R.string.friends_leave_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onLeave() }) { Text(stringResource(R.string.friends_leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
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
