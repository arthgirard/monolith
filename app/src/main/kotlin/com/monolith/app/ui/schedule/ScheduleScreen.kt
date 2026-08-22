package com.monolith.app.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.text.format.DateFormat
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.ui.theme.MonolithButtonShape
import com.monolith.app.util.formatScheduleDays
import com.monolith.app.util.formatScheduleTime
import com.monolith.app.util.weekOrder
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // null = closed. A Some(null) payload means "adding", Some(rule) means "editing that rule".
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<BlockSchedule?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = EditorTarget(null) },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.schedules_add_cta))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.schedules_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            if (uiState.schedules.isEmpty()) {
                Text(
                    stringResource(R.string.schedules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(uiState.schedules, key = { it.id }) { schedule ->
                    ScheduleRow(
                        schedule = schedule,
                        onClick = { editing = EditorTarget(schedule) },
                        onToggle = { viewModel.setEnabled(schedule, it) },
                        onDelete = { pendingDelete = schedule },
                    )
                }
            }
        }
    }

    editing?.let { target ->
        ScheduleEditorDialog(
            existing = target.schedule,
            onDismiss = { editing = null },
            onSave = { days, time ->
                viewModel.save(target.schedule, days, time)
                editing = null
            },
        )
    }

    pendingDelete?.let { schedule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.schedules_delete_cta)) },
            text = { Text(scheduleSummary(schedule)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(schedule)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.schedules_delete_cta))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Wrapper so "adding" (null rule) is distinguishable from "dialog closed" in a single state. */
private data class EditorTarget(val schedule: BlockSchedule?)

@Composable
private fun scheduleSummary(schedule: BlockSchedule): String {
    val days = formatScheduleDays(
        days = schedule.days,
        everyDayLabel = stringResource(R.string.schedules_every_day),
        weekdaysLabel = stringResource(R.string.schedules_weekdays),
        weekendsLabel = stringResource(R.string.schedules_weekends),
    )
    return "${formatScheduleTime(schedule.startTime)}  •  $days"
}

@Composable
private fun ScheduleRow(
    schedule: BlockSchedule,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val alpha = if (schedule.enabled) 1f else 0.4f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = formatScheduleTime(schedule.startTime),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                text = formatScheduleDays(
                    days = schedule.days,
                    everyDayLabel = stringResource(R.string.schedules_every_day),
                    weekdaysLabel = stringResource(R.string.schedules_weekdays),
                    weekendsLabel = stringResource(R.string.schedules_weekends),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Switch(
            checked = schedule.enabled,
            onCheckedChange = onToggle,
            // Material's defaults pull the unset primaryContainer role, which resolves to the
            // baseline purple. The app's accent is amber, and it lives on `secondary`.
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                checkedTrackColor = MaterialTheme.colorScheme.secondary,
                checkedBorderColor = MaterialTheme.colorScheme.secondary,
            ),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.schedules_delete_cta))
        }
    }
}

@Composable
private fun DayChips(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    val locale = Locale.getDefault()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        weekOrder(locale).forEach { day ->
            val isSelected = day in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(day) },
                label = {
                    Text(
                        day.getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondary,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    selectedBorderColor = MaterialTheme.colorScheme.secondary,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditorDialog(
    existing: BlockSchedule?,
    onDismiss: () -> Unit,
    onSave: (Set<DayOfWeek>, LocalTime) -> Unit,
) {
    val initialTime = existing?.startTime ?: LocalTime.of(22, 0)
    var days by remember { mutableStateOf(existing?.days ?: emptySet()) }
    val timeState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.schedules_new_title else R.string.schedules_edit_title,
                ),
            )
        },
        text = {
            // The dial is tall; on a short screen the chips above it would otherwise be cut off
            // with no way to reach them.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.schedules_days_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DayChips(
                    selected = days,
                    onToggle = { day -> days = if (day in days) days - day else days + day },
                )
                if (days.isEmpty()) {
                    Text(
                        stringResource(R.string.schedules_no_days),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Every one of these roles is unset in MonolithTheme, so left alone the dial comes
                // out in Material's baseline purple. Amber for what's selected, surface tones for
                // the rest, matching the chips above it.
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectorColor = MaterialTheme.colorScheme.secondary,
                        clockDialSelectedContentColor = MaterialTheme.colorScheme.onSecondary,
                        clockDialUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        periodSelectorSelectedContainerColor = MaterialTheme.colorScheme.secondary,
                        periodSelectorSelectedContentColor = MaterialTheme.colorScheme.onSecondary,
                        periodSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surface,
                        periodSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        periodSelectorBorderColor = MaterialTheme.colorScheme.outline,
                        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.secondary,
                        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onSecondary,
                        timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        timeSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                shape = MonolithButtonShape,
                enabled = days.isNotEmpty(),
                onClick = { onSave(days, LocalTime.of(timeState.hour, timeState.minute)) },
            ) {
                Text(stringResource(R.string.schedules_save_cta))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
