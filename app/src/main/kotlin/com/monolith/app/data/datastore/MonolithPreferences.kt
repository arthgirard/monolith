package com.monolith.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.domain.model.BlockHit
import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.Pause
import com.monolith.app.domain.model.PauseType
import com.monolith.app.domain.model.SlotResult
import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.usecase.BlockHitLog
import com.monolith.app.domain.usecase.PauseLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
private data class PauseDto(
    val type: String,
    val at: Long,
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


private object Keys {
    val BLOCK_MODE_ACTIVE = booleanPreferencesKey("block_mode_active")
    val BYPASS_EXPIRES_AT = longPreferencesKey("bypass_expires_at")
    val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
    val TAG_UID = stringPreferencesKey("tag_uid")
    val TAG_NDEF_URI = stringPreferencesKey("tag_ndef_uri")
    val TAG_MODE = stringPreferencesKey("tag_mode")
    val TAG_LINKED_AT = longPreferencesKey("tag_linked_at")
    val TAG_DISPATCH_TECH = stringPreferencesKey("tag_dispatch_tech")
    val IMPORTANT_PEOPLE = stringPreferencesKey("important_people")
    val SESSION_STARTED_AT = longPreferencesKey("session_started_at")

    /**
     * When Monolith was last turned on. Distinct from [SESSION_STARTED_AT], which an app
     * unlock fast-forwards past its window: the streak restarts there, but the cycle does
     * not, and the notification draws the whole cycle including the holes in it.
     */
    val CYCLE_STARTED_AT = longPreferencesKey("cycle_started_at")
    val BLOCK_SESSIONS = stringPreferencesKey("block_sessions")
    val BLOCK_HITS = stringPreferencesKey("block_hits")
    val APP_UNLOCKS = stringPreferencesKey("app_unlocks")
    val PAUSE_LOG = stringPreferencesKey("pause_log")
    val APP_CODE_BREAKERS = stringPreferencesKey("app_code_breakers")
    val BLOCK_SCHEDULES = stringPreferencesKey("block_schedules")
    val SCHEDULE_LAST_FIRE = longPreferencesKey("schedule_last_fire_handled_at")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val STRICTNESS_LEVEL = stringPreferencesKey("strictness_level")
}

@Singleton
class MonolithPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether the one-time setup flow (permissions, app picking, tag linking) has been walked
     * through. Permissions can be revoked by Android long after that -- an accessibility service
     * killed off, a "restricted settings" reset -- and those users need the permission step back,
     * not the whole tour again.
     *
     * Installs that finished onboarding before this flag existed have no value stored, so a
     * linked tag or a non-empty block list stands in as proof the tour was completed.
     */
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED]
            ?: (prefs[Keys.TAG_UID] != null || !prefs[Keys.BLOCKED_PACKAGES].isNullOrEmpty())
    }

    suspend fun setOnboardingCompleted() {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = true }
    }

    /**
     * Which escape hatches the user left themselves. Stored by enum name so a level renamed or
     * reordered later can't silently reinterpret an existing install's choice as a different one.
     *
     * Absent for every install that predates the setting, and for anyone who skipped tag linking
     * during setup; both resolve to [StrictnessLevel.DEFAULT], which is the behaviour they have.
     */
    val strictnessLevel: Flow<StrictnessLevel> = context.dataStore.data.map { prefs ->
        StrictnessLevel.fromStorage(prefs[Keys.STRICTNESS_LEVEL])
    }

    suspend fun setStrictnessLevel(level: StrictnessLevel) {
        context.dataStore.edit { it[Keys.STRICTNESS_LEVEL] = level.name }
    }

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
                val now = System.currentTimeMillis()
                prefs[Keys.SESSION_STARTED_AT] = now
                prefs[Keys.CYCLE_STARTED_AT] = now
            } else if (!active && wasActive) {
                val startedAt = prefs[Keys.SESSION_STARTED_AT]
                if (startedAt != null) {
                    commitRunningSegment(prefs, startedAt, System.currentTimeMillis())
                }
                prefs.remove(Keys.SESSION_STARTED_AT)
                prefs.remove(Keys.CYCLE_STARTED_AT)
            }
        }
    }

    /**
     * Starts emergency bypass and ends the current streak the same way [grantAppUnlock] does:
     * the running segment up to now is committed (carving out the bypass window itself), and the
     * session clock is fast-forwarded past the bypass window so nothing accrues during it.
     */
    suspend fun startBypass(durationMillis: Long) {
        context.dataStore.edit { prefs ->
            val now = System.currentTimeMillis()
            prefs[Keys.BYPASS_EXPIRES_AT] = now + durationMillis
            appendPause(prefs, PauseType.BYPASS, now)

            val isActive = prefs[Keys.BLOCK_MODE_ACTIVE] ?: false
            val startedAt = prefs[Keys.SESSION_STARTED_AT]
            if (isActive && startedAt != null) {
                if (now > startedAt) commitRunningSegment(prefs, startedAt, now)
                prefs[Keys.SESSION_STARTED_AT] = maxOf(startedAt, now) + durationMillis
            }
        }
    }

    suspend fun clearBypass() {
        context.dataStore.edit { it.remove(Keys.BYPASS_EXPIRES_AT) }
    }

    /**
     * Ends a running bypass and every live app unlock now, rather than when they would have run
     * out. The bypass expiry is moved to now instead of removed, so it still reads as used: were
     * it refunded, bypasses could be chained back to back without ever touching the tag.
     *
     * Both pauses fast-forwarded the session clock past their window; it comes back to now so held
     * time counts again from the moment blocking resumes. The bypass carve-out in
     * [commitRunningSegment] (and TimeSavedCalculator.ongoingSessions) still works out the window
     * as expiry minus the full duration, but with the session starting at the new expiry every
     * clamp collapses onto that start, so nothing before it is credited twice.
     */
    suspend fun endPauses() {
        context.dataStore.edit { prefs ->
            val now = System.currentTimeMillis()

            val bypassExpiresAt = prefs[Keys.BYPASS_EXPIRES_AT]
            if (bypassExpiresAt != null && bypassExpiresAt > now) prefs[Keys.BYPASS_EXPIRES_AT] = now

            val unlocks = decodeAppUnlocks(prefs[Keys.APP_UNLOCKS])
            if (unlocks.any { it.expiresAt > now }) {
                prefs[Keys.APP_UNLOCKS] = json.encodeToString(unlocks.filter { it.expiresAt <= now })
            }

            val isActive = prefs[Keys.BLOCK_MODE_ACTIVE] ?: false
            val startedAt = prefs[Keys.SESSION_STARTED_AT]
            if (isActive && startedAt != null && startedAt > now) prefs[Keys.SESSION_STARTED_AT] = now
        }
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
        val newSegments = runningSegments(prefs, startedAt, now)
        if (newSegments.isEmpty()) return
        val cutoff = now - SESSION_RETENTION_MILLIS
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
            appendPause(prefs, PauseType.UNLOCK, now)

            val codeBreakers = decodeCodeBreakers(prefs[Keys.APP_CODE_BREAKERS]).filterNot { it.packageName == packageName }
            prefs[Keys.APP_CODE_BREAKERS] = json.encodeToString(codeBreakers)
        }
    }

    /**
     * Records that [packageName] was reached for and blocked, applying [BlockHitLog]'s dedupe
     * and retention rules inside the atomic edit so concurrent hits can't clobber each other.
     * Returns whether a hit was actually written, which the dedupe can decline.
     */
    suspend fun recordBlockHit(packageName: String): Boolean {
        var recorded = false
        context.dataStore.edit { prefs ->
            // Reset per attempt: DataStore can run this block more than once for one call, and a
            // retry that dedupes must not inherit the first attempt's answer.
            recorded = false
            val existing = decodeBlockHits(prefs[Keys.BLOCK_HITS])
                .map { BlockHit(it.packageName, it.atMillis) }
            val updated = BlockHitLog.record(existing, packageName, System.currentTimeMillis())
                ?: return@edit
            prefs[Keys.BLOCK_HITS] = json.encodeToString(
                updated.map { BlockHitDto(it.packageName, it.atMillis) },
            )
            recorded = true
        }
        return recorded
    }

    val blockHits: Flow<List<BlockHit>> = context.dataStore.data.map { prefs ->
        decodeBlockHits(prefs[Keys.BLOCK_HITS]).map { BlockHit(it.packageName, it.atMillis) }
    }

    /** Every bypass and app unlock of the last [PauseLog.RETENTION_MILLIS], for the leaderboard's daily counts. */
    val pauses: Flow<List<Pause>> = context.dataStore.data.map { prefs -> decodePauses(prefs[Keys.PAUSE_LOG]) }

    private fun appendPause(prefs: MutablePreferences, type: PauseType, now: Long) {
        val updated = PauseLog.record(decodePauses(prefs[Keys.PAUSE_LOG]), type, now)
        prefs[Keys.PAUSE_LOG] = json.encodeToString(updated.map { PauseDto(it.type.name, it.atMillis) })
    }

    private fun decodePauses(raw: String?): List<Pause> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<PauseDto>>(raw) }
            .getOrDefault(emptyList())
            .mapNotNull { dto -> runCatching { PauseType.valueOf(dto.type) }.getOrNull()?.let { Pause(it, dto.at) } }
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
            dispatchTech = prefs[Keys.TAG_DISPATCH_TECH],
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
            if (link.dispatchTech != null) {
                prefs[Keys.TAG_DISPATCH_TECH] = link.dispatchTech
            } else {
                prefs.remove(Keys.TAG_DISPATCH_TECH)
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

    /**
     * Replaces [original] with [updated] in place, in one edit, so the entry keeps its position
     * in the list rather than jumping to the end the way a remove-then-add would. A no-op if
     * [original] is gone, which means a stale edit dialog can't resurrect a deleted person.
     */
    suspend fun updateImportantPerson(original: ImportantPerson, updated: ImportantPerson) {
        context.dataStore.edit { prefs ->
            val current = decodeImportantPeople(prefs[Keys.IMPORTANT_PEOPLE])
            val index = current.indexOfFirst {
                it.packageName == original.packageName && it.name == original.name && it.handle == original.handle
            }
            if (index < 0) return@edit
            val replaced = current.toMutableList().also {
                it[index] = ImportantPersonDto(updated.packageName, updated.name, updated.handle)
            }
            prefs[Keys.IMPORTANT_PEOPLE] = json.encodeToString(replaced)
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

    val cycleStartedAt: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[Keys.CYCLE_STARTED_AT]
    }

    private fun decodeBlockSessions(raw: String?): List<BlockSessionDto> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<BlockSessionDto>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun exportSnapshot(): BackupSnapshot =
        readSnapshot(context.dataStore.data.first(), System.currentTimeMillis())

    suspend fun restoreSnapshot(snapshot: BackupSnapshot) {
        context.dataStore.edit { applySnapshot(it, snapshot) }
    }
}

private val snapshotJson = Json { ignoreUnknownKeys = true }

private inline fun <reified T> decodeList(raw: String?): List<T> {
    if (raw == null) return emptyList()
    return runCatching { snapshotJson.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
}

internal fun readSnapshot(prefs: Preferences, now: Long): BackupSnapshot = BackupSnapshot(
    createdAt = now,
    // A running session is only written to history when it ends; back it up as ending now, so a
    // restore carries today's time gained without turning Monolith on for the other phone.
    sessions = (decodeList<BlockSessionDto>(prefs[Keys.BLOCK_SESSIONS]) + activeSegments(prefs, now))
        .map { BackupSnapshot.SessionEntry(it.start, it.end) },
    blockedPackages = prefs[Keys.BLOCKED_PACKAGES].orEmpty().toList(),
    importantPeople = decodeList<ImportantPersonDto>(prefs[Keys.IMPORTANT_PEOPLE])
        .map { BackupSnapshot.PersonEntry(it.packageName, it.name, it.handle) },
    schedules = decodeList<BlockScheduleDto>(prefs[Keys.BLOCK_SCHEDULES])
        .map { BackupSnapshot.ScheduleEntry(it.id, it.enabled, it.days, it.startMinuteOfDay) },
    strictness = prefs[Keys.STRICTNESS_LEVEL],
)

private fun activeSegments(prefs: Preferences, now: Long): List<BlockSessionDto> {
    if (prefs[Keys.BLOCK_MODE_ACTIVE] != true) return emptyList()
    val startedAt = prefs[Keys.SESSION_STARTED_AT] ?: return emptyList()
    return runningSegments(prefs, startedAt, now)
}

/**
 * What the session running since [startedAt] adds to history if it ends at [now]. [startedAt] can
 * sit in the future: [MonolithPreferences.grantAppUnlock] fast-forwards the session clock past an
 * app's unlock window, and nothing has accrued yet in that case. Bypass (emergency mode) minutes
 * don't count toward time gained, so the bypass window is carved out rather than credited.
 */
private fun runningSegments(prefs: Preferences, startedAt: Long, now: Long): List<BlockSessionDto> {
    if (now <= startedAt) return emptyList()
    val bypassExpiresAt = prefs[Keys.BYPASS_EXPIRES_AT]?.takeIf { it > 0 }
        ?: return listOf(BlockSessionDto(startedAt, now))
    val bypassStartedAt = bypassExpiresAt - BlockState.BYPASS_DURATION_MILLIS
    val beforeBypassEnd = bypassStartedAt.coerceIn(startedAt, now)
    val afterBypassStart = bypassExpiresAt.coerceIn(startedAt, now)
    return listOfNotNull(
        BlockSessionDto(startedAt, beforeBypassEnd).takeIf { beforeBypassEnd > startedAt },
        BlockSessionDto(afterBypassStart, now).takeIf { now > afterBypassStart },
    )
}

/** Replaces exactly the five backed-up keys; everything device-bound is left alone. */
internal fun applySnapshot(prefs: MutablePreferences, snapshot: BackupSnapshot) {
    prefs[Keys.BLOCK_SESSIONS] = snapshotJson.encodeToString(
        snapshot.sessions.map { BlockSessionDto(it.start, it.end) },
    )
    prefs[Keys.BLOCKED_PACKAGES] = snapshot.blockedPackages.toSet()
    prefs[Keys.IMPORTANT_PEOPLE] = snapshotJson.encodeToString(
        snapshot.importantPeople.map { ImportantPersonDto(it.packageName, it.name, it.handle) },
    )
    prefs[Keys.BLOCK_SCHEDULES] = snapshotJson.encodeToString(
        snapshot.schedules.map { BlockScheduleDto(it.id, it.enabled, it.days, it.startMinuteOfDay) },
    )
    val strictness = snapshot.strictness
    if (strictness == null) prefs.remove(Keys.STRICTNESS_LEVEL) else prefs[Keys.STRICTNESS_LEVEL] = strictness
}
