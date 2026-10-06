package com.monolith.app.data.leaderboard

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
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

    /**
     * The master a legacy identity is being migrated to, kept until a [save] with a master: if the
     * server rotated but its answer was lost, the next try still knows the new token.
     */
    val pendingMaster: Flow<String?>

    /**
     * A master made on the phone before any server knows it: written to a tag during setup, and
     * registered on the next online moment. Cleared by any [save], and by [clear].
     */
    val unregisteredMaster: Flow<String?>
    val backupEnabled: Flow<Boolean>
    val lastBackupAt: Flow<Long?>
    suspend fun save(identity: Identity)

    /** Replaces the cached group list; does nothing without an identity. */
    suspend fun saveGroups(groups: List<GroupInfo>)
    suspend fun savePendingMaster(master: String)
    suspend fun saveUnregisteredMaster(master: String)
    suspend fun setBackupEnabled(enabled: Boolean)
    suspend fun setLastBackupAt(at: Long?)
    suspend fun select(groupId: String?)

    /** Everything goes, backup settings too: without the identity the server holds no backup. */
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
        Identity(
            token = token,
            displayName = prefs[DISPLAY_NAME].orEmpty(),
            groups = decodeGroups(prefs[GROUPS]),
            master = prefs[MASTER],
            backupAt = prefs[BACKUP_AT],
        )
    }

    override val selectedGroupId: Flow<String?> = context.leaderboardStore.data.map { it[SELECTED_GROUP] }

    override val removedNotice: Flow<Boolean> = context.leaderboardStore.data.map { it[REMOVED_NOTICE] ?: false }

    override val pendingMaster: Flow<String?> = context.leaderboardStore.data.map { it[PENDING_MASTER] }

    override val unregisteredMaster: Flow<String?> = context.leaderboardStore.data.map { it[UNREGISTERED_MASTER] }

    override val backupEnabled: Flow<Boolean> = context.leaderboardStore.data.map { it[BACKUP_ENABLED] ?: false }

    override val lastBackupAt: Flow<Long?> = context.leaderboardStore.data.map { it[LAST_BACKUP_AT] }

    override suspend fun save(identity: Identity) {
        context.leaderboardStore.edit { prefs ->
            prefs.remove(REMOVED_NOTICE)
            prefs[TOKEN] = identity.token
            prefs[DISPLAY_NAME] = identity.displayName
            prefs[GROUPS] = encodeGroups(identity.groups)
            prefs.putOrRemove(MASTER, identity.master)
            prefs.putOrRemove(BACKUP_AT, identity.backupAt)
            if (identity.master != null) prefs.remove(PENDING_MASTER)
            prefs.remove(UNREGISTERED_MASTER)
        }
    }

    override suspend fun saveGroups(groups: List<GroupInfo>) {
        context.leaderboardStore.edit { prefs ->
            if (prefs[TOKEN] != null) prefs[GROUPS] = encodeGroups(groups)
        }
    }

    override suspend fun savePendingMaster(master: String) {
        context.leaderboardStore.edit { it[PENDING_MASTER] = master }
    }

    override suspend fun saveUnregisteredMaster(master: String) {
        context.leaderboardStore.edit { it[UNREGISTERED_MASTER] = master }
    }

    override suspend fun setBackupEnabled(enabled: Boolean) {
        context.leaderboardStore.edit { it[BACKUP_ENABLED] = enabled }
    }

    override suspend fun setLastBackupAt(at: Long?) {
        context.leaderboardStore.edit { it.putOrRemove(LAST_BACKUP_AT, at) }
    }

    override suspend fun select(groupId: String?) {
        context.leaderboardStore.edit { it.putOrRemove(SELECTED_GROUP, groupId) }
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

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
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
        val MASTER = stringPreferencesKey("master")
        val PENDING_MASTER = stringPreferencesKey("pending_master")
        val UNREGISTERED_MASTER = stringPreferencesKey("unregistered_master")
        val BACKUP_AT = longPreferencesKey("backup_at")
        val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    }
}
