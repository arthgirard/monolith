package com.monolith.app.ui.timesaved

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.util.formatDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSavedScreen(
    onBack: () -> Unit,
    viewModel: TimeSavedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.time_saved_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val periods = listOf(
                TimePeriodType.DAY to R.string.time_saved_day,
                TimePeriodType.WEEK to R.string.time_saved_week,
                TimePeriodType.MONTH to R.string.time_saved_month,
                TimePeriodType.YEAR to R.string.time_saved_year,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                periods.forEachIndexed { index, (type, labelRes) ->
                    SegmentedButton(
                        selected = uiState.periodType == type,
                        onClick = { viewModel.selectPeriodType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.surface,
                            activeContentColor = MaterialTheme.colorScheme.secondary,
                            activeBorderColor = MaterialTheme.colorScheme.secondary,
                        ),
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::goToPrevious) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.time_saved_previous))
                }
                Text(uiState.periodLabel, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = viewModel::goToNext, enabled = uiState.canGoNext) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.time_saved_next))
                }
            }

            Column {
                Text(
                    stringResource(R.string.time_saved_total_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatDuration(uiState.totalMillis), style = MaterialTheme.typography.displaySmall)
            }

            Column {
                Text(
                    stringResource(R.string.time_saved_record_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatDuration(uiState.personalRecordMillis), style = MaterialTheme.typography.titleLarge)
            }

            if (uiState.totalMillis == 0L) {
                Text(
                    stringResource(R.string.time_saved_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TimeSavedBarChart(buckets = uiState.buckets, periodType = uiState.periodType)
            }
        }
    }
}

@Composable
fun TimeSavedBarChart(
    buckets: List<TimeSavedBucket>,
    periodType: TimePeriodType,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 160.dp,
    showLabels: Boolean = true,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            val barCount = buckets.size
            if (barCount == 0) return@Canvas
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (barCount - 1)) / barCount
            buckets.forEachIndexed { index, bucket ->
                val capacity = bucket.capacityMillis.coerceAtLeast(1L).toFloat()
                val fraction = (bucket.durationMillis.toFloat() / capacity).coerceIn(0f, 1f)
                val barHeight = size.height * fraction
                val left = index * (barWidth + gap)
                drawRect(color = trackColor, topLeft = Offset(left, 0f), size = Size(barWidth, size.height))
                if (barHeight > 0f) {
                    drawRect(
                        color = barColor,
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                }
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                buckets.forEachIndexed { index, bucket ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        bucketLabel(periodType, index, bucket.bucketStartMillis)?.let { label ->
                            Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
                        }
                    }
                }
            }
        }
    }
}

private val zone: ZoneId = ZoneId.systemDefault()

private fun bucketLabel(periodType: TimePeriodType, index: Int, bucketStartMillis: Long): String? {
    val date = Instant.ofEpochMilli(bucketStartMillis).atZone(zone)
    return when (periodType) {
        TimePeriodType.DAY -> if (index % 6 == 0) {
            when (val hour = date.hour) {
                0 -> "12a"
                12 -> "12p"
                in 1..11 -> "${hour}a"
                else -> "${hour - 12}p"
            }
        } else {
            null
        }
        TimePeriodType.WEEK -> date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        TimePeriodType.MONTH -> {
            val day = date.dayOfMonth
            if (day == 1 || day % 5 == 0) day.toString() else null
        }
        TimePeriodType.YEAR -> date.month.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    }
}
