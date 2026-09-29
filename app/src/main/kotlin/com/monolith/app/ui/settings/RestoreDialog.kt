package com.monolith.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.util.formatDateTime

/**
 * Restore from a recovery code: the code, then (when there is a backup) the question naming what
 * gets replaced, then the result. Closing it at any step writes nothing more.
 */
@Composable
fun RestoreDialog(
    onDismiss: () -> Unit,
    viewModel: RestoreViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var code by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    // The view model outlives the dialog: closing resets it, so the next opening starts at the code.
    val close = { viewModel.reset(); onDismiss() }

    when (val step = uiState.step) {
        is RestoreStep.Entry -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.friends_recovery_label)) },
                        singleLine = true,
                        // The code is the account: keep it out of keyboard dictionaries and suggestions.
                        keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    step.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.submit(code) }, enabled = code.isNotBlank() && !uiState.busy) {
                    Text(stringResource(R.string.friends_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
            },
        )
        is RestoreStep.Confirm -> {
            val date = step.backupAt?.let { formatDateTime(context, it) }.orEmpty()
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.backup_restore_title)) },
                text = { Text(stringResource(R.string.backup_restore_confirm_body, date)) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirm, enabled = !uiState.busy) {
                        Text(stringResource(R.string.backup_restore_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        is RestoreStep.Finished -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = { Text(stringResource(step.message)) },
            confirmButton = {
                TextButton(onClick = close) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}
