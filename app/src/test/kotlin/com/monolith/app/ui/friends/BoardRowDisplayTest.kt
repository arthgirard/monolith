package com.monolith.app.ui.friends

import com.monolith.app.domain.model.BoardRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRowDisplayTest {
    private val hidden = BoardRow(
        name = "Alex",
        isMe = false,
        rank = null,
        savedMillis = null,
        streakVisible = false,
        streakStartedAt = null,
        bypassCount = null,
        unlockCount = null,
        lastSyncAt = null,
    )

    @Test
    fun `a member hiding every stat shares none`() {
        assertTrue(sharesNoStats(hidden))
    }

    @Test
    fun `any one visible stat counts as sharing`() {
        assertFalse(sharesNoStats(hidden.copy(savedMillis = 0)))
        assertFalse(sharesNoStats(hidden.copy(streakVisible = true)))
        assertFalse(sharesNoStats(hidden.copy(bypassCount = 0)))
        assertFalse(sharesNoStats(hidden.copy(unlockCount = 0)))
    }

    @Test
    fun `blocked apps alone are not a stat`() {
        assertTrue(sharesNoStats(hidden.copy(blockedApps = emptyList())))
    }
}
