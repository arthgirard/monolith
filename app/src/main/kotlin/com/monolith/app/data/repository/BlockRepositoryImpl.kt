package com.monolith.app.data.repository

import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.BlockHit
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.nfc.NfcDispatchGate
import com.monolith.app.widget.TimeSavedWidgetRefresher
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockRepositoryImpl @Inject constructor(
    private val preferences: MonolithPreferences,
    private val widgetRefresher: TimeSavedWidgetRefresher,
    private val nfcDispatchGate: NfcDispatchGate,
) : BlockRepository {

    override fun observeBlockState(): Flow<BlockState> = preferences.blockState

    // Each of these moves today's saved total: starting or stopping blocking opens or
    // closes a session, and a bypass pauses and resumes the clock. The widget's own refresh floor
    // is 30 minutes, far too slow to look honest right after a tag tap.
    override suspend fun setBlockModeActive(active: Boolean) {
        preferences.setBlockModeActive(active)
        widgetRefresher.refresh()
    }

    override suspend fun startBypass(durationMillis: Long) {
        preferences.startBypass(durationMillis)
        widgetRefresher.refresh()
    }

    override suspend fun clearBypass() {
        preferences.clearBypass()
        widgetRefresher.refresh()
    }

    override suspend fun endPauses() {
        preferences.endPauses()
        widgetRefresher.refresh()
    }

    override fun observeLinkedTag(): Flow<NfcTagLink?> = preferences.linkedTag

    override suspend fun saveLinkedTag(link: NfcTagLink) {
        preferences.saveLinkedTag(link)
        // Applied here rather than left to the next app start: linking a UID tag has to start
        // working immediately, and re-linking from a UID tag to an NDEF one has to stop Monolith
        // listening for that old technology just as promptly.
        nfcDispatchGate.apply(link)
    }

    override fun observeBlockSessions(): Flow<List<BlockSession>> = preferences.blockSessions

    override fun observeActiveSessionStart(): Flow<Long?> = preferences.activeSessionStart

    override fun observeCycleStart(): Flow<Long?> = preferences.cycleStartedAt

    // A hit still doesn't move the time-saved total, but the widget's taller sizes report today's
    // block count beneath the chart, and that number would sit stale until the next tick. Only a
    // hit that actually landed is worth a redraw: the dedupe declines the repeat events one reach
    // produces, and each of those would otherwise cost a broadcast and a fresh chart bitmap.
    override suspend fun recordBlockHit(packageName: String) {
        if (preferences.recordBlockHit(packageName)) widgetRefresher.refresh()
    }

    override fun observeBlockHits(): Flow<List<BlockHit>> = preferences.blockHits
}
