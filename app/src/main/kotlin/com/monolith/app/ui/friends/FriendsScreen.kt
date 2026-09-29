package com.monolith.app.ui.friends

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.DISPLAY_NAME_MAX_LENGTH
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.model.isValidDisplayName
import com.monolith.app.domain.usecase.groupLabels
import com.monolith.app.ui.components.SettingsDivider
import com.monolith.app.ui.components.SettingsGroup
import com.monolith.app.ui.components.SettingsToggleRow
import com.monolith.app.ui.settings.RestoreDialog
import com.monolith.app.ui.theme.mono
import com.monolith.app.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val identity = uiState.identity
    val group = uiState.selectedGroup

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.friends_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    if (group != null) {
                        IconButton(onClick = { viewModel.openSheet(FriendsSheet.SETTINGS) }) {
                            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.friends_group_settings))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (group != null) {
                GroupSwitcher(
                    groups = uiState.groups,
                    selectedGroupId = group.id,
                    onSelect = viewModel::selectGroup,
                    onAdd = { viewModel.openSheet(FriendsSheet.ADD) },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // While a sheet is open it shows the message itself, right where the action was.
                if (uiState.sheet == null) {
                    uiState.message?.let { message ->
                        Text(stringResource(message.text), color = MaterialTheme.colorScheme.error)
                    }
                }
                when {
                    !uiState.loaded -> Unit
                    group == null -> JoinContent(uiState.busy, uiState.displayName, viewModel)
                    else -> BoardContent(uiState, viewModel)
                }
            }
        }
    }

    when (uiState.sheet) {
        FriendsSheet.SETTINGS -> if (identity != null && group != null) {
            GroupSheet(
                identity = identity,
                group = group,
                busy = uiState.busy,
                message = uiState.message,
                onRenameGroup = viewModel::renameGroup,
                onShareChange = viewModel::updateShare,
                onLeave = viewModel::leaveSelectedGroup,
                onDismiss = viewModel::closeSheet,
            )
        }
        FriendsSheet.ADD -> AddGroupSheet(
            busy = uiState.busy,
            message = uiState.message,
            onCreate = { viewModel.create(ShareAll) },
            onJoin = { code -> viewModel.join(code, ShareAll) },
            onDismiss = viewModel::closeSheet,
        )
        null -> Unit
    }
}

/** One chip per group, the selected one filled, then the way to another. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSwitcher(
    groups: List<GroupInfo>,
    selectedGroupId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val labels = groupLabels(groups)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.id }) { group ->
            val label = labels.getValue(group.id)
            FilterChip(
                selected = group.id == selectedGroupId,
                onClick = { onSelect(group.id) },
                label = {
                    Text(
                        label.text,
                        // A group that is still just its invite code shows it as the data it is.
                        style = if (label.isCode) LocalTextStyle.current.mono() else LocalTextStyle.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                },
            )
        }
        item(key = "add") {
            AssistChip(
                onClick = onAdd,
                label = { Text(stringResource(R.string.friends_add_group)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                },
            )
        }
    }
}

/** Creating or joining shares everything; what a group sees is adjusted afterwards in its settings. */
internal val ShareAll = ShareSettings(saved = true, streak = true, pauses = true)

@Composable
private fun JoinContent(busy: Boolean, knownName: String, viewModel: FriendsViewModel) {
    // Setup asks for the name. Only an install set up before it did has none, and asks here.
    val askName = knownName.isEmpty()
    var name by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var showRestore by rememberSaveable { mutableStateOf(false) }
    val nameValid = !askName || isValidDisplayName(name)
    val newName = name.takeIf { askName }

    Text(stringResource(R.string.friends_intro), style = MaterialTheme.typography.bodyLarge)
    if (askName) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(DISPLAY_NAME_MAX_LENGTH) },
            label = { Text(stringResource(R.string.friends_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it.take(12) },
            label = { Text(stringResource(R.string.friends_invite_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { viewModel.join(inviteCode, ShareAll, newName) }, enabled = nameValid && inviteCode.isNotBlank() && !busy) {
            Text(stringResource(R.string.friends_join))
        }
    }
    HorizontalDivider()
    Button(onClick = { viewModel.create(ShareAll, newName) }, enabled = nameValid && !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.friends_create))
    }
    // The same restore as Settings: a code with a backup brings back history and setup too.
    TextButton(onClick = { showRestore = true }) { Text(stringResource(R.string.friends_restore)) }
    if (showRestore) RestoreDialog(onDismiss = { showRestore = false })
}

@Composable
internal fun ShareToggles(share: ShareSettings, enabled: Boolean, onChange: (ShareSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup {
            SettingsToggleRow(stringResource(R.string.friends_share_saved), share.saved, { onChange(share.copy(saved = it)) }, enabled)
            SettingsDivider(startInset = 20.dp)
            SettingsToggleRow(stringResource(R.string.friends_share_streak), share.streak, { onChange(share.copy(streak = it)) }, enabled)
            SettingsDivider(startInset = 20.dp)
            SettingsToggleRow(stringResource(R.string.friends_share_pauses), share.pauses, { onChange(share.copy(pauses = it)) }, enabled)
        }
        Text(
            stringResource(R.string.friends_share_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                // The border already marks the selection; a checkmark on top is decoration.
                icon = {},
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
    val leaderMillis = uiState.rows.mapNotNull { it.savedMillis }.maxOrNull()
    SettingsGroup {
        uiState.rows.forEachIndexed { index, row ->
            if (index > 0) SettingsDivider(startInset = 20.dp)
            BoardRowItem(row, leaderMillis, uiState.nowMillis)
        }
    }
}

private val RankWidth = 28.dp
private val SlabEdgeWidth = 3.dp

@Composable
private fun BoardRowItem(row: BoardRow, leaderMillis: Long?, nowMillis: Long) {
    val hidden = stringResource(R.string.friends_hidden)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The viewer's own row carries a slab edge rather than a suffix, so the name stays just the name.
    val edge = if (row.isMe) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val width = SlabEdgeWidth.toPx()
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                drawRect(edge, topLeft = Offset(x, 0f), size = Size(width, size.height))
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.rank?.toString() ?: hidden,
                style = MaterialTheme.typography.titleMedium.mono(),
                color = muted,
                modifier = Modifier.width(RankWidth),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showSyncAge(row.lastSyncAt, nowMillis)) {
                    Text(
                        row.lastSyncAt?.let { DateUtils.getRelativeTimeSpanString(it, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString() }
                            ?: stringResource(R.string.friends_row_never_synced),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                row.savedMillis?.let(::formatDuration) ?: hidden,
                style = MaterialTheme.typography.titleLarge.mono(),
            )
        }
        // Time saved as a share of the leader's, so the gaps read at a glance.
        val saved = row.savedMillis
        if (saved != null && leaderMillis != null && leaderMillis > 0 && saved > 0) {
            Box(
                modifier = Modifier
                    .padding(start = RankWidth)
                    .fillMaxWidth(saved.toFloat() / leaderMillis)
                    .height(2.dp)
                    .background(if (row.isMe) MaterialTheme.colorScheme.onSurface else muted.copy(alpha = 0.4f)),
            )
        }
        if (row.streakVisible || row.bypassCount != null || row.unlockCount != null) {
            Row(
                modifier = Modifier.padding(start = RankWidth),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (row.streakVisible) {
                    val started = row.streakStartedAt
                    MiniReadout(
                        Icons.Outlined.Timelapse,
                        stringResource(R.string.friends_row_streak_label),
                        if (started != null) formatDuration((nowMillis - started).coerceAtLeast(0)) else hidden,
                    )
                }
                row.bypassCount?.let { MiniReadout(Icons.Outlined.LockOpen, stringResource(R.string.friends_row_bypasses_label), it.toString()) }
                row.unlockCount?.let { MiniReadout(Icons.Outlined.PhoneAndroid, stringResource(R.string.friends_row_unlocks_label), it.toString()) }
            }
        }
    }
}

/** A small muted glyph names the readout; the caption is still what a screen reader says. */
@Composable
private fun MiniReadout(icon: ImageVector, caption: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = caption, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.mono())
    }
}
