package com.monolith.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Draws the session's shape for the enforcement notification: solid from the moment Monolith came
 * on, on the left, to now, on the right, with a cut wherever a bypass or an app unlock had the
 * wall open.
 *
 * Drawn in real colours rather than as a tinted alpha mask, which is the opposite of what the
 * widget's renderers do and deliberate. The widget's mask works because the launcher resolves the
 * tint against the configuration of the surface the bitmap sits on. A notification's surface is
 * the shade, whose appearance the system owns and this app's `-night` resources cannot predict --
 * and a theme attribute resolved during RemoteViews inflation is the standard route to black on
 * black. Every colour passed in is defined once in values/ with no night variant, exactly as the
 * comment on `monolith_amber` says notification colours must be.
 */
object SessionPortraitRenderer {

    /**
     * One pixel tall: the bar only varies horizontally, and the ImageView stretches it to whatever
     * height the shade gives it. Nothing whose aspect ratio matters can go in here -- the
     * horizontal stretch factor is whatever the shade's width turns out to be.
     */
    private const val WIDTH_PX = 960

    /**
     * No cut is allowed to round away to nothing. A five-minute unlock inside a twelve-hour
     * session is under a pixel of this bitmap, and a session that reads as unbroken when it wasn't
     * is the one thing this bar must never do.
     */
    private const val MIN_CUT_PX = 6f

    fun render(
        spans: List<PortraitSpan>,
        heldColor: Int,
        pausedColor: Int,
        livePausedColor: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH_PX, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        // Nothing measured yet -- Monolith has just come on. A whole bar is the honest picture of
        // a session with no holes in it.
        if (spans.isEmpty()) {
            paint.color = heldColor
            canvas.drawRect(0f, 0f, WIDTH_PX.toFloat(), 1f, paint)
            return bitmap
        }

        spans.filter { it.kind == SpanKind.HELD }.forEach { span ->
            paint.color = heldColor
            canvas.drawRect(span.startFraction * WIDTH_PX, 0f, span.endFraction * WIDTH_PX, 1f, paint)
        }

        // Cuts go on last so that widening a thin one takes its pixels back from the hold either
        // side of it, rather than being painted over by the next hold along.
        spans.filter { it.kind != SpanKind.HELD }.forEach { span ->
            paint.color = if (span.kind == SpanKind.PAUSED_NOW) livePausedColor else pausedColor
            val left = span.startFraction * WIDTH_PX
            val right = span.endFraction * WIDTH_PX
            val short = MIN_CUT_PX - (right - left)
            if (short > 0f) {
                val widenedLeft = (left - short / 2f).coerceIn(0f, WIDTH_PX - MIN_CUT_PX)
                canvas.drawRect(widenedLeft, 0f, widenedLeft + MIN_CUT_PX, 1f, paint)
            } else {
                canvas.drawRect(left, 0f, right, 1f, paint)
            }
        }

        return bitmap
    }
}
