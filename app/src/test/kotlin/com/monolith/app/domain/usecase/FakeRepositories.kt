package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockHit
import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.ScheduleRepository
import com.monolith.app.domain.repository.StrictnessRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory stand-ins for the DataStore-backed repositories. They mirror the real writes the
 * tests care about: [FakeBlockRepository.sessionStartCount] stands in for SESSION_STARTED_AT
 * being restamped, a tag is linked by default because most callers assume a set-up install, and [FakeScheduleRepository] enforces the same forward-only watermark rule as
 * MonolithPreferences.setScheduleLastFire.
 */
class FakeBlockRepository(
    initiallyActive: Boolean = false,
    linkedTag: NfcTagLink? = NfcTagLink(uid = "tag", mode = TagLinkMode.FALLBACK_UID),
) : BlockRepository {
    private val state = MutableStateFlow(BlockState(isActive = initiallyActive))

    /** How many times blocking went from off to on -- i.e. how many streaks were started. */
    var sessionStartCount: Int = 0
        private set
    var clearBypassCount: Int = 0
        private set

    override fun observeBlockState(): Flow<BlockState> = state

    override suspend fun setBlockModeActive(active: Boolean) {
        if (active && !state.value.isActive) sessionStartCount++
        state.value = state.value.copy(isActive = active)
    }

    override suspend fun startBypass(durationMillis: Long) {
        state.value = state.value.copy(bypassExpiresAtMillis = System.currentTimeMillis() + durationMillis)
    }

    override suspend fun clearBypass() {
        clearBypassCount++
        state.value = state.value.copy(bypassExpiresAtMillis = null)
    }

    private val storedLink = MutableStateFlow(linkedTag)

    override fun observeLinkedTag(): Flow<NfcTagLink?> = storedLink

    override suspend fun saveLinkedTag(link: NfcTagLink) {
        storedLink.value = link
    }

    override fun observeBlockSessions(): Flow<List<BlockSession>> = MutableStateFlow(emptyList())

    private val activeSessionStart = MutableStateFlow<Long?>(null)

    fun setActiveSessionStart(startedAt: Long?) { activeSessionStart.value = startedAt }

    override fun observeActiveSessionStart(): Flow<Long?> = activeSessionStart

    val cycleStart = MutableStateFlow<Long?>(null)

    override fun observeCycleStart(): Flow<Long?> = cycleStart

    private val hits = MutableStateFlow<List<BlockHit>>(emptyList())

    override suspend fun recordBlockHit(packageName: String) {
        hits.value = BlockHitLog.record(hits.value, packageName, System.currentTimeMillis()) ?: hits.value
    }

    override fun observeBlockHits(): Flow<List<BlockHit>> = hits
}

class FakeAppUnlockRepository : AppUnlockRepository {
    private val unlocks = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val codeBreakers = MutableStateFlow<Map<String, CodeBreaker>>(emptyMap())

    var clearUnlocksCount: Int = 0
        private set

    override fun observeUnlockedPackages(): Flow<Map<String, Long>> = unlocks

    override fun observeCodeBreakers(): Flow<Map<String, CodeBreaker>> = codeBreakers

    override suspend fun saveCodeBreaker(packageName: String, codeBreaker: CodeBreaker) {
        codeBreakers.value = codeBreakers.value + (packageName to codeBreaker)
    }

    override suspend fun grantUnlock(packageName: String, durationMillis: Long) {
        unlocks.value = unlocks.value + (packageName to System.currentTimeMillis() + durationMillis)
        codeBreakers.value = codeBreakers.value - packageName
    }

    override suspend fun clearUnlocks() {
        clearUnlocksCount++
        unlocks.value = emptyMap()
        codeBreakers.value = emptyMap()
    }
}

class FakeStrictnessRepository(level: StrictnessLevel = StrictnessLevel.DEFAULT) : StrictnessRepository {
    private val stored = MutableStateFlow(level)

    override fun observeStrictness(): Flow<StrictnessLevel> = stored

    override suspend fun setStrictness(level: StrictnessLevel) {
        stored.value = level
    }

    fun current(): StrictnessLevel = stored.value
}

class FakeScheduleRepository(schedules: List<BlockSchedule> = emptyList()) : ScheduleRepository {
    private val stored = MutableStateFlow(schedules)
    private val lastFire = MutableStateFlow<Long?>(null)

    override fun observeSchedules(): Flow<List<BlockSchedule>> = stored

    override suspend fun saveSchedule(schedule: BlockSchedule) {
        stored.value = stored.value.filterNot { it.id == schedule.id } + schedule
    }

    override suspend fun deleteSchedule(id: String) {
        stored.value = stored.value.filterNot { it.id == id }
    }

    override fun observeLastHandledFire(): Flow<Long?> = lastFire

    override suspend fun setLastHandledFire(millis: Long) {
        // Forward-only, exactly as the real store behaves.
        if (millis > (lastFire.value ?: Long.MIN_VALUE)) lastFire.value = millis
    }

    fun lastHandledFire(): Long? = lastFire.value
}
