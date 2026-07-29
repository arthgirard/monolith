package com.monolith.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "monolith_prefs")

@Singleton
class MonolithPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val BLOCK_MODE_ACTIVE = booleanPreferencesKey("block_mode_active")
        val BYPASS_EXPIRES_AT = longPreferencesKey("bypass_expires_at")
        val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
        val TAG_UID = stringPreferencesKey("tag_uid")
        val TAG_NDEF_URI = stringPreferencesKey("tag_ndef_uri")
        val TAG_MODE = stringPreferencesKey("tag_mode")
        val TAG_LINKED_AT = longPreferencesKey("tag_linked_at")
    }

    val blockState: Flow<BlockState> = context.dataStore.data.map { prefs ->
        BlockState(
            isActive = prefs[Keys.BLOCK_MODE_ACTIVE] ?: false,
            bypassExpiresAtMillis = prefs[Keys.BYPASS_EXPIRES_AT]?.takeIf { it > 0 },
        )
    }

    suspend fun setBlockModeActive(active: Boolean) {
        context.dataStore.edit { it[Keys.BLOCK_MODE_ACTIVE] = active }
    }

    suspend fun startBypass(durationMillis: Long) {
        context.dataStore.edit {
            it[Keys.BYPASS_EXPIRES_AT] = System.currentTimeMillis() + durationMillis
        }
    }

    suspend fun clearBypass() {
        context.dataStore.edit { it.remove(Keys.BYPASS_EXPIRES_AT) }
    }

    val linkedTag: Flow<NfcTagLink?> = context.dataStore.data.map { prefs ->
        val uid = prefs[Keys.TAG_UID] ?: return@map null
        NfcTagLink(
            uid = uid,
            mode = prefs[Keys.TAG_MODE]?.let { runCatching { TagLinkMode.valueOf(it) }.getOrNull() }
                ?: TagLinkMode.FALLBACK_UID,
            ndefUri = prefs[Keys.TAG_NDEF_URI],
            linkedAtMillis = prefs[Keys.TAG_LINKED_AT] ?: System.currentTimeMillis(),
        )
    }

    suspend fun saveLinkedTag(link: NfcTagLink) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TAG_UID] = link.uid
            prefs[Keys.TAG_MODE] = link.mode.name
            prefs[Keys.TAG_LINKED_AT] = link.linkedAtMillis
            if (link.ndefUri != null) {
                prefs[Keys.TAG_NDEF_URI] = link.ndefUri
            } else {
                prefs.remove(Keys.TAG_NDEF_URI)
            }
        }
    }

    val blockedPackages: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLOCKED_PACKAGES] ?: emptySet()
    }

    suspend fun setBlockedPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.BLOCKED_PACKAGES] = packages }
    }
}
