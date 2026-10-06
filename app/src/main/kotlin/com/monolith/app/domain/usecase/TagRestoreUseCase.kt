package com.monolith.app.domain.usecase

import android.nfc.Tag
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.TagProvisioner
import javax.inject.Inject

/** What a tag tapped during setup could bring back. */
sealed interface TagCheck {
    /** No code, one that does not decode, or one whose identity is gone: a blank tag to link. */
    data object NoCode : TagCheck
    data class Offer(val backupAt: Long?, val hasBackup: Boolean) : TagCheck

    /** Offline or a server error. Never treated as a blank tag: that would overwrite the way back. */
    data object Unreachable : TagCheck
    data object Unreadable : TagCheck
}

sealed interface TagRestoreResult {
    data class Done(val hasBackup: Boolean) : TagRestoreResult
    data object Failed : TagRestoreResult
}

/**
 * The setup step's half of restoring from a tag: read the code it carries, ask what it would
 * restore, and on a yes restore it and link the tag as it is. Holds the tag and code between
 * [check] and [restore]; one instance per screen.
 */
class TagRestoreUseCase @Inject constructor(
    private val tagProvisioner: TagProvisioner,
    private val blockRepository: BlockRepository,
    private val restoreBackup: RestoreBackupUseCase,
) {
    private var tag: Tag? = null
    private var code: String? = null
    private var hasBackup = false

    suspend fun check(tag: Tag): TagCheck {
        this.tag = null
        code = null
        val found = tagProvisioner.readCode(tag) ?: return TagCheck.NoCode
        this.tag = tag
        code = found
        return recheck()
    }

    /** Asks again with the code [check] found, after a failure to reach the server. */
    suspend fun recheck(): TagCheck {
        val found = code ?: return TagCheck.NoCode
        return when (val outcome = restoreBackup.prepare(found)) {
            is RestoreOutcome.Ready -> {
                hasBackup = outcome.hasBackup
                TagCheck.Offer(outcome.backupAt, outcome.hasBackup)
            }
            is RestoreOutcome.Failed -> when (outcome.error) {
                LeaderboardError.UNAUTHORIZED -> TagCheck.NoCode
                LeaderboardError.INVALID -> TagCheck.Unreadable
                else -> TagCheck.Unreachable
            }
            // Monolith is on: linking refuses on its own, so there is nothing to offer.
            RestoreOutcome.Refused, RestoreOutcome.Done -> TagCheck.NoCode
        }
    }

    suspend fun restore(): TagRestoreResult {
        val tag = tag ?: return TagRestoreResult.Failed
        val code = code ?: return TagRestoreResult.Failed
        if (restoreBackup.confirm() != RestoreOutcome.Done) return TagRestoreResult.Failed
        // Linked only once the restore went through, and as it is: it already carries this code.
        blockRepository.saveLinkedTag(tagProvisioner.existingLink(tag, code))
        return TagRestoreResult.Done(hasBackup)
    }
}
