package com.monolith.app.ui.friends

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAgeTest {
    private val now = 10_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun `a member who never synced shows the age`() {
        assertTrue(showSyncAge(null, now))
    }

    @Test
    fun `a fresh sync hides the age`() {
        assertFalse(showSyncAge(now - 5 * 60 * 1000L, now))
    }

    @Test
    fun `exactly one hour old is still fresh`() {
        assertFalse(showSyncAge(now - hour, now))
    }

    @Test
    fun `older than an hour shows the age`() {
        assertTrue(showSyncAge(now - hour - 1, now))
    }
}
