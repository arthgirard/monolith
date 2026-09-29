package com.monolith.app.ui.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monolith.app.R

/** Another group for a member who already has one: their name comes along, so none is asked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGroupSheet(
    busy: Boolean,
    message: FriendsMessage?,
    onCreate: () -> Unit,
    onJoin: (inviteCode: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var inviteCode by rememberSaveable { mutableStateOf("") }

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
            SectionHeader(stringResource(R.string.friends_add_join_heading))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it.take(12) },
                    label = { Text(stringResource(R.string.friends_invite_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onJoin(inviteCode) }, enabled = inviteCode.isNotBlank() && !busy) {
                    Text(stringResource(R.string.friends_join))
                }
            }
            HorizontalDivider()
            Button(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.friends_create))
            }
        }
    }
}
