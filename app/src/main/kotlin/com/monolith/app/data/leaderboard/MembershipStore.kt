package com.monolith.app.data.leaderboard

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.monolith.app.domain.model.GroupMembership
import com.monolith.app.domain.model.ShareSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface MembershipStore {
    val membership: Flow<GroupMembership?>

    /** Set when the server dropped this member (not when they left); cleared by any [save]. */
    val removedNotice: Flow<Boolean>
    suspend fun save(membership: GroupMembership)
    suspend fun clear(removed: Boolean = false)
    suspend fun dismissRemovedNotice()
}

// A file of its own: leaving a group clears it whole, and nothing here belongs in the block state.
// A corrupt file starts over empty: the member can come back with their recovery code.
private val Context.leaderboardStore by preferencesDataStore(
    name = "leaderboard_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** App-private storage, the same protection the linked tag's UID has. */
@Singleton
class DataStoreMembershipStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : MembershipStore {

    override val membership: Flow<GroupMembership?> = context.leaderboardStore.data.map { prefs ->
        val token = prefs[TOKEN] ?: return@map null
        GroupMembership(
            token = token,
            displayName = prefs[DISPLAY_NAME].orEmpty(),
            inviteCode = prefs[INVITE_CODE].orEmpty(),
            share = ShareSettings(
                saved = prefs[SHARE_SAVED] ?: true,
                streak = prefs[SHARE_STREAK] ?: true,
                pauses = prefs[SHARE_PAUSES] ?: true,
            ),
        )
    }

    override val removedNotice: Flow<Boolean> = context.leaderboardStore.data.map { it[REMOVED_NOTICE] ?: false }

    override suspend fun save(membership: GroupMembership) {
        context.leaderboardStore.edit { prefs ->
            prefs.remove(REMOVED_NOTICE)
            prefs[TOKEN] = membership.token
            prefs[DISPLAY_NAME] = membership.displayName
            prefs[INVITE_CODE] = membership.inviteCode
            prefs[SHARE_SAVED] = membership.share.saved
            prefs[SHARE_STREAK] = membership.share.streak
            prefs[SHARE_PAUSES] = membership.share.pauses
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

    private companion object {
        val TOKEN = stringPreferencesKey("token")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val INVITE_CODE = stringPreferencesKey("invite_code")
        val SHARE_SAVED = booleanPreferencesKey("share_saved")
        val SHARE_STREAK = booleanPreferencesKey("share_streak")
        val SHARE_PAUSES = booleanPreferencesKey("share_pauses")
        val REMOVED_NOTICE = booleanPreferencesKey("removed_notice")
    }
}
