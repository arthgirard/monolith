package com.monolith.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.domain.usecase.DeleteBlockScheduleUseCase
import com.monolith.app.domain.usecase.ObserveBlockSchedulesUseCase
import com.monolith.app.domain.usecase.SaveBlockScheduleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

data class ScheduleUiState(
    val schedules: List<BlockSchedule> = emptyList(),
)

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    observeSchedules: ObserveBlockSchedulesUseCase,
    private val saveSchedule: SaveBlockScheduleUseCase,
    private val deleteSchedule: DeleteBlockScheduleUseCase,
) : ViewModel() {

    val uiState: StateFlow<ScheduleUiState> = observeSchedules()
        .map { ScheduleUiState(schedules = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

    /**
     * [existing] null creates a rule; non-null replaces it, keeping both its identity and its
     * on/off state -- editing the time of a rule the user had switched off must not switch it
     * back on behind their back.
     */
    fun save(existing: BlockSchedule?, days: Set<DayOfWeek>, startTime: LocalTime) {
        if (days.isEmpty()) return
        viewModelScope.launch {
            saveSchedule(
                BlockSchedule(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    enabled = existing?.enabled ?: true,
                    days = days,
                    startTime = startTime,
                ),
            )
        }
    }

    fun setEnabled(schedule: BlockSchedule, enabled: Boolean) {
        viewModelScope.launch { saveSchedule(schedule.copy(enabled = enabled)) }
    }

    fun delete(schedule: BlockSchedule) {
        viewModelScope.launch { deleteSchedule(schedule.id) }
    }
}
