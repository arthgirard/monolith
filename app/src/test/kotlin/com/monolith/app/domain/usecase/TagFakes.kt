package com.monolith.app.domain.usecase

import android.nfc.Tag
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.repository.TagProvisioner

class FakeTagProvisioner(
    var result: NfcLinkResult = NfcLinkResult.Failure("unset"),
    var codeOnTag: String? = null,
    var id: String = "tag",
) : TagProvisioner {
    val provisionedCodes = mutableListOf<String?>()
    val provisionCount get() = provisionedCodes.size
    var readCount = 0
        private set

    override suspend fun provisionTag(tag: Tag, code: String?): NfcLinkResult {
        provisionedCodes += code
        return result
    }

    override fun identifyTag(tag: Tag): String = id

    override fun dispatchTechFor(tag: Tag): String? = null

    override fun readCode(tag: Tag): String? {
        readCount++
        return codeOnTag
    }

    override fun existingLink(tag: Tag, code: String) = NfcTagLink(
        uid = id,
        mode = TagLinkMode.SMART_NDEF,
        ndefUri = "monolith://tag/$id",
        linkedAtMillis = 0L,
        code = code,
    )
}

/**
 * android.nfc.Tag has no constructor to call, and the fakes never read it, so an empty instance
 * is all these tests need.
 */
fun allocateTag(): Tag {
    val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null)
    val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, Tag::class.java) as Tag
}
