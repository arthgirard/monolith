package com.monolith.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * The countdown drawn as minutes: one segment for each minute a bypass or an app unlock still
 * has, going out one at a time. Sits under the session portrait while the wall is open, and is
 * hidden the rest of the time -- there is nothing to count down to while Monolith is holding.
 *
 * Real colours rather than a tinted mask, for the reason [SessionPortraitRenderer] documents.
 */
object MinuteSegmentRenderer {

    /** Divisible by both 15 and 5, so segment edges land on whole pixels for either window. */
    private const val WIDTH_PX = 960

    private const val GAP_PX = 6

    fun render(row: SegmentRow, litColor: Int, spentColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH_PX, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        val total = row.total.coerceAtLeast(1)
        val gaps = total - 1
        val segmentWidth = (WIDTH_PX - gaps * GAP_PX).toFloat() / total

        repeat(total) { index ->
            val left = index * (segmentWidth + GAP_PX)
            // Segments go out from the right, so the burning ones stay anchored where the row
            // starts and the remaining time reads as a length rather than as a position.
            paint.color = if (index < row.lit) litColor else spentColor
            canvas.drawRect(left, 0f, left + segmentWidth, 1f, paint)
        }

        return bitmap
    }
}
