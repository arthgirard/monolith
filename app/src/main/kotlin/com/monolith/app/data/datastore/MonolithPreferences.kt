package com.monolith.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "monolith_prefs")

@Serializable
private data class ImportantPersonDto(
    val packageName: String,
    val name: String?,
    val handle: String?,
)

@Serializable
private data class BlockSessionDto(
    val start: Long,
    val end: Long,
)

/** Sessions older than this are pruned on write; Year view only ever needs the trailing 12 months. */
private const val SESSION_RETENTION_MILLIS: Long = 400L * 24 * 60 * 60 * 1000

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
        val IMPORTANT_PEOPLE = stringPreferencesKey("important_people")
        val SESSION_STARTED_AT = longPreferencesKey("session_started_at")
        val BLOCK_SESSIONS = stringPreferencesKey("block_sessions")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val blockState: Flow<BlockState> = context.dataStore.data.map { prefs ->
        BlockState(
            isActive = prefs[Keys.BLOCK_MODE_ACTIVE] ?: false,
            bypassExpiresAtMillis = prefs[Keys.BYPASS_EXPIRES_AT]?.takeIf { it > 0 },
        )
    }

    suspend fun setBlockModeActive(active: Boolean) {
        context.dataStore.edit { prefs ->
            val wasActive = prefs[Keys.BLOCK_MODE_ACTIVE] ?: false
            prefs[Keys.BLOCK_MODE_ACTIVE] = active

            if (active && !wasActive) {
                prefs[Keys.SESSION_STARTED_AT] = System.currentTimeMillis()
            } else if (!active && wasActive) {
                val startedAt = prefs[Keys.SESSION_STARTED_AT]
                if (startedAt != null) {
                    val now = System.currentTimeMillis()
                    val cutoff = now - SESSION_RETENTION_MILLIS
                    // Bypass (emergency mode) minutes don't count toward time gained: carve the
                    // bypass window out of this session instead of crediting the full span.
                    val bypassExpiresAt = prefs[Keys.BYPASS_EXPIRES_AT]?.takeIf { it > 0 }
                    val newSegments = if (bypassExpiresAt != null) {
                        val bypassStartedAt = bypassExpiresAt - BlockState.BYPASS_DURATION_MILLIS
                        val beforeBypassEnd = bypassStartedAt.coerceIn(startedAt, now)
                        val afterBypassStart = bypassExpiresAt.coerceIn(startedAt, now)
                        listOfNotNull(
                            BlockSessionDto(startedAt, beforeBypassEnd).takeIf { beforeBypassEnd > startedAt },
                            BlockSessionDto(afterBypassStart, now).takeIf { now > afterBypassStart },
                        )
                    } else {
                        listOf(BlockSessionDto(startedAt, now))
                    }
                    val updated = decodeBlockSessions(prefs[Keys.BLOCK_SESSIONS])
                        .filter { it.end >= cutoff } + newSegments
                    prefs[Keys.BLOCK_SESSIONS] = json.encodeToString(updated)
                }
                prefs.remove(Keys.SESSION_STARTED_AT)
            }
        }
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

    val importantPeople: Flow<List<ImportantPerson>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.IMPORTANT_PEOPLE] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<ImportantPersonDto>>(raw) }
            .getOrDefault(emptyList())
            .map { ImportantPerson(it.packageName, it.name, it.handle) }
    }

    suspend fun addImportantPerson(person: ImportantPerson) {
        context.dataStore.edit { prefs ->
            val current = decodeImportantPeople(prefs[Keys.IMPORTANT_PEOPLE])
            val updated = current + ImportantPersonDto(person.packageName, person.name, person.handle)
            prefs[Keys.IMPORTANT_PEOPLE] = json.encodeToString(updated)
        }
    }

    suspend fun removeImportantPerson(person: ImportantPerson) {
        context.dataStore.edit { prefs ->
            val current = decodeImportantPeople(prefs[Keys.IMPORTANT_PEOPLE])
            val updated = current.filterNot {
                it.packageName == person.packageName && it.name == person.name && it.handle == person.handle
            }
            prefs[Keys.IMPORTANT_PEOPLE] = json.encodeToString(updated)
        }
    }

    private fun decodeImportantPeople(raw: String?): List<ImportantPersonDto> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<ImportantPersonDto>>(raw) }.getOrDefault(emptyList())
    }

    val blockSessions: Flow<List<BlockSession>> = context.dataStore.data.map { prefs ->
        decodeBlockSessions(prefs[Keys.BLOCK_SESSIONS]).map { BlockSession(it.start, it.end) }
    }

    val activeSessionStart: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[Keys.SESSION_STARTED_AT]
    }

    private fun decodeBlockSessions(raw: String?): List<BlockSessionDto> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<BlockSessionDto>>(raw) }.getOrDefault(emptyList())
    }
}
