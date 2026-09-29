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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.ui.theme.MonolithMonoFamily
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
            // While the sheet is open it shows the message itself, right where the action was.
            if (!showSheet) {
                uiState.message?.let { message ->
                    Text(stringResource(message.text), color = MaterialTheme.colorScheme.error)
                }
            }
            when {
                !uiState.loaded -> Unit
                uiState.membership == null -> JoinContent(uiState.busy, viewModel)
                else -> BoardContent(uiState, viewModel)
            }
        }
    }

    val membership = uiState.membership
    LaunchedEffect(membership == null) { if (membership == null) showSheet = false }
    if (showSheet && membership != null) {
        GroupSheet(
            membership = membership,
            busy = uiState.busy,
            message = uiState.message,
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
    // Saveable one by one: a rotation must not quietly turn back on a signal the member hid.
    var shareSaved by rememberSaveable { mutableStateOf(true) }
    var shareStreak by rememberSaveable { mutableStateOf(true) }
    var sharePauses by rememberSaveable { mutableStateOf(true) }
    val share = ShareSettings(saved = shareSaved, streak = shareStreak, pauses = sharePauses)
    val nameValid = name.trim().length in 1..24

    Text(stringResource(R.string.friends_intro), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(24) },
        label = { Text(stringResource(R.string.friends_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ShareToggles(share, enabled = !busy, onChange = {
        shareSaved = it.saved
        shareStreak = it.streak
        sharePauses = it.pauses
    })
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
            // The code is the account: keep it out of keyboard dictionaries and suggestions.
            keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
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
        val detailStyle = MaterialTheme.typography.bodySmall
        val streakLabel = stringResource(R.string.friends_row_streak_label)
        val streakOff = stringResource(R.string.friends_row_streak_off)
        val bypassesLabel = stringResource(R.string.friends_row_bypasses_label)
        val unlocksLabel = stringResource(R.string.friends_row_unlocks_label)
        val syncedLabel = stringResource(R.string.friends_row_synced_label)
        val neverSynced = stringResource(R.string.friends_row_never_synced)
        val monoValue = SpanStyle(fontFamily = MonolithMonoFamily)
        // Labels stay in the body face; only the figures are set in mono.
        val details = buildAnnotatedString {
            fun part(label: String, value: String?) {
                if (length > 0) append("  \u00B7  ")
                append(label)
                if (value != null) {
                    append(" ")
                    withStyle(monoValue) { append(value) }
                }
            }
            if (row.streakVisible) {
                val started = row.streakStartedAt
                if (started != null) part(streakLabel, formatDuration((nowMillis - started).coerceAtLeast(0))) else part(streakOff, null)
            }
            row.bypassCount?.let { part(bypassesLabel, it.toString()) }
            row.unlockCount?.let { part(unlocksLabel, it.toString()) }
            val synced = row.lastSyncAt
            if (synced != null) {
                part(syncedLabel, DateUtils.getRelativeTimeSpanString(synced, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString())
            } else {
                part(neverSynced, null)
            }
        }
        Text(
            details,
            style = detailStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp),
        )
    }
}
