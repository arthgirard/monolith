package com.monolith.app.domain.usecase

import android.nfc.Tag
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.TagProvisioner
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class LinkNfcTagUseCase @Inject constructor(
    private val tagProvisioner: TagProvisioner,
    private val blockRepository: BlockRepository,
    private val backupRepository: BackupRepository,
) {
    suspend operator fun invoke(tag: Tag, newInstall: Boolean = false): NfcLinkResult {
        // Re-linking while Monolith is on is a way out of an active session: any blank tag
        // becomes the new key, and the next tap with it unlocks. That undoes the whole point of
        // the tag being something you have to go and find, so it follows the same rule as the
        // block list -- edits happen while Monolith is off, or not at all.
        if (blockRepository.observeBlockState().first().isActive) {
            return NfcLinkResult.Locked
        }
        // The code rides along only while backup is on: with it off there is nothing behind it
        // for a reinstall to restore. A [newInstall] backs up by default, but only turns it on
        // once setup is done, so its first upload carries the apps chosen after this step.
        val backsUp = newInstall || backupRepository.observeBackupEnabled().first()
        val code = if (backsUp) backupRepository.localRecoveryCode() else null
        val result = tagProvisioner.provisionTag(tag, code)
        if (result is NfcLinkResult.Success) {
            blockRepository.saveLinkedTag(result.link)
        }
        return result
    }
}
