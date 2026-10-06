package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkNfcTagUseCaseTest {

    private val tag = allocateTag()
    private val link = NfcTagLink(uid = "abc", mode = TagLinkMode.FALLBACK_UID, linkedAtMillis = 0L)
    private val code = "c".repeat(43)

    @Test
    fun `linking while monolith is off saves the tag`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val blockRepository = FakeBlockRepository(initiallyActive = false, linkedTag = null)

        val result = LinkNfcTagUseCase(provisioner, blockRepository, FakeBackupRepository())(tag)

        assertEquals(NfcLinkResult.Success(link), result)
        assertEquals(link, blockRepository.observeLinkedTag().first())
    }

    @Test
    fun `an active monolith refuses to swap the key it is holding`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val blockRepository = FakeBlockRepository(initiallyActive = true)

        val result = LinkNfcTagUseCase(provisioner, blockRepository, FakeBackupRepository())(tag)

        assertEquals(NfcLinkResult.Locked, result)
        assertEquals("a refused link must not write to the tag either", 0, provisioner.provisionCount)
    }

    @Test
    fun `a failed provision links nothing`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Failure("unreadable"))
        val blockRepository = FakeBlockRepository(initiallyActive = false, linkedTag = null)

        val result = LinkNfcTagUseCase(provisioner, blockRepository, FakeBackupRepository())(tag)

        assertEquals(NfcLinkResult.Failure("unreadable"), result)
        assertNull(blockRepository.observeLinkedTag().first())
    }

    @Test
    fun `with backup on the tag is given the recovery code`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val backup = FakeBackupRepository(enabled = true).apply { localCode = code }

        LinkNfcTagUseCase(provisioner, FakeBlockRepository(initiallyActive = false), backup)(tag)

        assertEquals(listOf<String?>(code), provisioner.provisionedCodes)
    }

    @Test
    fun `with backup off the tag gets no code`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val backup = FakeBackupRepository(enabled = false).apply { localCode = code }

        LinkNfcTagUseCase(provisioner, FakeBlockRepository(initiallyActive = false), backup)(tag)

        assertEquals(listOf<String?>(null), provisioner.provisionedCodes)
    }

    @Test
    fun `a new install's tag gets the code before backup is on`() = runBlocking {
        // Setup turns backup on only once it is finished, so the first upload carries the apps.
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val backup = FakeBackupRepository(enabled = false).apply { localCode = code }

        LinkNfcTagUseCase(provisioner, FakeBlockRepository(initiallyActive = false), backup)(tag, newInstall = true)

        assertEquals(listOf<String?>(code), provisioner.provisionedCodes)
    }
}
