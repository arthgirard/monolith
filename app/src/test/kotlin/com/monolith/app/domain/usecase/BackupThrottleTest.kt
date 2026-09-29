package com.monolith.app.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupThrottleTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun `never backed up is due`() {
        assertTrue(BackupThrottle.due(lastBackupAt = null, now = now))
    }

    @Test
    fun `two hours fifty-nine minutes after the last backup is not due`() {
        assertFalse(BackupThrottle.due(lastBackupAt = now - (2 * hour + 59 * 60 * 1000L), now = now))
    }

    @Test
    fun `exactly three hours after the last backup is due`() {
        assertTrue(BackupThrottle.due(lastBackupAt = now - 3 * hour, now = now))
    }
}
