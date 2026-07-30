package com.monolith.app.domain.model

/**
 * Someone allowed through Monolith for a specific app. At least one of [name] or [handle] must
 * be non-blank; matching checks whichever is present against the notification's title and text.
 */
data class ImportantPerson(
    val packageName: String,
    val name: String?,
    val handle: String?,
) {
    /** Case-insensitive; [handle] is tried both with and without a leading '@'. */
    fun matches(title: CharSequence?, text: CharSequence?): Boolean {
        val haystacks = listOfNotNull(title?.toString(), text?.toString())
        if (haystacks.isEmpty()) return false

        val nameMatch = name?.takeIf { it.isNotBlank() }?.let { candidate ->
            haystacks.any { it.contains(candidate, ignoreCase = true) }
        } ?: false

        val handleMatch = handle?.takeIf { it.isNotBlank() }?.let { candidate ->
            val variants = listOf(candidate, "@$candidate")
            haystacks.any { haystack -> variants.any { haystack.contains(it, ignoreCase = true) } }
        } ?: false

        return nameMatch || handleMatch
    }
}
