package com.monolith.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * The rule under each app row, filled to that app's share of the most-reached-for one, so the
 * list reads as a ranking at a glance rather than as three numbers to compare by eye. The chart's
 * bars turned on their side.
 *
 * Drawn the same way the chart is, and for the same reason -- a white alpha mask the layout tints
 * with `@color/widget_bar`, so the launcher resolves the one colour against its own configuration
 * rather than this process resolving it against a possibly stale one. See TimeSavedChartRenderer.
 */
object WidgetRowBarRenderer {

    /** Solid, like the chart's filled bars: nothing sits on top of these to read through them. */
    private const val FILL_ALPHA = 1f

    /**
     * The bar is a flat colour stretched by the ImageView, so it needs enough columns to place the
     * fill edge accurately and no more: at 256 the edge lands within half a percent of the true
     * fraction, and the whole bitmap costs a kilobyte of the update's transaction budget.
     */
    private const val WIDTH_PX = 256

    /**
     * How full [count]'s bar is against the busiest app's [topCount]. A zero or negative top means
     * nothing was reached for, so nothing is drawn rather than everything being drawn full.
     */
    fun fraction(count: Int, topCount: Int): Float {
        if (topCount <= 0 || count <= 0) return 0f
        return (count.toFloat() / topCount.toFloat()).coerceIn(0f, 1f)
    }

    fun render(fraction: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH_PX, 1, Bitmap.Config.ARGB_8888)
        val filled = (WIDTH_PX * fraction.coerceIn(0f, 1f)).toInt()
        if (filled > 0) {
            val paint = Paint().apply { color = ((FILL_ALPHA * 255).toInt() shl 24) or 0x00FFFFFF }
            Canvas(bitmap).drawRect(0f, 0f, filled.toFloat(), 1f, paint)
        }
        return bitmap
    }
}
