package com.monolith.app.domain.repository

import com.monolith.app.domain.model.CodeBreaker
import kotlinx.coroutines.flow.Flow

/**
 * Per-package exceptions carved out of enforcement by solving a [CodeBreaker] (and then copying
 * its waiver), distinct from the single global emergency bypass: granting one only exempts that
 * package, every other blocked app stays blocked.
 */
interface AppUnlockRepository {
    /** packageName -> unlock expiry millis. Entries in the past are stale but harmless. */
    fun observeUnlockedPackages(): Flow<Map<String, Long>>

    fun observeCodeBreakers(): Flow<Map<String, CodeBreaker>>

    suspend fun saveCodeBreaker(packageName: String, codeBreaker: CodeBreaker)

    suspend fun grantUnlock(packageName: String, durationMillis: Long)

    /**
     * Drops every unlock and every stored code-breaker, ending the cycle they belong to. Called on
     * a tag tap (see `ToggleBlockModeFromTagUseCase`), so no app stays exempt across a toggle.
     */
    suspend fun clearUnlocks()
}
