package com.monolith.app.data.leaderboard

import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.ShareSettings
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LeaderboardDtosTest {

    private val day = DayAggregate(LocalDate.of(2026, 9, 28), savedMillis = 60_000, bypassCount = 1, unlockCount = 2)

    @Test
    fun `everything shared goes on the wire`() {
        val body = LeaderboardJson.encodeToString(syncRequestOf(listOf(day), 42L, ShareSettings(true, true, true)))

        assertEquals(
            """{"days":[{"date":"2026-09-28","savedMs":60000,"bypassCount":1,"unlockCount":2}],"streakStartedAt":42}""",
            body,
        )
    }

    @Test
    fun `hidden signals never leave the phone`() {
        val body = LeaderboardJson.encodeToString(syncRequestOf(listOf(day), 42L, ShareSettings(false, false, false)))

        assertEquals("""{"days":[{"date":"2026-09-28"}]}""", body)
    }

    @Test
    fun `a missing streak object means hidden, an empty one means not enforcing`() {
        val rows = LeaderboardJson.decodeFromString<BoardResponse>(
            """{"rows":[
                {"name":"A","isMe":true,"rank":1,"savedMs":5,"streak":{"startedAt":null},"lastSyncAt":9},
                {"name":"B","isMe":false,"lastSyncAt":null,"extra":"ignored"}
            ]}""",
        ).rows.map { it.toDomain() }

        assertTrue(rows[0].streakVisible)
        assertEquals(null, rows[0].streakStartedAt)
        assertEquals(1, rows[0].rank)
        assertFalse(rows[1].streakVisible)
        assertEquals(null, rows[1].savedMillis)
    }

    @Test
    fun `error codes map to domain errors`() {
        assertEquals(LeaderboardError.UNAUTHORIZED, errorOf("unauthorized"))
        assertEquals(LeaderboardError.INVITE_NOT_FOUND, errorOf("invite_not_found"))
        assertEquals(LeaderboardError.GROUP_FULL, errorOf("group_full"))
        assertEquals(LeaderboardError.HIDDEN_SIGNAL, errorOf("hidden_signal"))
        assertEquals(LeaderboardError.INVALID, errorOf("invalid_body"))
        assertEquals(LeaderboardError.SERVER, errorOf(null))
    }
}
