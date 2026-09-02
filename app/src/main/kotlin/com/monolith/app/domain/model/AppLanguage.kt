package com.monolith.app.domain.model

/**
 * The languages Monolith ships strings for. Kept in step by hand with `localeFilters` in
 * `app/build.gradle.kts` and with `tools/i18n/locales.json`: a tag listed here but not packaged
 * would offer a language that silently falls back to English.
 *
 * Names are written in the language they name, not translated, so someone who has landed in a
 * language they can't read can still find their way out of this list.
 */
enum class AppLanguage(val tag: String, val displayName: String) {
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    PORTUGUESE_BRAZIL("pt-BR", "Português (Brasil)"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    ITALIAN("it", "Italiano"),
    DUTCH("nl", "Nederlands");

    companion object {
        /**
         * Resolves a stored or system-reported tag. Region is matched when the entry carries one
         * and ignored otherwise, so "en-US" finds English and "pt-PT" does not become Brazilian
         * Portuguese. An unknown tag returns null, which reads as "follow the system".
         */
        fun fromTag(tag: String?): AppLanguage? {
            if (tag.isNullOrBlank()) return null
            val normalized = tag.replace('_', '-').lowercase()
            return entries.firstOrNull { it.tag.lowercase() == normalized }
                ?: entries.firstOrNull { entry ->
                    !entry.tag.contains('-') && normalized.substringBefore('-') == entry.tag.lowercase()
                }
        }
    }
}
