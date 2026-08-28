package com.monolith.app.domain.usecase

import android.nfc.Tag
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.repository.TagProvisioner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkNfcTagUseCaseTest {

    /**
     * android.nfc.Tag has no constructor to call, and the guard under test returns before the tag
     * is ever read, so an empty instance is all these tests need.
     */
    private val tag: Tag = allocateTag()

    private class FakeTagProvisioner(private val result: NfcLinkResult) : TagProvisioner {
        var provisionCount: Int = 0
            private set

        override suspend fun provisionTag(tag: Tag): NfcLinkResult {
            provisionCount++
            return result
        }

        override fun identifyTag(tag: Tag): String = "id"

        override fun dispatchTechFor(tag: Tag): String? = null
    }

    private val link = NfcTagLink(uid = "abc", mode = TagLinkMode.FALLBACK_UID, linkedAtMillis = 0L)

    @Test
    fun `linking while monolith is off saves the tag`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val blockRepository = FakeBlockRepository(initiallyActive = false, linkedTag = null)

        val result = LinkNfcTagUseCase(provisioner, blockRepository)(tag)

        assertEquals(NfcLinkResult.Success(link), result)
        assertEquals(link, blockRepository.observeLinkedTag().first())
    }

    @Test
    fun `an active monolith refuses to swap the key it is holding`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val blockRepository = FakeBlockRepository(initiallyActive = true)

        val result = LinkNfcTagUseCase(provisioner, blockRepository)(tag)

        assertEquals(NfcLinkResult.Locked, result)
        assertEquals("a refused link must not write to the tag either", 0, provisioner.provisionCount)
    }

    @Test
    fun `a failed provision links nothing`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Failure("unreadable"))
        val blockRepository = FakeBlockRepository(initiallyActive = false, linkedTag = null)

        val result = LinkNfcTagUseCase(provisioner, blockRepository)(tag)

        assertEquals(NfcLinkResult.Failure("unreadable"), result)
        assertNull(blockRepository.observeLinkedTag().first())
    }
}

private fun allocateTag(): Tag {
    val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null)
    val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, Tag::class.java) as Tag
}
