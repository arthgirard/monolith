package com.monolith.app.ui.friends

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DISPLAY_NAME_MAX_LENGTH
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.model.isValidDisplayName
import com.monolith.app.domain.usecase.groupLabels
import com.monolith.app.ui.components.MonolithSnackbarHost
import com.monolith.app.ui.components.SettingsDivider
import com.monolith.app.ui.components.SettingsGroup
import com.monolith.app.ui.components.SettingsToggleRow
import com.monolith.app.ui.settings.RestoreDialog
import com.monolith.app.ui.theme.mono
import com.monolith.app.util.appLocale
import com.monolith.app.util.formatDuration
import com.monolith.app.util.formatRelativeTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val MIN_REFRESH_SPIN_MILLIS = 800L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val identity = uiState.identity
    val group = uiState.selectedGroup
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // On the board a message is a passing notice. The join screen keeps it inline, next to the
    // fields it is about, and a removal stays up there until the member acts.
    val onBoard = group != null && uiState.sheet == null
    val message = uiState.message
    val messageText = message?.let { stringResource(it.text) }
    LaunchedEffect(message, onBoard) {
        if (onBoard && messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.dismissMessage()
        }
    }
    val offline = uiState.boardStatus == BoardStatus.OFFLINE
    val offlineText = stringResource(R.string.friends_offline)
    LaunchedEffect(offline) {
        if (offline) snackbarHostState.showSnackbar(offlineText)
    }

    // The state is keyed on the enabled lambda: a fresh one each recomposition would rebuild it,
    // and the recomposition a release triggers would drop the refresh it just started.
    val onBoardNow by rememberUpdatedState(group != null)
    val pullEnabled = remember { { onBoardNow } }
    val pull = rememberPullToRefreshState(enabled = pullEnabled)
    if (pull.isRefreshing) {
        LaunchedEffect(Unit) {
            // The board answers in a blink: without a floor the spinner snaps away before it
            // reads as a refresh, and an unchanged board then looks like nothing happened.
            val started = SystemClock.uptimeMillis()
            viewModel.refresh().join()
            delay(MIN_REFRESH_SPIN_MILLIS - (SystemClock.uptimeMillis() - started))
            pull.endRefresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.friends_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    if (group != null) {
                        IconButton(onClick = { shareInvite(context, group.inviteCode) }) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.friends_invite_share))
                        }
                        IconButton(onClick = { viewModel.openSheet(FriendsSheet.SETTINGS) }) {
                            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.friends_group_settings))
                        }
                    }
                },
            )
        },
        snackbarHost = { MonolithSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (group != null) {
                GroupSwitcher(
                    groups = uiState.groups,
                    selectedGroupId = group.id,
                    onSelect = viewModel::selectGroup,
                    onAdd = { viewModel.openSheet(FriendsSheet.ADD) },
                )
                // Held above the scroll so the period every number covers stays in sight.
                WindowSelector(
                    selected = uiState.window,
                    onSelect = viewModel::selectWindow,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .nestedScroll(pull.nestedScrollConnection),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    when {
                        !uiState.loaded -> Unit
                        group == null -> {
                            // While a sheet is open it shows the message itself, right where the action was.
                            if (uiState.sheet == null && messageText != null) {
                                Text(messageText, color = MaterialTheme.colorScheme.error)
                            }
                            JoinContent(uiState.busy, uiState.displayName, viewModel)
                        }
                        else -> BoardContent(uiState, group, viewModel, onOpenApps = { row -> viewModel.showApps(row); onOpenApps() })
                    }
                }
                val eased by rememberEasedPullOffset(pull)
                val thresholdPx = with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }
                PullToRefreshContainer(
                    state = pull,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            // The container places itself at the raw offset; shift it onto the eased one.
                            translationY = eased - pull.verticalOffset
                            alpha = (eased / thresholdPx).coerceIn(0f, 1f)
                        },
                )
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
internal val ShareAll = ShareSettings(saved = true, streak = true, pauses = true, apps = true)

@Composable
private fun JoinContent(busy: Boolean, knownName: String, viewModel: FriendsViewModel) {
    // Setup asks for the name. Only an install set up before it did has none, and asks here.
    val askName = knownName.isEmpty()
    var name by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var showRestore by rememberSaveable { mutableStateOf(false) }
    val nameValid = !askName || isValidDisplayName(name)
    val newName = name.takeIf { askName }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.friends_intro), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.friends_intro_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            SettingsDivider(startInset = 20.dp)
            SettingsToggleRow(stringResource(R.string.friends_share_apps), share.apps, { onChange(share.copy(apps = it)) }, enabled)
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
private fun WindowSelector(selected: BoardWindow, onSelect: (BoardWindow) -> Unit, modifier: Modifier = Modifier) {
    val windows = listOf(
        BoardWindow.DAY to R.string.time_saved_day,
        BoardWindow.WEEK to R.string.time_saved_week,
        BoardWindow.MONTH to R.string.time_saved_month,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        windows.forEachIndexed { index, (window, labelRes) ->
            SegmentedButton(
                selected = selected == window,
                onClick = { onSelect(window) },
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
}

@Composable
private fun BoardContent(uiState: FriendsUiState, group: GroupInfo, viewModel: FriendsViewModel, onOpenApps: (BoardRow) -> Unit) {
    if (uiState.boardStatus == BoardStatus.FAILED) {
        Text(stringResource(R.string.friends_board_failed))
        OutlinedButton(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.friends_retry)) }
    }
    if (group.memberCount <= 1) AloneCard(group.inviteCode)
    val leaderMillis = uiState.rows.mapNotNull { it.savedMillis }.maxOrNull()
    if (uiState.rows.isNotEmpty()) {
        SettingsGroup {
            uiState.rows.forEachIndexed { index, row ->
                if (index > 0) SettingsDivider(startInset = 20.dp)
                BoardRowItem(row, leaderMillis, uiState.nowMillis, uiState.window, onOpenApps = { onOpenApps(row) })
            }
        }
    }
}

/** A group of one has no board to speak of yet: the invite is the next step, so it leads. */
@Composable
private fun AloneCard(inviteCode: String) {
    val context = LocalContext.current
    val copier = rememberInviteCopier()
    SettingsGroup {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.friends_alone_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.friends_alone_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.friends_invite_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(inviteCode, style = MaterialTheme.typography.headlineMedium.mono())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { copier.copy(inviteCode) }, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (copier.copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(if (copier.copied) R.string.friends_copied else R.string.friends_copy))
                }
                Button(onClick = { shareInvite(context, inviteCode) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.friends_share))
                }
            }
        }
    }
}

private val RankWidth = 28.dp
private val SlabEdgeWidth = 3.dp
private val LeaderBarHeight = 4.dp

@Composable
private fun BoardRowItem(row: BoardRow, leaderMillis: Long?, nowMillis: Long, window: BoardWindow, onOpenApps: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The viewer's own row carries a slab edge and a heavier name rather than a suffix,
    // so the name stays just the name.
    val edge = if (row.isMe) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val sharesNothing = sharesNoStats(row)
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
            // A hidden rank leaves its column empty: the names still line up, and nothing reads as a value.
            Text(
                row.rank?.toString().orEmpty(),
                style = MaterialTheme.typography.titleMedium.mono(),
                color = muted,
                modifier = Modifier.width(RankWidth),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (row.isMe) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sharesNothing) {
                    Text(stringResource(R.string.friends_row_not_sharing), style = MaterialTheme.typography.labelSmall, color = muted)
                } else if (showSyncAge(row.lastSyncAt, nowMillis)) {
                    Text(
                        row.lastSyncAt?.let { formatRelativeTime(it, nowMillis, appLocale()) }
                            ?: stringResource(R.string.friends_row_never_synced),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
            row.savedMillis?.let { saved ->
                Spacer(Modifier.width(12.dp))
                Text(formatDuration(saved), style = MaterialTheme.typography.titleLarge.mono())
            }
        }
        // Time saved as a share of the leader's, so the gaps read at a glance.
        val saved = row.savedMillis
        if (saved != null && leaderMillis != null && leaderMillis > 0 && saved > 0) {
            Box(
                modifier = Modifier
                    .padding(start = RankWidth)
                    .fillMaxWidth(saved.toFloat() / leaderMillis)
                    .height(LeaderBarHeight)
                    .clip(RoundedCornerShape(LeaderBarHeight / 2))
                    .background(if (row.isMe) MaterialTheme.colorScheme.onSurface else muted.copy(alpha = 0.55f)),
            )
        }
        if (row.streakVisible || row.bypassCount != null || row.unlockCount != null) {
            Row(
                modifier = Modifier.padding(start = RankWidth),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (row.streakVisible) {
                    // Shared but not enforcing right now: a dash, since there is no streak to time.
                    val started = row.streakStartedAt
                    MiniReadout(
                        Icons.Outlined.Timelapse,
                        stringResource(R.string.friends_tip_streak),
                        if (started != null) formatDuration((nowMillis - started).coerceAtLeast(0)) else stringResource(R.string.friends_hidden),
                    )
                }
                row.bypassCount?.let { MiniReadout(Icons.Outlined.LockOpen, stringResource(bypassesTip(window)), it.toString()) }
                row.unlockCount?.let { MiniReadout(Icons.Outlined.Apps, stringResource(unlocksTip(window)), it.toString()) }
            }
        }
        row.blockedApps?.let { apps ->
            TextButton(
                onClick = onOpenApps,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                modifier = Modifier.padding(start = RankWidth).heightIn(min = 32.dp),
            ) {
                Icon(Icons.Outlined.Block, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.friends_row_apps), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text(apps.size.toString(), style = MaterialTheme.typography.bodyMedium.mono())
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Material 1.2 jumps the indicator when a refresh starts (an overshot pull snaps back to the
 * threshold) and when it ends (gone at once). Follow the finger exactly, ease only those jumps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberEasedPullOffset(state: PullToRefreshState): State<Float> {
    val eased = remember { Animatable(0f) }
    LaunchedEffect(state) {
        var wasRefreshing = state.isRefreshing
        snapshotFlow { state.isRefreshing to state.verticalOffset }.collectLatest { (refreshing, target) ->
            if (refreshing != wasRefreshing) {
                wasRefreshing = refreshing
                eased.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
            } else {
                eased.snapTo(target)
            }
        }
    }
    return eased.asState()
}

private fun bypassesTip(window: BoardWindow): Int = when (window) {
    BoardWindow.DAY -> R.string.friends_tip_bypasses_day
    BoardWindow.WEEK -> R.string.friends_tip_bypasses_week
    BoardWindow.MONTH -> R.string.friends_tip_bypasses_month
}

private fun unlocksTip(window: BoardWindow): Int = when (window) {
    BoardWindow.DAY -> R.string.friends_tip_unlocks_day
    BoardWindow.WEEK -> R.string.friends_tip_unlocks_week
    BoardWindow.MONTH -> R.string.friends_tip_unlocks_month
}

/**
 * A small muted glyph names the readout; a long press spells it out, period included. The same
 * words are what a screen reader says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniReadout(icon: ImageVector, caption: String, value: String) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(caption) } },
        state = rememberTooltipState(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = caption, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium.mono())
        }
    }
}
