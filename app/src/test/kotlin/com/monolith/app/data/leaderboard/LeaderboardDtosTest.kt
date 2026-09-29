package com.monolith.app.data.leaderboard

import com.monolith.app.domain.model.DayAggregate
import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.ShareSettings
import com.monolith.app.domain.model.SharedApp
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LeaderboardDtosTest {

    private val day = DayAggregate(LocalDate.of(2026, 9, 28), savedMillis = 60_000, bypassCount = 1, unlockCount = 2)
    private val apps = listOf(SharedApp("com.example.video", "Video"))

    @Test
    fun `everything shared goes on the wire`() {
        val body = LeaderboardJson.encodeToString(syncRequestOf(listOf(day), 42L, apps, ShareSettings(true, true, true, true)))

        assertEquals(
            """{"days":[{"date":"2026-09-28","savedMs":60000,"bypassCount":1,"unlockCount":2}],"streakStartedAt":42,""" +
                """"apps":[{"packageName":"com.example.video","label":"Video"}]}""",
            body,
        )
    }

    @Test
    fun `hidden signals never leave the phone`() {
        val body = LeaderboardJson.encodeToString(syncRequestOf(listOf(day), 42L, apps, ShareSettings(false, false, false, false)))

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
    fun `a missing apps list means hidden, an empty one means none uploaded yet`() {
        val rows = LeaderboardJson.decodeFromString<BoardResponse>(
            """{"rows":[
                {"name":"A","isMe":true,"apps":[{"packageName":"com.example.video","label":"Video"}]},
                {"name":"B","isMe":false,"apps":[]},
                {"name":"C","isMe":false}
            ]}""",
        ).rows.map { it.toDomain() }

        assertEquals(apps, rows[0].blockedApps)
        assertEquals(emptyList<SharedApp>(), rows[1].blockedApps)
        assertEquals(null, rows[2].blockedApps)
    }

    @Test
    fun `the apps flag is always sent, so turning it off reaches the server`() {
        assertEquals(
            """{"saved":true,"streak":true,"pauses":true,"apps":false}""",
            LeaderboardJson.encodeToString(ShareSettings(true, true, true, false).toDto()),
        )
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

    @Test
    fun `group responses decode`() {
        val response = LeaderboardJson.decodeFromString<GroupResponse>(
            """{"group":{"id":"g","inviteCode":"ABCDEFGH","name":null,"memberCount":2,"otherMembers":["Sam"],"share":{"saved":true,"streak":false,"pauses":true}}}""",
        )
        assertEquals(GroupInfo("g", "ABCDEFGH", null, 2, listOf("Sam"), ShareSettings(true, false, true, false)), response.group.toDomain())
    }

    @Test
    fun `backup error codes map`() {
        assertEquals(LeaderboardError.TOKEN_TAKEN, errorOf("token_taken"))
        assertEquals(LeaderboardError.NO_BACKUP, errorOf("no_backup"))
        assertEquals(LeaderboardError.TOO_LARGE, errorOf("too_large"))
    }

    @Test
    fun `a nameless registration sends only the token, and me decodes backupAt`() {
        assertEquals("""{"token":"t"}""", LeaderboardJson.encodeToString(RegisterRequest("t")))
        assertEquals(null, LeaderboardJson.decodeFromString<MeResponse>("""{"displayName":"A","groups":[]}""").backupAt)
        assertEquals(5L, LeaderboardJson.decodeFromString<MeResponse>("""{"displayName":"A","groups":[],"backupAt":5}""").backupAt)
    }

    @Test
    fun `new error codes map`() {
        assertEquals(LeaderboardError.TOO_MANY_GROUPS, errorOf("too_many_groups"))
        assertEquals(LeaderboardError.ALREADY_MEMBER, errorOf("already_member"))
        assertEquals(LeaderboardError.NAME_NEEDS_THREE, errorOf("name_needs_three"))
        assertEquals(LeaderboardError.NOT_MEMBER, errorOf("not_member"))
    }

    @Test
    fun `a create for a named identity sends no display name`() {
        assertEquals(
            """{"share":{"saved":true,"streak":true,"pauses":true,"apps":false}}""",
            LeaderboardJson.encodeToString(CreateGroupRequest(share = ShareDto(true, true, true))),
        )
    }
}
