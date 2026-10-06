package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ToggleBlockModeFromTagUseCaseTest {

    private val tag = allocateTag()
    private val code = "a".repeat(43)
    private val smart = NfcTagLink(uid = "tag", mode = TagLinkMode.SMART_NDEF, ndefUri = "monolith://tag/tag", linkedAtMillis = 0L)

    private fun toggle(provisioner: FakeTagProvisioner, blocks: FakeBlockRepository) =
        ToggleBlockModeFromTagUseCase(provisioner, blocks, FakeAppUnlockRepository())

    @Test
    fun `a tap records the code the tag carries`() = runBlocking {
        val blocks = FakeBlockRepository(initiallyActive = false, linkedTag = smart)

        toggle(FakeTagProvisioner(codeOnTag = code), blocks)(tag)

        assertEquals(code, blocks.observeLinkedTag().first()!!.code)
    }

    @Test
    fun `a tap that reads no code keeps the one on record`() = runBlocking {
        // A missed read is not proof the code is gone; it must not raise the Settings row.
        val blocks = FakeBlockRepository(initiallyActive = false, linkedTag = smart.copy(code = code))

        toggle(FakeTagProvisioner(codeOnTag = null), blocks)(tag)

        assertEquals(code, blocks.observeLinkedTag().first()!!.code)
    }

    @Test
    fun `a UID-only tag is never read for a code`() = runBlocking {
        val uidOnly = NfcTagLink(uid = "tag", mode = TagLinkMode.FALLBACK_UID, linkedAtMillis = 0L, dispatchTech = "NfcA")
        val provisioner = FakeTagProvisioner(codeOnTag = code)

        toggle(provisioner, FakeBlockRepository(initiallyActive = false, linkedTag = uidOnly))(tag)

        assertEquals(0, provisioner.readCount)
    }
}
