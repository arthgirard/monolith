package com.monolith.app.data.repository

import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.widget.TimeSavedWidgetRefresher
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockRepositoryImpl @Inject constructor(
    private val preferences: MonolithPreferences,
    private val widgetRefresher: TimeSavedWidgetRefresher,
) : BlockRepository {

    override fun observeBlockState(): Flow<BlockState> = preferences.blockState

    // Each of these three moves today's saved total: starting or stopping blocking opens or
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

    override fun observeLinkedTag(): Flow<NfcTagLink?> = preferences.linkedTag

    override suspend fun saveLinkedTag(link: NfcTagLink) {
        preferences.saveLinkedTag(link)
    }

    override fun observeBlockSessions(): Flow<List<BlockSession>> = preferences.blockSessions

    override fun observeActiveSessionStart(): Flow<Long?> = preferences.activeSessionStart
}
