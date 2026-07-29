package com.monolith.app.domain.usecase

import android.nfc.Tag
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.TagProvisioner
import javax.inject.Inject

class LinkNfcTagUseCase @Inject constructor(
    private val tagProvisioner: TagProvisioner,
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(tag: Tag): NfcLinkResult {
        val result = tagProvisioner.provisionTag(tag)
        if (result is NfcLinkResult.Success) {
            blockRepository.saveLinkedTag(result.link)
        }
        return result
    }
}
