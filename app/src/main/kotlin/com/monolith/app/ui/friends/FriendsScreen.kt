package com.monolith.app.ui.friends

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.ui.theme.mono
import com.monolith.app.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.friends_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    if (uiState.membership != null) {
                        IconButton(onClick = { showSheet = true }) {
                            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.friends_group_settings))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            uiState.message?.let { message ->
                Text(stringResource(message.text), color = MaterialTheme.colorScheme.error)
            }
            when {
                !uiState.loaded -> Unit
                uiState.membership == null -> JoinContent(uiState.busy, viewModel)
                else -> BoardContent(uiState, viewModel)
            }
        }
    }

    val membership = uiState.membership
    if (showSheet && membership != null) {
        GroupSheet(
            membership = membership,
            busy = uiState.busy,
            onRename = viewModel::rename,
            onShareChange = viewModel::updateShare,
            onLeave = { viewModel.leave(); showSheet = false },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun JoinContent(busy: Boolean, viewModel: FriendsViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var recoveryCode by rememberSaveable { mutableStateOf("") }
    var showRestore by rememberSaveable { mutableStateOf(false) }
    var share by remember { mutableStateOf(ShareSettings(saved = true, streak = true, pauses = true)) }
    val nameValid = name.trim().length in 1..24

    Text(stringResource(R.string.friends_intro), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(24) },
        label = { Text(stringResource(R.string.friends_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ShareToggles(share, enabled = !busy, onChange = { share = it })
    Button(onClick = { viewModel.create(name, share) }, enabled = nameValid && !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.friends_create))
    }
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it.take(12) },
            label = { Text(stringResource(R.string.friends_invite_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { viewModel.join(inviteCode, name, share) }, enabled = nameValid && inviteCode.isNotBlank() && !busy) {
            Text(stringResource(R.string.friends_join))
        }
    }
    if (!showRestore) {
        TextButton(onClick = { showRestore = true }) { Text(stringResource(R.string.friends_restore)) }
    } else {
        Text(stringResource(R.string.friends_restore_warning), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = recoveryCode,
            onValueChange = { recoveryCode = it },
            label = { Text(stringResource(R.string.friends_recovery_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { viewModel.restore(recoveryCode) }, enabled = recoveryCode.isNotBlank() && !busy) {
            Text(stringResource(R.string.friends_restore_confirm))
        }
    }
}

@Composable
internal fun ShareToggles(share: ShareSettings, enabled: Boolean, onChange: (ShareSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.friends_share_heading), style = MaterialTheme.typography.titleSmall)
        ToggleRow(R.string.friends_share_saved, share.saved, enabled) { onChange(share.copy(saved = it)) }
        ToggleRow(R.string.friends_share_streak, share.streak, enabled) { onChange(share.copy(streak = it)) }
        ToggleRow(R.string.friends_share_pauses, share.pauses, enabled) { onChange(share.copy(pauses = it)) }
        Text(
            stringResource(R.string.friends_share_reciprocity),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardContent(uiState: FriendsUiState, viewModel: FriendsViewModel) {
    val windows = listOf(
        BoardWindow.DAY to R.string.time_saved_day,
        BoardWindow.WEEK to R.string.time_saved_week,
        BoardWindow.MONTH to R.string.time_saved_month,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        windows.forEachIndexed { index, (window, labelRes) ->
            SegmentedButton(
                selected = uiState.window == window,
                onClick = { viewModel.selectWindow(window) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = windows.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.surface,
                    activeContentColor = MaterialTheme.colorScheme.secondary,
                    activeBorderColor = MaterialTheme.colorScheme.secondary,
                ),
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
    when (uiState.boardStatus) {
        BoardStatus.OFFLINE -> Text(stringResource(R.string.friends_offline), color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoardStatus.FAILED -> {
            Text(stringResource(R.string.friends_board_failed))
            OutlinedButton(onClick = viewModel::refresh) { Text(stringResource(R.string.friends_retry)) }
        }
        else -> Unit
    }
    uiState.rows.forEach { row ->
        BoardRowItem(row, uiState.nowMillis)
        HorizontalDivider()
    }
}

@Composable
private fun BoardRowItem(row: BoardRow, nowMillis: Long) {
    val hidden = stringResource(R.string.friends_hidden)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.rank?.toString() ?: hidden,
                style = MaterialTheme.typography.titleMedium.mono(),
                modifier = Modifier.width(32.dp),
            )
            Text(
                if (row.isMe) stringResource(R.string.friends_you, row.name) else row.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                row.savedMillis?.let(::formatDuration) ?: hidden,
                style = MaterialTheme.typography.titleMedium.mono(),
            )
        }
        val details = buildList {
            if (row.streakVisible) {
                add(
                    row.streakStartedAt?.let { stringResource(R.string.friends_row_streak, formatDuration(nowMillis - it)) }
                        ?: stringResource(R.string.friends_row_streak_off),
                )
            }
            row.bypassCount?.let { add(stringResource(R.string.friends_row_bypasses, it)) }
            row.unlockCount?.let { add(stringResource(R.string.friends_row_unlocks, it)) }
            add(
                row.lastSyncAt?.let {
                    stringResource(
                        R.string.friends_row_synced,
                        DateUtils.getRelativeTimeSpanString(it, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString(),
                    )
                } ?: stringResource(R.string.friends_row_never_synced),
            )
        }
        Text(
            details.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall.mono(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp),
        )
    }
}
