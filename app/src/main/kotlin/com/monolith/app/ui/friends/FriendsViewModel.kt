package com.monolith.app.ui.friends

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.R
import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.GroupMembership
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.repository.LeaderboardRepository
import com.monolith.app.service.LeaderboardSyncer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class FriendsMessage(@StringRes val text: Int) {
    NETWORK(R.string.friends_error_network),
    INVITE_NOT_FOUND(R.string.friends_error_invite),
    GROUP_FULL(R.string.friends_error_full),
    BAD_RECOVERY(R.string.friends_error_recovery),
    REMOVED(R.string.friends_removed),
    GENERIC(R.string.friends_error_generic);

    companion object {
        fun of(error: LeaderboardError): FriendsMessage = when (error) {
            LeaderboardError.NETWORK -> NETWORK
            LeaderboardError.INVITE_NOT_FOUND -> INVITE_NOT_FOUND
            LeaderboardError.GROUP_FULL -> GROUP_FULL
            LeaderboardError.UNAUTHORIZED -> REMOVED
            else -> GENERIC
        }

        /** A restore that isn't recognized is a mistyped code; the member was never "removed". */
        fun ofRestore(error: LeaderboardError): FriendsMessage =
            if (error == LeaderboardError.UNAUTHORIZED) BAD_RECOVERY else of(error)
    }
}

enum class BoardStatus { LOADING, READY, OFFLINE, FAILED }

data class FriendsUiState(
    val loaded: Boolean = false,
    val membership: GroupMembership? = null,
    val window: BoardWindow = BoardWindow.WEEK,
    val rows: List<BoardRow> = emptyList(),
    val boardStatus: BoardStatus = BoardStatus.LOADING,
    val busy: Boolean = false,
    val message: FriendsMessage? = null,
    val nowMillis: Long = System.currentTimeMillis(),
)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val repository: LeaderboardRepository,
    private val syncer: LeaderboardSyncer,
) : ViewModel() {

    private val state = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = state.asStateFlow()

    private var boardJob: Job? = null

    init {
        viewModelScope.launch {
            var token: String? = null
            repository.observeMembership().collect { membership ->
                // Renames and share changes update the sheet in place; only a different
                // membership (join, restore, leave, removal) resets the board.
                val switched = membership?.token != token
                token = membership?.token
                val answersRemoval = switched && membership != null
                state.update {
                    it.copy(
                        loaded = true,
                        membership = membership,
                        rows = if (switched) emptyList() else it.rows,
                        message = if (answersRemoval && it.message == FriendsMessage.REMOVED) null else it.message,
                    )
                }
                if (switched && membership != null) {
                    // The board shows this member's own row: upload it now instead of after the
                    // debounce. The board fetches again once that lands (lastSyncedAt below).
                    syncer.requestSync(immediate = true)
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            // The background sync usually meets the 401 first, so a removal arrives as a notice.
            // Shown once: the message stays up until the member acts, then the notice is spent.
            repository.observeRemovedNotice().distinctUntilChanged().collect { removed ->
                if (removed) {
                    state.update { it.copy(message = FriendsMessage.REMOVED) }
                    repository.dismissRemovedNotice()
                }
            }
        }
        viewModelScope.launch {
            // The current value predates this screen; the first refresh() already covers it.
            syncer.lastSyncedAt.drop(1).filterNotNull().collect {
                if (state.value.membership != null) refresh()
            }
        }
        viewModelScope.launch {
            // Streaks tick on the board without syncing; once a second is enough for minutes.
            while (true) {
                delay(1_000)
                state.update { it.copy(nowMillis = System.currentTimeMillis()) }
            }
        }
    }

    fun selectWindow(window: BoardWindow) {
        state.update { it.copy(window = window) }
        refresh()
    }

    fun refresh() {
        // Only the latest request may land: a slow day board must not fill the week tab.
        boardJob?.cancel()
        boardJob = viewModelScope.launch {
            state.update { it.copy(boardStatus = if (it.rows.isEmpty()) BoardStatus.LOADING else it.boardStatus) }
            val window = state.value.window
            val token = state.value.membership?.token
            val result = repository.board(window, LocalDate.now())
            // The blocking HTTP call can't be cancelled, so check what it was asked for as well.
            if (state.value.window != window || state.value.membership?.token != token) return@launch
            when (result) {
                is LeaderboardResult.Ok -> state.update { it.copy(rows = result.value, boardStatus = BoardStatus.READY) }
                is LeaderboardResult.Err -> state.update {
                    when {
                        // The repository cleared the membership and left the removal notice.
                        result.error == LeaderboardError.UNAUTHORIZED -> it
                        it.rows.isNotEmpty() -> it.copy(boardStatus = BoardStatus.OFFLINE)
                        else -> it.copy(boardStatus = BoardStatus.FAILED)
                    }
                }
            }
        }
    }

    fun create(displayName: String, share: ShareSettings) = submit { repository.createGroup(displayName, share) }

    fun join(inviteCode: String, displayName: String, share: ShareSettings) =
        submit { repository.joinGroup(inviteCode, displayName, share) }

    fun restore(recoveryCode: String) = submit(FriendsMessage::ofRestore) { repository.restore(recoveryCode) }

    fun rename(displayName: String) = submit { repository.updateProfile(displayName, null) }

    fun updateShare(share: ShareSettings) = submit {
        repository.updateProfile(null, share).also {
            if (it is LeaderboardResult.Ok) {
                // Turning a signal back on needs an upload (the server nulled it when it was
                // hidden); the board fetches again when it lands. A hidden one shows right away.
                syncer.requestSync(immediate = true)
                refresh()
            }
        }
    }

    fun leave() = submit { repository.leave() }

    fun dismissMessage() = state.update { it.copy(message = null) }

    private fun submit(
        toMessage: (LeaderboardError) -> FriendsMessage = FriendsMessage.Companion::of,
        action: suspend () -> LeaderboardResult<Unit>,
    ) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = null) }
            var message: FriendsMessage? = null
            try {
                message = (action() as? LeaderboardResult.Err)?.let { err -> toMessage(err.error) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = FriendsMessage.GENERIC
            } finally {
                // Always, so a throwing action can't leave every button disabled.
                state.update { it.copy(busy = false, message = message) }
            }
        }
    }
}
