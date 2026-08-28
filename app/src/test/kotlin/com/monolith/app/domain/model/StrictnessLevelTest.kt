package com.monolith.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictnessLevelTest {

    @Test
    fun `an install that predates the setting reads back as standard`() {
        assertEquals(StrictnessLevel.STANDARD, StrictnessLevel.fromStorage(null))
    }

    @Test
    fun `a level that no longer exists reads back as standard`() {
        assertEquals(StrictnessLevel.STANDARD, StrictnessLevel.fromStorage("PARANOID"))
    }

    @Test
    fun `every stored level round-trips`() {
        StrictnessLevel.entries.forEach { level ->
            assertEquals(level, StrictnessLevel.fromStorage(level.name))
        }
    }

    @Test
    fun `only standard keeps the per-app escape`() {
        assertTrue(StrictnessLevel.STANDARD.allowsAppUnlock)
        assertFalse(StrictnessLevel.STRICT.allowsAppUnlock)
        assertFalse(StrictnessLevel.ABSOLUTE.allowsAppUnlock)
    }

    @Test
    fun `only absolute drops the emergency bypass`() {
        assertTrue(StrictnessLevel.STANDARD.allowsEmergencyBypass)
        assertTrue(StrictnessLevel.STRICT.allowsEmergencyBypass)
        assertFalse(StrictnessLevel.ABSOLUTE.allowsEmergencyBypass)
    }
}
