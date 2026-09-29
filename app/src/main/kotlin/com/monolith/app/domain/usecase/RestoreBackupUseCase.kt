package com.monolith.app.domain.usecase

import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.FetchedBackup
import com.monolith.app.domain.repository.LeaderboardRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

sealed interface RestoreOutcome {
    /** Fetched and readable, waiting for [RestoreBackupUseCase.confirm]. */
    data class Ready(val backupAt: Long?, val hasBackup: Boolean) : RestoreOutcome

    /** Monolith is on: a restore would swap the blocked apps mid-session. */
    data object Refused : RestoreOutcome
    data class Failed(val error: LeaderboardError) : RestoreOutcome
    data object Done : RestoreOutcome
}

/** Replaces this phone's history and setup with a snapshot, in one write. */
interface SnapshotWriter {
    suspend fun write(snapshot: BackupSnapshot)
}

/** What has to catch up with restored data: the widget, and the schedule alarm. */
interface AfterRestore {
    suspend fun run()
}

/**
 * Restores in two steps so the user sees what they are about to replace: [prepare] reads the
 * backup and writes nothing; [confirm] writes it. Both refuse while Monolith is on, since it can
 * be turned on between the two.
 */
class RestoreBackupUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
    private val backupRepository: BackupRepository,
    private val leaderboardRepository: LeaderboardRepository,
    private val snapshotWriter: SnapshotWriter,
    private val afterRestore: AfterRestore,
) {
    private var prepared: FetchedBackup? = null

    suspend fun prepare(code: String): RestoreOutcome {
        prepared = null
        if (isActive()) return RestoreOutcome.Refused
        return when (val result = backupRepository.fetch(code)) {
            is LeaderboardResult.Err -> RestoreOutcome.Failed(result.error)
            is LeaderboardResult.Ok -> {
                prepared = result.value
                val backup = result.value
                RestoreOutcome.Ready(backupAt = backup.me.backupAt ?: backup.snapshot?.createdAt, hasBackup = backup.snapshot != null)
            }
        }
    }

    suspend fun confirm(): RestoreOutcome {
        val backup = prepared ?: return RestoreOutcome.Failed(LeaderboardError.INVALID)
        if (isActive()) return RestoreOutcome.Refused
        // The identity first: it is the step that can fail (it asks the server), and a failure
        // there must leave this phone's data untouched.
        val saved = leaderboardRepository.restore(backup.master)
        if (saved is LeaderboardResult.Err) return RestoreOutcome.Failed(saved.error)
        prepared = null
        val snapshot = backup.snapshot ?: return RestoreOutcome.Done
        snapshotWriter.write(snapshot)
        // Only fails without an identity, and one was just saved. Enabled after the write, so the
        // upload this triggers carries the restored data.
        backupRepository.setBackupEnabled(true)
        afterRestore.run()
        return RestoreOutcome.Done
    }

    private suspend fun isActive(): Boolean = blockRepository.observeBlockState().first().isActive
}
