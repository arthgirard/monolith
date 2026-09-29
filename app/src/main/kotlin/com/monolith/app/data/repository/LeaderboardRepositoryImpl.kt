package com.monolith.app.data.repository

import com.monolith.app.data.backup.BackupCrypto
import com.monolith.app.data.leaderboard.BoardRowDto
import com.monolith.app.data.leaderboard.CreateGroupRequest
import com.monolith.app.data.leaderboard.GroupDto
import com.monolith.app.data.leaderboard.GroupResponse
import com.monolith.app.data.leaderboard.IdentityStore
import com.monolith.app.data.leaderboard.JoinRequest
import com.monolith.app.data.leaderboard.LeaderboardApi
import com.monolith.app.data.leaderboard.UpdateGroupRequest
import com.monolith.app.data.leaderboard.UpdateMeRequest
import com.monolith.app.data.leaderboard.syncRequestOf
import com.monolith.app.data.leaderboard.toDomain
import com.monolith.app.data.leaderboard.toDto
import com.monolith.app.domain.model.BoardRow
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.model.andThen
import com.monolith.app.domain.model.map
import com.monolith.app.domain.repository.LeaderboardRepository
import com.monolith.app.domain.usecase.shareUnion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeaderboardRepositoryImpl @Inject constructor(
    private val api: LeaderboardApi,
    private val store: IdentityStore,
    private val identities: IdentityManager,
) : LeaderboardRepository {

    override fun observeIdentity(): Flow<Identity?> = store.identity

    override fun observeSelectedGroupId(): Flow<String?> = store.selectedGroupId

    override suspend fun selectGroup(groupId: String) = store.select(groupId)

    override fun observeRemovedNotice(): Flow<Boolean> = store.removedNotice

    override suspend fun dismissRemovedNotice() = store.dismissRemovedNotice()

    override suspend fun createGroup(displayName: String?, share: ShareSettings): LeaderboardResult<Unit> =
        enterGroup(displayName) { token, name -> api.createGroup(token, CreateGroupRequest(name, share.toDto())) }

    override suspend fun joinGroup(inviteCode: String, displayName: String?, share: ShareSettings): LeaderboardResult<Unit> =
        enterGroup(displayName) { token, name -> api.join(token, JoinRequest(inviteCode.trim(), name, share.toDto())) }

    override suspend fun restore(recoveryCode: String): LeaderboardResult<Unit> {
        val master = BackupCrypto.decodeCode(recoveryCode) ?: return LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        val token = BackupCrypto.deriveToken(master)
        return api.me(token).andThen { me ->
            val groups = me.groups.map(GroupDto::toDomain)
            store.save(Identity(token, me.displayName, groups, BackupCrypto.encodeCode(master), me.backupAt))
            store.select(groups.firstOrNull()?.id)
        }
    }

    override suspend fun rename(displayName: String): LeaderboardResult<Unit> = authed { identity ->
        api.updateMe(identity.token, UpdateMeRequest(displayName.trim()))
            .andThen { me ->
                val current = store.identity.first()
                if (current?.token == identity.token) {
                    store.save(current.copy(displayName = me.displayName, groups = me.groups.map(GroupDto::toDomain)))
                }
            }
    }

    override suspend fun refreshGroups(): LeaderboardResult<Unit> = authed { identity ->
        api.me(identity.token).andThen { me -> cacheGroups(identity.token, me.groups.map(GroupDto::toDomain)) }
    }

    override suspend fun updateGroup(groupId: String, share: ShareSettings?, name: String?): LeaderboardResult<Unit> =
        authed { identity ->
            api.updateGroup(identity.token, groupId, UpdateGroupRequest(share?.toDto(), name?.trim()))
                .andThen { response ->
                    val updated = response.group.toDomain()
                    val groups = store.identity.first()?.groups ?: return@andThen
                    cacheGroups(identity.token, groups.map { if (it.id == updated.id) updated else it })
                }
                .alsoDropIfNotMember()
        }

    override suspend fun leaveGroup(groupId: String): LeaderboardResult<Unit> = authed { identity ->
        api.leaveGroup(identity.token, groupId)
            .andThen {
                // Drop it now so an offline refresh can't leave it on screen.
                cacheGroups(identity.token, identity.groups.filter { it.id != groupId })
                // Ask the server rather than the cache whether that was the last group: one joined
                // on another phone may not be cached yet. The server deletes a user who leaves
                // their last group, so a 401 here is that leave, not a removal: no notice.
                when (val me = api.me(identity.token)) {
                    is LeaderboardResult.Ok -> cacheGroups(identity.token, me.value.groups.map(GroupDto::toDomain))
                    is LeaderboardResult.Err ->
                        if (me.error == LeaderboardError.UNAUTHORIZED && store.identity.first()?.token == identity.token) {
                            store.clear()
                        }
                }
            }
            .alsoDropIfNotMember()
            // Already out of it (left on another phone, or removed): the refresh dropped it, which
            // is all leaving would have done.
            .let { if (it is LeaderboardResult.Err && it.error == LeaderboardError.NOT_MEMBER) LeaderboardResult.Ok(Unit) else it }
    }

    override suspend fun sync(days: List<DayAggregate>, streakStartedAt: Long?): LeaderboardResult<Unit> = authed { identity ->
        // With no group cached the share union is all off, and uploading it would wipe the
        // server's values with nulls. Nobody could see them anyway.
        if (identity.groups.isEmpty()) return@authed LeaderboardResult.Ok(Unit)
        val first = api.sync(identity.token, syncRequestOf(days, streakStartedAt, shareUnion(identity.groups)))
        // The cached share flags are older than the server's (a change on another phone, or an
        // update that raced this upload). Take the server's, then retry once with their union.
        if (first is LeaderboardResult.Err && first.error == LeaderboardError.HIDDEN_SIGNAL) {
            val refreshed = refreshGroups()
            if (refreshed is LeaderboardResult.Err) return@authed refreshed
            val current = store.identity.first()?.takeIf { it.token == identity.token } ?: return@authed first
            api.sync(identity.token, syncRequestOf(days, streakStartedAt, shareUnion(current.groups)))
        } else {
            first
        }
    }

    override suspend fun board(groupId: String, window: BoardWindow, today: LocalDate): LeaderboardResult<List<BoardRow>> =
        authed { identity ->
            api.board(identity.token, groupId, window, today)
                .map { response -> response.rows.map(BoardRowDto::toDomain) }
                .alsoDropIfNotMember()
        }

    /**
     * Creating and joining first make sure there is an identity (registering a new one, or
     * migrating one from before backups), then always send its token. [displayName] goes along
     * only while the stored name is empty: a named identity keeps its name.
     *
     * An identity with no cached group may be a dead one (the last leave's refresh was offline,
     * or a token from the single-group build): on a 401 it is cleared quietly and the call is
     * retried once as a new member, with the name the join form supplied.
     */
    private suspend fun enterGroup(
        displayName: String?,
        retried: Boolean = false,
        call: suspend (token: String, displayName: String?) -> LeaderboardResult<GroupResponse>,
    ): LeaderboardResult<Unit> {
        val before = store.identity.first()
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
        val ensured = identities.ensureIdentity(name)
        val identity = store.identity.first()
        val result: LeaderboardResult<GroupResponse> = when {
            ensured is LeaderboardResult.Err -> ensured
            identity == null -> LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
            else -> guarded(identity) { call(identity.token, name.takeIf { identity.displayName.isEmpty() }) }
        }
        // A dead legacy token fails in the migration, before any call: then it is [before].
        val tried = identity ?: before
        if (!retried && tried != null && tried.groups.isEmpty() && result is LeaderboardResult.Err &&
            result.error == LeaderboardError.UNAUTHORIZED && store.identity.first() == null
        ) {
            return enterGroup(displayName, retried = true, call)
        }
        if (identity == null) return result.andThen { }
        return result.andThen { response ->
            val group = response.group.toDomain()
            // Cached right away, so the new group shows even if the refresh below fails.
            cacheGroups(identity.token, identity.groups.filter { it.id != group.id } + group)
            if (identity.displayName.isEmpty() && name != null) {
                store.identity.first()?.takeIf { it.token == identity.token }?.let { store.save(it.copy(displayName = name)) }
            }
            refreshGroups()
            // That refresh may have met a 401 and cleared the store: select nothing into it.
            if (store.identity.first()?.token == identity.token) store.select(group.id)
        }
    }

    /** A group this member is no longer in (removed, or left on another phone) leaves the cache. */
    private suspend fun <T> LeaderboardResult<T>.alsoDropIfNotMember(): LeaderboardResult<T> {
        if (this is LeaderboardResult.Err && error == LeaderboardError.NOT_MEMBER) refreshGroups()
        return this
    }

    /**
     * Stores [groups] only while [token] is still the stored identity, and moves the selection
     * when the selected group is gone.
     */
    private suspend fun cacheGroups(token: String, groups: List<GroupInfo>) {
        if (store.identity.first()?.token != token) return
        store.saveGroups(groups)
        val selected = store.selectedGroupId.first()
        if (groups.none { it.id == selected }) store.select(groups.firstOrNull()?.id)
    }

    private suspend fun <T> authed(block: suspend (Identity) -> LeaderboardResult<T>): LeaderboardResult<T> {
        val identity = store.identity.first() ?: return LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED)
        return guarded(identity) { block(identity) }
    }

    private suspend fun <T> guarded(identity: Identity, block: suspend () -> LeaderboardResult<T>): LeaderboardResult<T> =
        identities.guarded(identity, block)
}
