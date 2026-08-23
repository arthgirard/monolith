package com.monolith.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRowBarRendererTest {

    @Test
    fun `the busiest app fills its bar`() {
        assertEquals(1f, WidgetRowBarRenderer.fraction(7, 7), 0f)
    }

    @Test
    fun `a lesser count fills its share`() {
        assertEquals(0.5f, WidgetRowBarRenderer.fraction(3, 6), 0f)
    }

    @Test
    fun `nothing reached for draws no bar`() {
        // Guards the empty list: dividing by a zero top would fill every bar rather than none.
        assertEquals(0f, WidgetRowBarRenderer.fraction(0, 0), 0f)
    }

    @Test
    fun `a count above the top is clamped rather than overflowing`() {
        assertEquals(1f, WidgetRowBarRenderer.fraction(9, 4), 0f)
    }
}
