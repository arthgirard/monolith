package com.monolith.app.ui.friends

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.R
import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.Identity
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
import kotlinx.coroutines.flow.combine
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
    TOO_MANY_GROUPS(R.string.friends_error_too_many),
    ALREADY_MEMBER(R.string.friends_error_already_member),
    NAME_NEEDS_THREE(R.string.friends_error_name_needs_three),
    REMOVED(R.string.friends_removed),
    GENERIC(R.string.friends_error_generic);

    companion object {
        fun of(error: LeaderboardError): FriendsMessage = when (error) {
            LeaderboardError.NETWORK -> NETWORK
            LeaderboardError.INVITE_NOT_FOUND -> INVITE_NOT_FOUND
            LeaderboardError.GROUP_FULL -> GROUP_FULL
            LeaderboardError.TOO_MANY_GROUPS -> TOO_MANY_GROUPS
            LeaderboardError.ALREADY_MEMBER -> ALREADY_MEMBER
            LeaderboardError.NAME_NEEDS_THREE -> NAME_NEEDS_THREE
            LeaderboardError.UNAUTHORIZED -> REMOVED
            // NOT_MEMBER included: the repository already dropped that group from the switcher.
            else -> GENERIC
        }
    }
}

enum class BoardStatus { LOADING, READY, OFFLINE, FAILED }

enum class FriendsSheet { SETTINGS, ADD }

data class FriendsUiState(
    val loaded: Boolean = false,
    val identity: Identity? = null,
    /** The name from setup; empty only on installs set up before it was asked. */
    val displayName: String = "",
    /** Always one of [identity]'s groups, or null when there are none. */
    val selectedGroupId: String? = null,
    val sheet: FriendsSheet? = null,
    val window: BoardWindow = BoardWindow.WEEK,
    val rows: List<BoardRow> = emptyList(),
    val boardStatus: BoardStatus = BoardStatus.LOADING,
    val busy: Boolean = false,
    val message: FriendsMessage? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    /** The member whose blocked apps are open on their own page. */
    val appsOf: BoardRow? = null,
) {
    val groups: List<GroupInfo> get() = identity?.groups.orEmpty()
    val selectedGroup: GroupInfo? get() = groups.find { it.id == selectedGroupId }
}

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
            var groupId: String? = null
            combine(repository.observeIdentity(), repository.observeSelectedGroupId()) { identity, selected ->
                // A stored selection can lag the group list by a write; fall back to the first.
                val groups = identity?.groups.orEmpty()
                identity to (groups.find { it.id == selected } ?: groups.firstOrNull())?.id
            }.collect { (identity, selected) ->
                // Renames, share changes and member counts update in place; only a different
                // group or identity (switch, join, restore, leave, removal) resets the board.
                val appeared = identity != null && identity.token != token
                val switched = identity?.token != token || selected != groupId
                val moved = selected != groupId
                token = identity?.token
                groupId = selected
                if (switched) boardJob?.cancel()
                state.update {
                    it.copy(
                        loaded = true,
                        identity = identity,
                        selectedGroupId = selected,
                        // The settings sheet edits the selected group: if the selection moved under
                        // it (a not_member drop fell back to another group), close it, don't retarget.
                        sheet = if (selected == null || (moved && it.sheet == FriendsSheet.SETTINGS)) null else it.sheet,
                        rows = if (switched) emptyList() else it.rows,
                        boardStatus = if (switched) BoardStatus.LOADING else it.boardStatus,
                        message = if (appeared && it.message == FriendsMessage.REMOVED) null else it.message,
                    )
                }
                // The board shows this member's own row: upload it now instead of after the
                // debounce. The board fetches again once that lands (lastSyncedAt below).
                if (appeared) syncer.requestSync(immediate = true)
                if (switched && selected != null) refresh()
            }
        }
        viewModelScope.launch {
            repository.observeDisplayName().collect { name -> state.update { it.copy(displayName = name) } }
        }
        viewModelScope.launch {
            // Names and member counts change as friends join; without an identity this is a no-op.
            repository.refreshGroups()
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
                if (state.value.selectedGroupId != null) refresh()
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

    fun selectGroup(groupId: String) {
        viewModelScope.launch { repository.selectGroup(groupId) }
    }

    fun refresh() {
        // Only the latest request may land: a slow day board must not fill the week tab.
        boardJob?.cancel()
        boardJob = viewModelScope.launch {
            state.update { it.copy(boardStatus = if (it.rows.isEmpty()) BoardStatus.LOADING else it.boardStatus) }
            val window = state.value.window
            val token = state.value.identity?.token ?: return@launch
            val groupId = state.value.selectedGroupId ?: return@launch
            val result = repository.board(groupId, window, LocalDate.now())
            // The blocking HTTP call can't be cancelled, so check what it was asked for as well.
            val current = state.value
            if (current.window != window || current.identity?.token != token || current.selectedGroupId != groupId) return@launch
            when (result) {
                is LeaderboardResult.Ok -> state.update { it.copy(rows = result.value, boardStatus = BoardStatus.READY) }
                is LeaderboardResult.Err -> state.update {
                    when {
                        // The repository cleared the identity and left the removal notice.
                        result.error == LeaderboardError.UNAUTHORIZED -> it
                        it.rows.isNotEmpty() -> it.copy(boardStatus = BoardStatus.OFFLINE)
                        else -> it.copy(boardStatus = BoardStatus.FAILED)
                    }
                }
            }
        }
    }

    fun showApps(row: BoardRow) = state.update { it.copy(appsOf = row) }

    fun openSheet(sheet: FriendsSheet) = state.update { it.copy(sheet = sheet, message = null) }

    fun closeSheet() = state.update { it.copy(sheet = null) }

    /** [displayName] only from an install that has none yet; it is saved as theirs first. */
    fun create(share: ShareSettings, displayName: String? = null) =
        submit(onOk = ::enteredGroup) { named(displayName) { repository.createGroup(share) } }

    fun join(inviteCode: String, share: ShareSettings, displayName: String? = null) =
        submit(onOk = ::enteredGroup) { named(displayName) { repository.joinGroup(inviteCode, share) } }

    private suspend fun named(
        displayName: String?,
        action: suspend () -> LeaderboardResult<Unit>,
    ): LeaderboardResult<Unit> {
        if (displayName != null) {
            val saved = repository.setDisplayName(displayName)
            if (saved is LeaderboardResult.Err) return saved
        }
        return action()
    }

    /** An empty [name] clears it. */
    fun renameGroup(name: String) = submit {
        val groupId = state.value.selectedGroupId ?: return@submit LeaderboardResult.Ok(Unit)
        repository.updateGroup(groupId, name = name)
    }

    fun updateShare(share: ShareSettings) = submit(onOk = {
        // Turning a signal back on needs an upload (the server nulled it when it was hidden);
        // the board fetches again when it lands. A hidden one shows right away.
        syncer.requestSync(immediate = true)
        refresh()
    }) {
        val groupId = state.value.selectedGroupId ?: return@submit LeaderboardResult.Ok(Unit)
        repository.updateGroup(groupId, share = share)
    }

    fun leaveSelectedGroup() {
        // submit() drops a tap while busy; keep the sheet open so the leave isn't silently lost.
        if (state.value.busy) return
        val groupId = state.value.selectedGroupId ?: return
        closeSheet()
        submit { repository.leaveGroup(groupId) }
    }

    fun dismissMessage() = state.update { it.copy(message = null) }

    private fun enteredGroup() {
        if (state.value.sheet == FriendsSheet.ADD) closeSheet()
        // A new group's share may widen what this member uploads: send it now for its board.
        syncer.requestSync(immediate = true)
    }

    private fun submit(
        onOk: () -> Unit = {},
        action: suspend () -> LeaderboardResult<Unit>,
    ) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = null) }
            var message: FriendsMessage? = null
            try {
                when (val result = action()) {
                    is LeaderboardResult.Ok -> onOk()
                    is LeaderboardResult.Err -> message = FriendsMessage.of(result.error)
                }
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
