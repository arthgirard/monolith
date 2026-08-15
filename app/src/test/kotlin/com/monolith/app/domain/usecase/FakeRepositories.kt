package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSchedule
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory stand-ins for the DataStore-backed repositories. They mirror the real writes the
 * tests care about: [FakeBlockRepository.sessionStartCount] stands in for SESSION_STARTED_AT
 * being restamped, and [FakeScheduleRepository] enforces the same forward-only watermark rule as
 * MonolithPreferences.setScheduleLastFire.
 */
class FakeBlockRepository(initiallyActive: Boolean = false) : BlockRepository {
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

    override fun observeLinkedTag(): Flow<NfcTagLink?> = MutableStateFlow(null)

    override suspend fun saveLinkedTag(link: NfcTagLink) = Unit

    override fun observeBlockSessions(): Flow<List<BlockSession>> = MutableStateFlow(emptyList())

    override fun observeActiveSessionStart(): Flow<Long?> = MutableStateFlow(null)
}

class FakeAppUnlockRepository : AppUnlockRepository {
    var clearUnlocksCount: Int = 0
        private set

    override fun observeUnlockedPackages(): Flow<Map<String, Long>> = MutableStateFlow(emptyMap())

    override fun observeCodeBreakers(): Flow<Map<String, CodeBreaker>> = MutableStateFlow(emptyMap())

    override suspend fun saveCodeBreaker(packageName: String, codeBreaker: CodeBreaker) = Unit

    override suspend fun grantUnlock(packageName: String, durationMillis: Long) = Unit

    override suspend fun clearUnlocks() {
        clearUnlocksCount++
    }
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
