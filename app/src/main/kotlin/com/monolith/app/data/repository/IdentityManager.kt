package com.monolith.app.data.repository

import com.monolith.app.data.backup.BackupCrypto
import com.monolith.app.data.leaderboard.DisplayNameStore
import com.monolith.app.data.leaderboard.IdentityStore
import com.monolith.app.data.leaderboard.LeaderboardApi
import com.monolith.app.data.leaderboard.RegisterRequest
import com.monolith.app.data.leaderboard.RotateTokenRequest
import com.monolith.app.domain.model.Identity
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.model.andThen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one identity the friends groups and the backup share. The phone makes it: a random master
 * (the recovery code) derives the token, so the server never picks, or sees, the master.
 */
@Singleton
class IdentityManager @Inject constructor(
    private val api: LeaderboardApi,
    private val store: IdentityStore,
    private val names: DisplayNameStore,
) {
    // A create and turning backup on at once must not register two identities.
    private val mutex = Mutex()

    /**
     * Makes sure an identity with a master is stored: registers one when there is none, and
     * migrates one from before backups (its server-issued token) to a phone-generated token.
     * A failed migration leaves that identity as it was, unless the answer was a 401: the token
     * is dead, and it goes like any other. A new one takes [displayName], or else the name given
     * during setup.
     */
    suspend fun ensureIdentity(displayName: String?): LeaderboardResult<Unit> = mutex.withLock {
        val identity = store.identity.first()
        when {
            identity == null -> register((displayName ?: names.displayName.first()).trim().takeIf { it.isNotEmpty() })
            identity.master == null -> migrate(identity)
            else -> LeaderboardResult.Ok(Unit)
        }
    }

    /**
     * The recovery code a tag linked now should carry: the identity's, or one made here and kept
     * until it registers. Null for an identity from before backups, whose code exists only once it
     * migrates.
     */
    suspend fun localMaster(): String? {
        store.identity.first()?.let { return it.master }
        return unregisteredMaster()
    }

    // Its own lock: [mutex] is held across a registration's network call, and a tag being linked
    // has to get its code while it is still against the phone.
    private val masterMutex = Mutex()

    /** Made once and kept: a registration in flight and a tag being linked must agree on it. */
    private suspend fun unregisteredMaster(): String = masterMutex.withLock {
        store.unregisteredMaster.first()
            ?: BackupCrypto.encodeCode(BackupCrypto.newMaster()).also { store.saveUnregisteredMaster(it) }
    }

    private suspend fun register(displayName: String?): LeaderboardResult<Unit> {
        // The same master a tag linked meanwhile is given, or the tag would carry a code that
        // restores nothing.
        val master = BackupCrypto.decodeCode(unregisteredMaster()) ?: BackupCrypto.newMaster()
        val token = BackupCrypto.deriveToken(master)
        return api.register(RegisterRequest(token, displayName)).andThen {
            // A restore may have stored another identity meanwhile; this one is then left unused.
            if (store.identity.first() == null) {
                store.save(Identity(token, displayName.orEmpty(), emptyList(), BackupCrypto.encodeCode(master)))
            }
        }
    }

    private suspend fun migrate(legacy: Identity): LeaderboardResult<Unit> {
        // Stored before asking, and reused on a retry: the server may rotate and the answer be lost.
        val pending = store.pendingMaster.first()
        val code = pending ?: BackupCrypto.encodeCode(BackupCrypto.newMaster()).also { store.savePendingMaster(it) }
        val token = BackupCrypto.deriveToken(checkNotNull(BackupCrypto.decodeCode(code)))
        val result = guarded(legacy) {
            val rotated = api.rotateToken(legacy.token, RotateTokenRequest(token))
            if (pending != null && rotated is LeaderboardResult.Err && rotated.error == LeaderboardError.UNAUTHORIZED) {
                // The old token is dead: an earlier try may have rotated it to this one.
                api.me(token).andThen { }
            } else {
                rotated
            }
        }
        return result.andThen {
            val current = store.identity.first()
            if (current?.token == legacy.token) store.save(current.copy(token = token, master = code))
        }
    }

    /**
     * Runs [block] for [identity], and clears the store when it answers UNAUTHORIZED: the token is
     * dead. Only if it is still the stored one: a late 401 must not clear a newer identity.
     */
    suspend fun <T> guarded(identity: Identity, block: suspend () -> LeaderboardResult<T>): LeaderboardResult<T> {
        val result = block()
        if (result is LeaderboardResult.Err && result.error == LeaderboardError.UNAUTHORIZED &&
            store.identity.first()?.token == identity.token
        ) {
            // Without a cached group the member wasn't in anything to be removed from: the last
            // leave's refresh was offline, the token predates the multi-group server, or the
            // identity only ever held a backup.
            store.clear(removed = identity.groups.isNotEmpty())
        }
        return result
    }
}
