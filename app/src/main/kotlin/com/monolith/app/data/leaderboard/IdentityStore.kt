package com.monolith.app.data.leaderboard

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.Identity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

interface IdentityStore {
    val identity: Flow<Identity?>
    val selectedGroupId: Flow<String?>

    /** Set when the server dropped this identity (not when they left); cleared by any [save]. */
    val removedNotice: Flow<Boolean>
    suspend fun save(identity: Identity)

    /** Replaces the cached group list; does nothing without an identity. */
    suspend fun saveGroups(groups: List<GroupInfo>)
    suspend fun select(groupId: String?)
    suspend fun clear(removed: Boolean = false)
    suspend fun dismissRemovedNotice()
}

// A file of its own: leaving the last group clears it whole, and nothing here belongs in the
// block state. A corrupt file starts over empty: the member can come back with their recovery
// code. Keys of the single-group version (invite_code, share_*) are simply never read.
private val Context.leaderboardStore by preferencesDataStore(
    name = "leaderboard_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** App-private storage, the same protection the linked tag's UID has. */
@Singleton
class DataStoreIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : IdentityStore {

    override val identity: Flow<Identity?> = context.leaderboardStore.data.map { prefs ->
        val token = prefs[TOKEN] ?: return@map null
        Identity(token = token, displayName = prefs[DISPLAY_NAME].orEmpty(), groups = decodeGroups(prefs[GROUPS]))
    }

    override val selectedGroupId: Flow<String?> = context.leaderboardStore.data.map { it[SELECTED_GROUP] }

    override val removedNotice: Flow<Boolean> = context.leaderboardStore.data.map { it[REMOVED_NOTICE] ?: false }

    override suspend fun save(identity: Identity) {
        context.leaderboardStore.edit { prefs ->
            prefs.remove(REMOVED_NOTICE)
            prefs[TOKEN] = identity.token
            prefs[DISPLAY_NAME] = identity.displayName
            prefs[GROUPS] = encodeGroups(identity.groups)
        }
    }

    override suspend fun saveGroups(groups: List<GroupInfo>) {
        context.leaderboardStore.edit { prefs ->
            if (prefs[TOKEN] != null) prefs[GROUPS] = encodeGroups(groups)
        }
    }

    override suspend fun select(groupId: String?) {
        context.leaderboardStore.edit { prefs ->
            if (groupId == null) prefs.remove(SELECTED_GROUP) else prefs[SELECTED_GROUP] = groupId
        }
    }

    override suspend fun clear(removed: Boolean) {
        context.leaderboardStore.edit { prefs ->
            prefs.clear()
            if (removed) prefs[REMOVED_NOTICE] = true
        }
    }

    override suspend fun dismissRemovedNotice() {
        context.leaderboardStore.edit { it.remove(REMOVED_NOTICE) }
    }

    private fun encodeGroups(groups: List<GroupInfo>): String = LeaderboardJson.encodeToString(groups.map(GroupInfo::toDto))

    // An unreadable cache only costs a refresh: the groups come back from the server.
    private fun decodeGroups(json: String?): List<GroupInfo> = json
        ?.let { runCatching { LeaderboardJson.decodeFromString<List<GroupDto>>(it) }.getOrNull() }
        ?.map(GroupDto::toDomain)
        .orEmpty()

    private companion object {
        val TOKEN = stringPreferencesKey("token")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val GROUPS = stringPreferencesKey("groups")
        val SELECTED_GROUP = stringPreferencesKey("selected_group")
        val REMOVED_NOTICE = booleanPreferencesKey("removed_notice")
    }
}
