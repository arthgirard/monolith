package com.monolith.app.data.leaderboard

import kotlinx.coroutines.flow.Flow

/**
 * The name asked for during setup, which friends see. It outlives the leaderboard identity: that
 * one is cleared whole when the member leaves their last group, and the name belongs to the
 * person. Empty for installs set up before the name was asked.
 */
interface DisplayNameStore {
    val displayName: Flow<String>
    suspend fun setDisplayName(name: String)
}
