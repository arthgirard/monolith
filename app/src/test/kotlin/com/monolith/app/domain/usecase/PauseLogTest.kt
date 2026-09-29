package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.Pause
import com.monolith.app.domain.model.PauseType
import org.junit.Assert.assertEquals
import org.junit.Test

class PauseLogTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `record appends the new pause`() {
        val existing = listOf(Pause(PauseType.UNLOCK, now - 1000))

        val updated = PauseLog.record(existing, PauseType.BYPASS, now)

        assertEquals(listOf(Pause(PauseType.UNLOCK, now - 1000), Pause(PauseType.BYPASS, now)), updated)
    }

    @Test
    fun `record drops pauses past retention and keeps the boundary`() {
        val expired = Pause(PauseType.BYPASS, now - PauseLog.RETENTION_MILLIS - 1)
        val boundary = Pause(PauseType.UNLOCK, now - PauseLog.RETENTION_MILLIS)

        val updated = PauseLog.record(listOf(expired, boundary), PauseType.UNLOCK, now)

        assertEquals(listOf(boundary, Pause(PauseType.UNLOCK, now)), updated)
    }
}
