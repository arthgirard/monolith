package com.monolith.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `every tag round-trips`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }

    @Test
    fun `a region the app does not ship is matched on its language`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de-AT"))
    }

    @Test
    fun `a language whose only entry names a region does not swallow its other regions`() {
        assertEquals(AppLanguage.PORTUGUESE_BRAZIL, AppLanguage.fromTag("pt-BR"))
        assertNull(AppLanguage.fromTag("pt-PT"))
    }

    @Test
    fun `the underscore form Android also reports is understood`() {
        assertEquals(AppLanguage.PORTUGUESE_BRAZIL, AppLanguage.fromTag("pt_BR"))
    }

    @Test
    fun `no choice and an unshipped language both read as following the system`() {
        assertNull(AppLanguage.fromTag(null))
        assertNull(AppLanguage.fromTag(""))
        assertNull(AppLanguage.fromTag("ja"))
    }
}
