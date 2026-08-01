package com.monolith.app.ui.timesaved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.domain.usecase.ObserveActiveSessionStartUseCase
import com.monolith.app.domain.usecase.ObserveBlockSessionsUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.TimeSavedCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class TimeSavedUiState(
    val periodType: TimePeriodType = TimePeriodType.DAY,
    val buckets: List<TimeSavedBucket> = emptyList(),
    val totalMillis: Long = 0L,
    val periodLabel: String = "",
    val canGoNext: Boolean = false,
)

@HiltViewModel
class TimeSavedViewModel @Inject constructor(
    observeBlockSessions: ObserveBlockSessionsUseCase,
    observeBlockState: ObserveBlockStateUseCase,
    observeActiveSessionStart: ObserveActiveSessionStartUseCase,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val periodType = MutableStateFlow(TimePeriodType.DAY)
    private val anchor = MutableStateFlow(LocalDate.now(zone))

    // Live sessions only need to advance every so often to keep "today"'s total moving;
    // no need for HomeViewModel's 1s cadence on a stats screen.
    private val ticker = MutableStateFlow(System.currentTimeMillis())

    val uiState: StateFlow<TimeSavedUiState> = combine(
        observeBlockSessions(),
        observeBlockState(),
        observeActiveSessionStart(),
        ticker,
        combine(periodType, anchor, ::Pair),
    ) { sessions, blockState, activeSessionStart, now, (type, anchorDate) ->
        val ongoing = if (blockState.isEnforcing(now) && activeSessionStart != null) {
            // A past bypass this cycle doesn't count toward time gained, even after it expires.
            val effectiveStart = maxOf(activeSessionStart, blockState.bypassExpiresAtMillis ?: activeSessionStart)
            BlockSession(effectiveStart, now)
        } else {
            null
        }
        val buckets = TimeSavedCalculator.bucketsFor(type, anchorDate, sessions, ongoing)
        val (_, periodEnd) = TimeSavedCalculator.periodRange(type, anchorDate)
        TimeSavedUiState(
            periodType = type,
            buckets = buckets,
            totalMillis = buckets.sumOf { it.durationMillis },
            periodLabel = labelFor(type, anchorDate),
            canGoNext = periodEnd <= now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimeSavedUiState())

    init {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                ticker.value = System.currentTimeMillis()
            }
        }
    }

    fun selectPeriodType(type: TimePeriodType) {
        periodType.value = type
        anchor.value = LocalDate.now(zone)
    }

    fun goToPrevious() {
        anchor.value = step(anchor.value, periodType.value, -1)
    }

    fun goToNext() {
        if (uiState.value.canGoNext) {
            anchor.value = step(anchor.value, periodType.value, 1)
        }
    }

    private fun step(date: LocalDate, type: TimePeriodType, amount: Long): LocalDate = when (type) {
        TimePeriodType.DAY -> date.plusDays(amount)
        TimePeriodType.WEEK -> date.plusWeeks(amount)
        TimePeriodType.MONTH -> date.plusMonths(amount)
        TimePeriodType.YEAR -> date.plusYears(amount)
    }

    private fun labelFor(type: TimePeriodType, date: LocalDate): String = when (type) {
        TimePeriodType.DAY -> if (date == LocalDate.now(zone)) {
            "Today"
        } else {
            date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        }
        TimePeriodType.WEEK -> {
            val weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekEnd = weekStart.plusDays(6)
            val formatter = DateTimeFormatter.ofPattern("MMM d")
            "${weekStart.format(formatter)} – ${weekEnd.format(formatter)}"
        }
        TimePeriodType.MONTH -> date.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        TimePeriodType.YEAR -> date.format(DateTimeFormatter.ofPattern("yyyy"))
    }
}
