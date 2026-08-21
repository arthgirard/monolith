package com.monolith.app.widget

import com.monolith.app.domain.model.TimeSavedBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeSavedChartGeometryTest {

    private val hourMillis = 60 * 60 * 1000L

    private fun buckets(vararg durationMillis: Long, capacity: Long = hourMillis) =
        durationMillis.mapIndexed { index, duration ->
            TimeSavedBucket(
                bucketStartMillis = index * capacity,
                durationMillis = duration,
                capacityMillis = capacity,
            )
        }

    @Test
    fun `no buckets produce no slots`() {
        assertTrue(TimeSavedChartGeometry.slots(emptyList(), width = 100f, height = 50f, gap = 2f).isEmpty())
    }

    @Test
    fun `slots span the full width with even gaps`() {
        val slots = TimeSavedChartGeometry.slots(buckets(0, 0, 0, 0), width = 100f, height = 50f, gap = 4f)

        assertEquals(4, slots.size)
        assertEquals(0f, slots.first().left, 0.01f)
        assertEquals(100f, slots.last().right, 0.01f)
        // (100 - 3 gaps of 4) / 4 bars = 22 each
        slots.forEach { assertEquals(22f, it.right - it.left, 0.01f) }
        assertEquals(4f, slots[1].left - slots[0].right, 0.01f)
    }

    @Test
    fun `a fully blocked bucket fills the whole height`() {
        val slot = TimeSavedChartGeometry.slots(buckets(hourMillis), width = 10f, height = 50f, gap = 0f).single()

        assertEquals(0f, slot.fillTop, 0.01f)
        assertEquals(50f, slot.bottom, 0.01f)
        assertTrue(slot.hasFill)
    }

    @Test
    fun `a half blocked bucket fills half the height`() {
        val slot = TimeSavedChartGeometry.slots(buckets(hourMillis / 2), width = 10f, height = 50f, gap = 0f).single()

        assertEquals(25f, slot.fillTop, 0.01f)
    }

    @Test
    fun `an empty bucket has no fill`() {
        val slot = TimeSavedChartGeometry.slots(buckets(0), width = 10f, height = 50f, gap = 0f).single()

        assertEquals(50f, slot.fillTop, 0.01f)
        assertTrue(!slot.hasFill)
    }

    @Test
    fun `a bucket credited beyond its capacity is clamped`() {
        val slot = TimeSavedChartGeometry.slots(buckets(hourMillis * 3), width = 10f, height = 50f, gap = 0f).single()

        assertEquals(0f, slot.fillTop, 0.01f)
    }

    @Test
    fun `a zero capacity bucket does not blow up`() {
        val bucket = TimeSavedBucket(bucketStartMillis = 0, durationMillis = 0, capacityMillis = 0)
        val slot = TimeSavedChartGeometry.slots(listOf(bucket), width = 10f, height = 50f, gap = 0f).single()

        assertTrue(!slot.hasFill)
    }

    @Test
    fun `a label that fits stays centred on its bar`() {
        val x = TimeSavedChartGeometry.labelCenterX(barCenter = 50f, textWidth = 20f, chartWidth = 100f)

        assertEquals(50f, x, 0.01f)
    }

    @Test
    fun `a label hanging off the left edge is pushed back in`() {
        // The 12a label sits on the first bar, half a bar in, so on a narrow widget its left
        // half would be clipped away and it would read as 2a.
        val x = TimeSavedChartGeometry.labelCenterX(barCenter = 3f, textWidth = 20f, chartWidth = 100f)

        assertEquals(10f, x, 0.01f)
    }

    @Test
    fun `a label hanging off the right edge is pushed back in`() {
        val x = TimeSavedChartGeometry.labelCenterX(barCenter = 97f, textWidth = 20f, chartWidth = 100f)

        assertEquals(90f, x, 0.01f)
    }

    @Test
    fun `a label wider than the whole chart is centred rather than clamped twice`() {
        val x = TimeSavedChartGeometry.labelCenterX(barCenter = 3f, textWidth = 200f, chartWidth = 100f)

        assertEquals(50f, x, 0.01f)
    }

    @Test
    fun `gaps collapse rather than inverting bars when the chart is too narrow`() {
        val slots = TimeSavedChartGeometry.slots(buckets(*LongArray(24) { 0L }), width = 24f, height = 50f, gap = 4f)

        assertEquals(24, slots.size)
        slots.forEach { assertTrue("bar width must stay positive", it.right - it.left > 0f) }
        assertTrue(slots.last().right <= 24f)
    }
}
