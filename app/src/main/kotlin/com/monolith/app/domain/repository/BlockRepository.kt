package com.monolith.app.domain.repository

import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.NfcTagLink
import kotlinx.coroutines.flow.Flow

interface BlockRepository {
    fun observeBlockState(): Flow<BlockState>

    suspend fun setBlockModeActive(active: Boolean)

    suspend fun startBypass(durationMillis: Long)

    suspend fun clearBypass()

    fun observeLinkedTag(): Flow<NfcTagLink?>

    suspend fun saveLinkedTag(link: NfcTagLink)

    fun observeBlockSessions(): Flow<List<BlockSession>>

    fun observeActiveSessionStart(): Flow<Long?>
}
