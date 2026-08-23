package com.monolith.app.service

import com.monolith.app.domain.model.BlockSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPortraitTest {

    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    private fun at(minutes: Long) = start + minutes * minute

    @Test
    fun `an unbroken hold is one full-width span`() {
        val spans = SessionPortrait.spansFor(start, at(60), listOf(BlockSession(start, at(60))))

        assertEquals(listOf(PortraitSpan(0f, 1f, SpanKind.HELD)), spans)
    }

    @Test
    fun `a finished pause cuts the bar where it happened`() {
        // Held for 15, open for 15, held for 30: the hole sits a quarter of the way in.
        val held = listOf(BlockSession(start, at(15)), BlockSession(at(30), at(60)))

        val spans = SessionPortrait.spansFor(start, at(60), held)

        assertEquals(
            listOf(
                PortraitSpan(0f, 0.25f, SpanKind.HELD),
                PortraitSpan(0.25f, 0.5f, SpanKind.PAUSED),
                PortraitSpan(0.5f, 1f, SpanKind.HELD),
            ),
            spans,
        )
    }

    @Test
    fun `a pause running now reaches the right edge and is marked as live`() {
        val spans = SessionPortrait.spansFor(start, at(60), listOf(BlockSession(start, at(45))))

        assertEquals(
            listOf(
                PortraitSpan(0f, 0.75f, SpanKind.HELD),
                PortraitSpan(0.75f, 1f, SpanKind.PAUSED_NOW),
            ),
            spans,
        )
    }

    @Test
    fun `a cycle that has only ever been paused is one live pause`() {
        // Unlocking an app the instant Monolith came on: nothing has been held yet.
        val spans = SessionPortrait.spansFor(start, at(5), emptyList())

        assertEquals(listOf(PortraitSpan(0f, 1f, SpanKind.PAUSED_NOW)), spans)
    }

    @Test
    fun `a committed hold and the running one join into a single span`() {
        // What an app unlock leaves behind once its window closes would otherwise draw a seam at
        // the join, exactly where nothing happened.
        val held = listOf(BlockSession(start, at(30)), BlockSession(at(30), at(60)))

        val spans = SessionPortrait.spansFor(start, at(60), held)

        assertEquals(listOf(PortraitSpan(0f, 1f, SpanKind.HELD)), spans)
    }

    @Test
    fun `overlapping held segments don't double count`() {
        val held = listOf(BlockSession(start, at(40)), BlockSession(at(20), at(60)))

        val spans = SessionPortrait.spansFor(start, at(60), held)

        assertEquals(listOf(PortraitSpan(0f, 1f, SpanKind.HELD)), spans)
    }

    @Test
    fun `held time from before this cycle is clipped away`() {
        // blockSessions keeps 400 days of history; only what falls inside the cycle is the cycle.
        val held = listOf(BlockSession(start - 10 * minute, at(30)))

        val spans = SessionPortrait.spansFor(start, at(60), held)

        assertEquals(
            listOf(
                PortraitSpan(0f, 0.5f, SpanKind.HELD),
                PortraitSpan(0.5f, 1f, SpanKind.PAUSED_NOW),
            ),
            spans,
        )
    }

    @Test
    fun `two pauses both survive into the portrait`() {
        val held = listOf(
            BlockSession(start, at(10)),
            BlockSession(at(20), at(50)),
            BlockSession(at(60), at(100)),
        )

        val spans = SessionPortrait.spansFor(start, at(100), held)

        assertEquals(5, spans.size)
        assertEquals(2, spans.count { it.kind == SpanKind.PAUSED })
        assertEquals(3, spans.count { it.kind == SpanKind.HELD })
    }

    @Test
    fun `the spans always tile the whole width`() {
        val held = listOf(BlockSession(start, at(10)), BlockSession(at(20), at(50)))

        val spans = SessionPortrait.spansFor(start, at(60), held)

        assertEquals(0f, spans.first().startFraction, 0.0001f)
        assertEquals(1f, spans.last().endFraction, 0.0001f)
        spans.zipWithNext { left, right ->
            assertEquals(left.endFraction, right.startFraction, 0.0001f)
        }
    }

    @Test
    fun `a cycle with no elapsed time draws nothing`() {
        assertTrue(SessionPortrait.spansFor(start, start, emptyList()).isEmpty())
    }

    @Test
    fun `a clock moved behind the cycle start draws nothing rather than inverting`() {
        assertTrue(SessionPortrait.spansFor(start, start - minute, emptyList()).isEmpty())
    }
}
