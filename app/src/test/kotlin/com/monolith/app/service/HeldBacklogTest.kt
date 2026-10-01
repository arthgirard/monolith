package com.monolith.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldBacklogTest {

    @Test
    fun `an update replaces its earlier version and counts once`() {
        val backlog = HeldBacklog<String>()
        backlog.add("chat", "thread", "hi", at = 1)
        backlog.add("chat", "thread", "hi, you there?", at = 2)

        val app = backlog.drain().single()
        assertEquals(listOf("hi, you there?"), app.newestFirst)
        assertEquals(1, app.count)
    }

    @Test
    fun `each app keeps its newest few and counts the rest`() {
        val backlog = HeldBacklog<Int>(perApp = 3)
        repeat(10) { backlog.add("feed", "post$it", it, at = it.toLong()) }

        val app = backlog.drain().single()
        assertEquals(listOf(9, 8, 7), app.newestFirst)
        assertEquals(10, app.count)
    }

    @Test
    fun `a drain lists the most recent app first and empties the backlog`() {
        val backlog = HeldBacklog<String>()
        backlog.add("old", "a", "a", at = 1)
        backlog.add("new", "b", "b", at = 5)
        backlog.add("mid", "c", "c", at = 3)

        assertEquals(listOf("new", "mid", "old"), backlog.drain().map { it.packageName })
        assertTrue(backlog.drain().isEmpty())
    }

    @Test
    fun `draining one app leaves the others held`() {
        val backlog = HeldBacklog<String>()
        backlog.add("one", "a", "a", at = 1)
        backlog.add("two", "b", "b", at = 2)

        assertEquals(listOf("one"), backlog.drain(onlyPackage = "one").map { it.packageName })
        assertTrue(backlog.drain(onlyPackage = "one").isEmpty())
        assertEquals(listOf("two"), backlog.drain().map { it.packageName })
    }
}
