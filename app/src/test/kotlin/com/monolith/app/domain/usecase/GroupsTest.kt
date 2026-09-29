package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.GroupLabel
import com.monolith.app.domain.model.ShareSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupsTest {
    private fun group(memberCount: Int, others: List<String>, name: String? = null, share: ShareSettings = ShareSettings(true, true, true)) =
        GroupInfo("g", "ABCDEFGH", name, memberCount, others, share)

    @Test
    fun `a signal is uploaded when any group shares it`() {
        val union = shareUnion(listOf(group(2, listOf("Sam"), share = ShareSettings(true, false, false)), group(2, listOf("Lea"), share = ShareSettings(false, false, true))))
        assertEquals(ShareSettings(saved = true, streak = false, pauses = true), union)
        assertEquals(ShareSettings(false, false, false), shareUnion(emptyList()))
    }

    @Test
    fun `labels follow group size`() {
        assertEquals(GroupLabel("ABCDEFGH", isCode = true), groupLabel(group(1, emptyList())))
        assertEquals(GroupLabel("Sam", isCode = false), groupLabel(group(2, listOf("Sam"))))
        assertEquals(GroupLabel("Lea, Max", isCode = false), groupLabel(group(3, listOf("Max", "Lea"))))
        assertEquals(GroupLabel("Flat", isCode = false), groupLabel(group(3, listOf("Max", "Lea"), name = "Flat")))
    }

    @Test
    fun `labels that collide carry the start of the invite code`() {
        val a = GroupInfo("a", "ABCDEFGH", null, 2, listOf("Sam"), ShareSettings(true, true, true))
        val b = GroupInfo("b", "JKLMNPQR", null, 2, listOf("Sam"), ShareSettings(true, true, true))
        val c = GroupInfo("c", "STUVWXYZ", null, 2, listOf("Lea"), ShareSettings(true, true, true))

        assertEquals(
            mapOf(
                "a" to GroupLabel("Sam · ABCD", isCode = false),
                "b" to GroupLabel("Sam · JKLM", isCode = false),
                "c" to GroupLabel("Lea", isCode = false),
            ),
            groupLabels(listOf(a, b, c)),
        )
    }

    @Test
    fun `distinct labels are left alone`() {
        val a = GroupInfo("a", "ABCDEFGH", null, 1, emptyList(), ShareSettings(true, true, true))
        val b = GroupInfo("b", "JKLMNPQR", null, 2, listOf("Sam"), ShareSettings(true, true, true))

        assertEquals(
            mapOf("a" to GroupLabel("ABCDEFGH", isCode = true), "b" to GroupLabel("Sam", isCode = false)),
            groupLabels(listOf(a, b)),
        )
    }
}
