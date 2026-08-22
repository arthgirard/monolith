package com.monolith.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.monolith.app.domain.model.BlockHit
import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.SlotResult
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.usecase.BlockHitLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalTime
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

@Serializable
private data class BlockHitDto(
    val packageName: String,
    val atMillis: Long,
)

@Serializable
private data class AppUnlockDto(
    val packageName: String,
    val expiresAt: Long,
)

@Serializable
private data class BlockScheduleDto(
    val id: String,
    val enabled: Boolean,
    val days: List<String>,
    val startMinuteOfDay: Int,
) {
    /**
     * Unparseable day names and out-of-range times are dropped rather than thrown: a schedule
     * store this app can't read is a schedule that silently stops firing, which is the safe
     * failure for a rule that only ever turns blocking *on*.
     */
    fun toDomain(): BlockSchedule? {
        if (startMinuteOfDay !in 0 until 24 * 60) return null
        val parsedDays = days.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
        return BlockSchedule(
            id = id,
            enabled = enabled,
            days = parsedDays.toSet(),
            startTime = LocalTime.of(startMinuteOfDay / 60, startMinuteOfDay % 60),
        )
    }

    companion object {
        fun from(schedule: BlockSchedule): BlockScheduleDto = BlockScheduleDto(
            id = schedule.id,
            enabled = schedule.enabled,
            days = schedule.days.map { it.name },
            startMinuteOfDay = schedule.startTime.hour * 60 + schedule.startTime.minute,
        )
    }
}

@Serializable
private data class CodeBreakerGuessDto(
    val values: List<Int>,
    val slotResults: List<String>,
)

@Serializable
private data class CodeBreakerDto(
    val packageName: String,
    val secret: List<Int>,
    val guesses: List<CodeBreakerGuessDto> = emptyList(),
) {
    fun toDomain(): CodeBreaker = CodeBreaker(
        secret = secret,
        guesses = guesses.map { dto ->
            CodeBreaker.Guess(dto.values, dto.slotResults.map { SlotResult.valueOf(it) })
        },
    )

    companion object {
        fun from(packageName: String, codeBreaker: CodeBreaker): CodeBreakerDto = CodeBreakerDto(
            packageName = packageName,
            secret = codeBreaker.secret,
            guesses = codeBreaker.guesses.map { guess ->
                CodeBreakerGuessDto(guess.values, guess.slotResults.map { it.name })
            },
        )
    }
}

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
        val BLOCK_HITS = stringPreferencesKey("block_hits")
        val APP_UNLOCKS = stringPreferencesKey("app_unlocks")
        val APP_CODE_BREAKERS = stringPreferencesKey("app_code_breakers")
        val BLOCK_SCHEDULES = stringPreferencesKey("block_schedules")
        val SCHEDULE_LAST_FIRE = longPreferencesKey("schedule_last_fire_handled_at")
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
                    commitRunningSegment(prefs, startedAt, System.currentTimeMillis())
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

    /**
     * Drops every per-app unlock and every stored code-breaker at once. A tag tap ends the cycle
     * those belong to: an unlock window must not survive into the next time Monolith comes on
     * (the app would silently stay exempt), and a puzzle from the old cycle must not be resumable.
     */
    suspend fun clearAppUnlocks() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.APP_UNLOCKS)
            prefs.remove(Keys.APP_CODE_BREAKERS)
        }
    }

    /**
     * Persists [startedAt]..[now] as finished block-session segment(s), carving out any live
     * bypass window exactly like the real session-end path below. Shared by session end and by
     * [grantAppUnlock], since an app unlock ends the current streak the same way a bypass does --
     * it just doesn't turn Monolith off to do it.
     */
    private fun commitRunningSegment(prefs: MutablePreferences, startedAt: Long, now: Long) {
        // [startedAt] can sit in the future: [grantAppUnlock] fast-forwards the session clock past
        // an app's unlock window. Nothing has accrued yet in that case -- committing anyway would
        // write a negative-length segment, and hand the clamps below an inverted range that throws.
        if (now <= startedAt) return
        val cutoff = now - SESSION_RETENTION_MILLIS
        // Bypass (emergency mode) minutes don't count toward time gained: carve the bypass
        // window out of this session instead of crediting the full span.
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
        val updated = decodeBlockSessions(prefs[Keys.BLOCK_SESSIONS]).filter { it.end >= cutoff } + newSegments
        prefs[Keys.BLOCK_SESSIONS] = json.encodeToString(updated)
    }

    /**
     * Grants [packageName] an exception from enforcement until [durationMillis] from now, and
     * ends the current streak the same way a bypass would: the running segment up to now is
     * committed, and the session clock is fast-forwarded past the unlock window so nothing
     * accrues during it. Unlike [startBypass] this doesn't touch [Keys.BYPASS_EXPIRES_AT] --
     * every other blocked app stays blocked, only [packageName] is exempted.
     */
    suspend fun grantAppUnlock(packageName: String, durationMillis: Long) {
        context.dataStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val isActive = prefs[Keys.BLOCK_MODE_ACTIVE] ?: false
            val startedAt = prefs[Keys.SESSION_STARTED_AT]
            if (isActive && startedAt != null) {
                if (now > startedAt) commitRunningSegment(prefs, startedAt, now)
                prefs[Keys.SESSION_STARTED_AT] = maxOf(startedAt, now) + durationMillis
            }

            val unlocks = decodeAppUnlocks(prefs[Keys.APP_UNLOCKS]).filter {
                it.expiresAt > now && it.packageName != packageName
            } + AppUnlockDto(packageName, now + durationMillis)
            prefs[Keys.APP_UNLOCKS] = json.encodeToString(unlocks)

            val codeBreakers = decodeCodeBreakers(prefs[Keys.APP_CODE_BREAKERS]).filterNot { it.packageName == packageName }
            prefs[Keys.APP_CODE_BREAKERS] = json.encodeToString(codeBreakers)
        }
    }

    /**
     * Records that [packageName] was reached for and blocked, applying [BlockHitLog]'s dedupe
     * and retention rules inside the atomic edit so concurrent hits can't clobber each other.
     */
    suspend fun recordBlockHit(packageName: String) {
        context.dataStore.edit { prefs ->
            val existing = decodeBlockHits(prefs[Keys.BLOCK_HITS])
                .map { BlockHit(it.packageName, it.atMillis) }
            val updated = BlockHitLog.record(existing, packageName, System.currentTimeMillis())
                ?: return@edit
            prefs[Keys.BLOCK_HITS] = json.encodeToString(
                updated.map { BlockHitDto(it.packageName, it.atMillis) },
            )
        }
    }

    val blockHits: Flow<List<BlockHit>> = context.dataStore.data.map { prefs ->
        decodeBlockHits(prefs[Keys.BLOCK_HITS]).map { BlockHit(it.packageName, it.atMillis) }
    }

    private fun decodeBlockHits(raw: String?): List<BlockHitDto> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<BlockHitDto>>(raw) }.getOrDefault(emptyList())
    }

    val appUnlocks: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        decodeAppUnlocks(prefs[Keys.APP_UNLOCKS]).associate { it.packageName to it.expiresAt }
    }

    val codeBreakers: Flow<Map<String, CodeBreaker>> = context.dataStore.data.map { prefs ->
        decodeCodeBreakers(prefs[Keys.APP_CODE_BREAKERS]).associate { it.packageName to it.toDomain() }
    }

    suspend fun saveCodeBreaker(packageName: String, codeBreaker: CodeBreaker) {
        context.dataStore.edit { prefs ->
            val updated = decodeCodeBreakers(prefs[Keys.APP_CODE_BREAKERS]).filterNot { it.packageName == packageName } +
                CodeBreakerDto.from(packageName, codeBreaker)
            prefs[Keys.APP_CODE_BREAKERS] = json.encodeToString(updated)
        }
    }

    private fun decodeAppUnlocks(raw: String?): List<AppUnlockDto> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<AppUnlockDto>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeCodeBreakers(raw: String?): List<CodeBreakerDto> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<CodeBreakerDto>>(raw) }.getOrDefault(emptyList())
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

    val blockSchedules: Flow<List<BlockSchedule>> = context.dataStore.data.map { prefs ->
        decodeBlockSchedules(prefs[Keys.BLOCK_SCHEDULES]).mapNotNull { it.toDomain() }
    }

    suspend fun setBlockSchedules(schedules: List<BlockSchedule>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BLOCK_SCHEDULES] = json.encodeToString(schedules.map { BlockScheduleDto.from(it) })
        }
    }

    /**
     * Timestamp of the most recent scheduled fire already acted on. Catch-up uses it as an
     * exclusive lower bound so a missed occurrence is replayed at most once, however many times
     * the reconcile path runs (boot broadcast, package replace, plain app start).
     */
    val scheduleLastFire: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[Keys.SCHEDULE_LAST_FIRE]
    }

    suspend fun setScheduleLastFire(millis: Long) {
        context.dataStore.edit { prefs ->
            // Never moves backwards: a stale fire arriving late must not reopen an older window.
            val current = prefs[Keys.SCHEDULE_LAST_FIRE] ?: Long.MIN_VALUE
            if (millis > current) prefs[Keys.SCHEDULE_LAST_FIRE] = millis
        }
    }

    private fun decodeBlockSchedules(raw: String?): List<BlockScheduleDto> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<BlockScheduleDto>>(raw) }.getOrDefault(emptyList())
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
