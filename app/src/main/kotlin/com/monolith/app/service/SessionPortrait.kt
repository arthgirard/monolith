package com.monolith.app.service

import com.monolith.app.domain.model.BlockSession

/** What a stretch of the session was doing. */
enum class SpanKind {
    /** Monolith was holding every blocked app. */
    HELD,

    /** A bypass or an app unlock had the wall open, and it has since closed. */
    PAUSED,

    /** The wall is open right now: this span runs to the right edge and is still growing. */
    PAUSED_NOW,
}

/**
 * One stretch of the session, as a fraction of the whole. Zero is the moment Monolith came on and
 * one is now, so the spans always tile the full width no matter how long the session has run.
 */
data class PortraitSpan(val startFraction: Float, val endFraction: Float, val kind: SpanKind)

/**
 * The shape of the current cycle: solid where Monolith held, cut where a bypass or an app unlock
 * opened the wall. Kept free of Android so the arithmetic can be tested without a shade to read
 * it out of.
 *
 * Held time is passed in rather than derived, because it comes from two places that only the
 * caller can put together: the segments already committed to storage (an app unlock commits the
 * running one and fast-forwards the session clock past its window) and the segment still running.
 * Everything inside the cycle that is not held time is a pause -- which is what makes the gaps
 * correct without this needing to know which kind of pause each one was.
 */
object SessionPortrait {

    fun spansFor(cycleStart: Long, now: Long, held: List<BlockSession>): List<PortraitSpan> {
        val total = now - cycleStart
        if (total <= 0L) return emptyList()

        val merged = merge(
            held.mapNotNull { session ->
                val start = session.startMillis.coerceIn(cycleStart, now)
                val end = session.endMillis.coerceIn(cycleStart, now)
                if (end > start) start to end else null
            },
        )

        val spans = mutableListOf<PortraitSpan>()
        var cursor = cycleStart
        merged.forEach { (start, end) ->
            if (start > cursor) spans += span(cursor, start, now, cycleStart, total, paused = true)
            spans += span(start, end, now, cycleStart, total, paused = false)
            cursor = end
        }
        if (cursor < now) spans += span(cursor, now, now, cycleStart, total, paused = true)
        return spans
    }

    private fun span(
        start: Long,
        end: Long,
        now: Long,
        cycleStart: Long,
        total: Long,
        paused: Boolean,
    ): PortraitSpan = PortraitSpan(
        startFraction = (start - cycleStart).toFloat() / total,
        endFraction = (end - cycleStart).toFloat() / total,
        // A pause touching the right edge is the one running now. Nothing else can be: held time
        // is only ever committed up to the present.
        kind = if (!paused) SpanKind.HELD else if (end >= now) SpanKind.PAUSED_NOW else SpanKind.PAUSED,
    )

    /**
     * Sorted, with touching or overlapping stretches joined. The committed segment ending where
     * the running one begins is one unbroken hold, and drawing it as two would put a seam in the
     * bar exactly where nothing happened.
     */
    private fun merge(ranges: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { (start, end) ->
            val (lastStart, lastEnd) = merged.last()
            if (start <= lastEnd) {
                merged[merged.lastIndex] = lastStart to maxOf(lastEnd, end)
            } else {
                merged += start to end
            }
        }
        return merged
    }
}
