package com.monolith.app.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.R
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.usecase.RestoreBackupUseCase
import com.monolith.app.domain.usecase.RestoreOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RestoreStep {
    /** Typing the code; [error] is why the last try didn't go through. */
    data class Entry(@StringRes val error: Int? = null) : RestoreStep

    /** A readable backup, waiting for the member to agree to replace this phone's data. */
    data class Confirm(val backupAt: Long?) : RestoreStep

    data class Finished(@StringRes val message: Int) : RestoreStep
}

data class RestoreUiState(val step: RestoreStep = RestoreStep.Entry(), val busy: Boolean = false)

/** The restore dialog, shared by Settings and the first-use Friends screen. */
@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val restoreBackup: RestoreBackupUseCase,
) : ViewModel() {

    private val state = MutableStateFlow(RestoreUiState())
    val uiState: StateFlow<RestoreUiState> = state.asStateFlow()

    fun reset() = state.update { RestoreUiState() }

    fun submit(code: String) = run {
        when (val outcome = restoreBackup.prepare(code)) {
            // Nothing to replace: restoring the identity alone can't lose anything, so no question.
            is RestoreOutcome.Ready -> if (outcome.hasBackup) RestoreStep.Confirm(outcome.backupAt) else finish(restoreBackup.confirm())
            else -> finish(outcome)
        }
    }

    fun confirm() = run { finish(restoreBackup.confirm()) }

    private fun finish(outcome: RestoreOutcome): RestoreStep = when (outcome) {
        RestoreOutcome.Done -> RestoreStep.Finished(
            if (state.value.step is RestoreStep.Confirm) R.string.backup_restore_done else R.string.backup_restore_none,
        )
        RestoreOutcome.Refused -> RestoreStep.Entry(R.string.backup_restore_refused)
        is RestoreOutcome.Failed -> RestoreStep.Entry(errorText(outcome.error))
        is RestoreOutcome.Ready -> RestoreStep.Entry(R.string.friends_error_generic)
    }

    private fun run(action: suspend () -> RestoreStep) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            val step = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RestoreStep.Entry(R.string.friends_error_generic)
            }
            state.update { RestoreUiState(step = step) }
        }
    }

    private companion object {
        @StringRes
        fun errorText(error: LeaderboardError): Int = when (error) {
            LeaderboardError.UNAUTHORIZED -> R.string.friends_error_recovery
            LeaderboardError.INVALID -> R.string.backup_restore_unreadable
            LeaderboardError.NETWORK -> R.string.friends_error_network
            else -> R.string.friends_error_generic
        }
    }
}
