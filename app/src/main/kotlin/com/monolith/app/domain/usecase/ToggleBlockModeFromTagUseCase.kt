package com.monolith.app.domain.usecase

import android.nfc.Tag
import com.monolith.app.domain.model.NfcTapResult
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.TagProvisioner
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** A registered tag tap flips Monolith on/off; an unrecognized tag is ignored. */
class ToggleBlockModeFromTagUseCase @Inject constructor(
    private val tagProvisioner: TagProvisioner,
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(tag: Tag): NfcTapResult {
        val linked = blockRepository.observeLinkedTag().first()
            ?: return NfcTapResult.NoTagLinked

        val tappedId = tagProvisioner.identifyTag(tag)
        val matches = tappedId == linked.uid || (linked.ndefUri != null && tappedId == linked.ndefUri)
        if (!matches) return NfcTapResult.UnknownTag

        val current = blockRepository.observeBlockState().first()
        val nowActive = !current.isActive
        blockRepository.setBlockModeActive(nowActive)
        // Any tap, on or off, starts a fresh cycle: clears a running/expired bypass so its
        // countdown can't linger on screen after the tap turned Monolith off, and frees up the
        // one-bypass-per-cycle allowance for next time Monolith comes on.
        blockRepository.clearBypass()
        return NfcTapResult.Toggled(nowActive)
    }
}
