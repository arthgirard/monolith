package com.monolith.app.data.repository

import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockRepositoryImpl @Inject constructor(
    private val preferences: MonolithPreferences,
) : BlockRepository {

    override fun observeBlockState(): Flow<BlockState> = preferences.blockState

    override suspend fun setBlockModeActive(active: Boolean) {
        preferences.setBlockModeActive(active)
    }

    override suspend fun startBypass(durationMillis: Long) {
        preferences.startBypass(durationMillis)
    }

    override suspend fun clearBypass() {
        preferences.clearBypass()
    }

    override fun observeLinkedTag(): Flow<NfcTagLink?> = preferences.linkedTag

    override suspend fun saveLinkedTag(link: NfcTagLink) {
        preferences.saveLinkedTag(link)
    }

    override fun observeBlockSessions(): Flow<List<BlockSession>> = preferences.blockSessions

    override fun observeActiveSessionStart(): Flow<Long?> = preferences.activeSessionStart
}
