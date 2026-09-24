package com.monolith.app.domain.repository

import com.monolith.app.domain.model.BlockHit
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.NfcTagLink
import kotlinx.coroutines.flow.Flow

interface BlockRepository {
    fun observeBlockState(): Flow<BlockState>

    suspend fun setBlockModeActive(active: Boolean)

    suspend fun startBypass(durationMillis: Long)

    suspend fun clearBypass()

    /**
     * Closes every pause still running: the emergency bypass and any per-app unlocks, in one
     * write. The bypass stays spent for the cycle; only a tag tap gives it back.
     */
    suspend fun endPauses()

    fun observeLinkedTag(): Flow<NfcTagLink?>

    suspend fun saveLinkedTag(link: NfcTagLink)

    fun observeBlockSessions(): Flow<List<BlockSession>>

    fun observeActiveSessionStart(): Flow<Long?>

    /**
     * When Monolith was last turned on, or null while it is off. Unlike
     * [observeActiveSessionStart] this survives an app unlock rather than being moved past its
     * window, so it is the left edge of the session the notification draws.
     */
    fun observeCycleStart(): Flow<Long?>

    suspend fun recordBlockHit(packageName: String)

    fun observeBlockHits(): Flow<List<BlockHit>>
}
