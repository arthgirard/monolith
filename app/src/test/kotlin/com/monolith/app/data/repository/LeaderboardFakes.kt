package com.monolith.app.data.repository

import com.monolith.app.data.leaderboard.BoardResponse
import com.monolith.app.data.leaderboard.CreateGroupRequest
import com.monolith.app.data.leaderboard.DisplayNameStore
import com.monolith.app.data.leaderboard.GroupDto
import com.monolith.app.data.leaderboard.GroupResponse
import com.monolith.app.data.leaderboard.IdentityStore
import com.monolith.app.data.leaderboard.JoinRequest
import com.monolith.app.data.leaderboard.LeaderboardApi
import com.monolith.app.data.leaderboard.MeResponse
import com.monolith.app.data.leaderboard.RegisterRequest
import com.monolith.app.data.leaderboard.RotateTokenRequest
import com.monolith.app.data.leaderboard.ShareDto
import com.monolith.app.data.leaderboard.SyncRequest
import com.monolith.app.data.leaderboard.UpdateGroupRequest
import com.monolith.app.data.leaderboard.UpdateMeRequest
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

internal class FakeIdentityStore(initial: Identity? = null, selected: String? = null) : IdentityStore {
    override val identity = MutableStateFlow(initial)
    override val selectedGroupId = MutableStateFlow(selected)
    override val removedNotice = MutableStateFlow(false)
    override val pendingMaster = MutableStateFlow<String?>(null)
    override val backupEnabled = MutableStateFlow(false)
    override val lastBackupAt = MutableStateFlow<Long?>(null)
    override suspend fun save(identity: Identity) {
        this.identity.value = identity
        removedNotice.value = false
        if (identity.master != null) pendingMaster.value = null
    }
    override suspend fun saveGroups(groups: List<GroupInfo>) {
        identity.value = identity.value?.copy(groups = groups)
    }
    override suspend fun savePendingMaster(master: String) { pendingMaster.value = master }
    override suspend fun setBackupEnabled(enabled: Boolean) { backupEnabled.value = enabled }
    override suspend fun setLastBackupAt(at: Long?) { lastBackupAt.value = at }
    override suspend fun select(groupId: String?) { selectedGroupId.value = groupId }
    override suspend fun clear(removed: Boolean) {
        identity.value = null
        selectedGroupId.value = null
        pendingMaster.value = null
        backupEnabled.value = false
        lastBackupAt.value = null
        removedNotice.value = removed
    }
    override suspend fun dismissRemovedNotice() { removedNotice.value = false }
}

internal class FakeDisplayNameStore(initial: String = "") : DisplayNameStore {
    override val displayName = MutableStateFlow(initial)
    override suspend fun setDisplayName(name: String) { displayName.value = name.trim() }
}

internal class FakeLeaderboardApi : LeaderboardApi {
    var createResult: LeaderboardResult<GroupResponse>? = null
    var meResult: LeaderboardResult<MeResponse> = LeaderboardResult.Ok(MeResponse("Ana", emptyList()))
    var boardResult: LeaderboardResult<BoardResponse> = LeaderboardResult.Ok(BoardResponse(emptyList()))
    var leaveResult: LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)
    var updateGroupResult: LeaderboardResult<GroupResponse>? = null
    var updateMeResult: LeaderboardResult<MeResponse>? = null
    var registerResult: LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)
    var rotateResult: LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)
    var putBackupResult: LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)
    var getBackupResult: LeaderboardResult<ByteArray> = LeaderboardResult.Err(LeaderboardError.NO_BACKUP)
    var deleteBackupResult: LeaderboardResult<Unit> = LeaderboardResult.Ok(Unit)
    var duringBoard: suspend () -> Unit = {}
    /** Tokens the server no longer knows (deleted user, or pre-migration): every call is a 401. */
    val deadTokens = mutableSetOf<String>()
    val syncResults = ArrayDeque<LeaderboardResult<Unit>>()
    val syncRequests = mutableListOf<SyncRequest>()
    val createCalls = mutableListOf<Pair<String, CreateGroupRequest>>()
    val joinCalls = mutableListOf<Pair<String, JoinRequest>>()
    val registerRequests = mutableListOf<RegisterRequest>()
    val rotateCalls = mutableListOf<Pair<String, RotateTokenRequest>>()
    val putBackupCalls = mutableListOf<Pair<String, ByteArray>>()
    val getBackupTokens = mutableListOf<String>()
    val deleteBackupTokens = mutableListOf<String>()
    val meTokens = mutableListOf<String>()
    val updateGroupRequests = mutableListOf<UpdateGroupRequest>()
    val leftGroups = mutableListOf<String>()

    /** How many calls of the identity and backup kind reached the fake. */
    val calls get() = createCalls.size + joinCalls.size + registerRequests.size + rotateCalls.size +
        putBackupCalls.size + getBackupTokens.size + deleteBackupTokens.size + meTokens.size

    override suspend fun register(request: RegisterRequest): LeaderboardResult<Unit> {
        registerRequests += request
        return registerResult
    }
    override suspend fun rotateToken(token: String, request: RotateTokenRequest): LeaderboardResult<Unit> {
        rotateCalls += token to request
        if (token in deadTokens) return LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        return rotateResult
    }
    override suspend fun createGroup(token: String, request: CreateGroupRequest): LeaderboardResult<GroupResponse> {
        createCalls += token to request
        if (token in deadTokens) return LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        return createResult ?: LeaderboardResult.Ok(GroupResponse(group = g1))
    }
    override suspend fun join(token: String, request: JoinRequest): LeaderboardResult<GroupResponse> {
        joinCalls += token to request
        return LeaderboardResult.Ok(GroupResponse(group = g1))
    }
    override suspend fun me(token: String): LeaderboardResult<MeResponse> {
        meTokens += token
        if (token in deadTokens) return LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        return meResult
    }
    override suspend fun updateMe(token: String, request: UpdateMeRequest): LeaderboardResult<MeResponse> =
        updateMeResult ?: LeaderboardResult.Ok(MeResponse(request.displayName, (meResult as LeaderboardResult.Ok).value.groups))
    override suspend fun updateGroup(token: String, groupId: String, request: UpdateGroupRequest): LeaderboardResult<GroupResponse> {
        updateGroupRequests += request
        return updateGroupResult ?: LeaderboardResult.Err(LeaderboardError.SERVER)
    }
    override suspend fun leaveGroup(token: String, groupId: String): LeaderboardResult<Unit> {
        leftGroups += groupId
        return leaveResult
    }
    override suspend fun sync(token: String, request: SyncRequest): LeaderboardResult<Unit> {
        syncRequests += request
        return syncResults.removeFirstOrNull() ?: LeaderboardResult.Ok(Unit)
    }
    override suspend fun board(token: String, groupId: String, window: BoardWindow, date: LocalDate): LeaderboardResult<BoardResponse> {
        duringBoard()
        return boardResult
    }
    override suspend fun putBackup(token: String, bytes: ByteArray): LeaderboardResult<Unit> {
        putBackupCalls += token to bytes
        return putBackupResult
    }
    override suspend fun getBackup(token: String): LeaderboardResult<ByteArray> {
        getBackupTokens += token
        return getBackupResult
    }
    override suspend fun deleteBackup(token: String): LeaderboardResult<Unit> {
        deleteBackupTokens += token
        if (token in deadTokens) return LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        return deleteBackupResult
    }
}

internal val g1 = GroupDto("g1", "ABCDEFGH", null, 2, listOf("Sam"), ShareDto(saved = true, streak = false, pauses = false))
internal val g2 = GroupDto("g2", "JKLMNPQR", null, 2, listOf("Lea"), ShareDto(saved = false, streak = false, pauses = true))
