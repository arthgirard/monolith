package com.monolith.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.monolith.app.domain.model.TimeSavedBucket
import java.time.Instant
import java.time.ZoneId

/**
 * Draws the day's hourly bars into a bitmap for the widget's ImageView. RemoteViews has no
 * Canvas, and a 24-view LinearLayout would blow past the update transaction budget, so the whole
 * chart -- bars and hour labels -- travels as one bitmap.
 *
 * The bitmap is drawn as an alpha mask in white, never in a real colour, and the layout tints it
 * with `@color/widget_bar`. That matters: a colour looked up here would be resolved against *this*
 * process's configuration, while everything else in the widget is resolved by the launcher against
 * its own. The two disagree whenever the app process outlives a day/night switch -- which it does,
 * because the enforcement service keeps it alive -- and the chart would then be drawn from
 * values-night while the background and labels came from values. Letting the host resolve the one
 * colour keeps the whole widget on a single configuration.
 */
object TimeSavedChartRenderer {

    /**
     * Ceiling on bitmap pixels. Every RemoteViews update is a Binder transaction with a ~1MB
     * budget shared across the whole update, and ARGB_8888 costs 4 bytes a pixel, so the chart
     * is scaled down past this point and stretched back by the ImageView.
     */
    private const val MAX_PIXELS = 200_000

    /**
     * Opacities within the mask. The track and the labels are the bar colour held back rather than
     * separate colours, solved against the in-app chart over the widget's own ground. That ground
     * is pure white / pitch black, which is further from the track than the app's is, so the exact
     * match differs by theme -- 9.4% in light, 14.1% in dark. 12% splits them and lands within a
     * couple of shades either way. The labels match the widget's own header label rather than the
     * app's, so the two rows of muted text in the widget agree.
     */
    private const val TRACK_ALPHA = 0.12f
    private const val LABEL_ALPHA = 0.50f

    private fun mask(alpha: Float): Int = ((alpha * 255).toInt() shl 24) or 0x00FFFFFF

    fun render(
        buckets: List<TimeSavedBucket>,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Bitmap {
        val scale = scaleFor(widthPx, heightPx)
        val width = (widthPx * scale).toInt().coerceAtLeast(1)
        val height = (heightPx * scale).toInt().coerceAtLeast(1)
        val scaledDensity = density * scale

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val labelSize = 10f * scaledDensity
        val labelGap = 4f * scaledDensity
        val labelBand = if (buckets.isEmpty()) 0f else labelSize + labelGap
        val barsHeight = (height - labelBand).coerceAtLeast(1f)

        // Bars are axis-aligned rectangles: anti-aliasing only softens their edges once the
        // ImageView stretches the bitmap to fill the widget.
        val paint = Paint()
        val slots = TimeSavedChartGeometry.slots(
            buckets = buckets,
            width = width.toFloat(),
            height = barsHeight,
            gap = 2f * scaledDensity,
        )

        slots.forEach { slot ->
            paint.color = mask(TRACK_ALPHA)
            canvas.drawRect(slot.left, slot.top, slot.right, slot.bottom, paint)
            if (slot.hasFill) {
                paint.color = mask(1f)
                canvas.drawRect(slot.left, slot.fillTop, slot.right, slot.bottom, paint)
            }
        }

        if (labelBand > 0f) {
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mask(LABEL_ALPHA)
                textSize = labelSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT
            }
            val baseline = height - labelPaint.fontMetrics.descent
            slots.forEachIndexed { index, slot ->
                val label = hourLabel(index, buckets[index].bucketStartMillis, zone) ?: return@forEachIndexed
                val x = TimeSavedChartGeometry.labelCenterX(
                    barCenter = (slot.left + slot.right) / 2f,
                    textWidth = labelPaint.measureText(label),
                    chartWidth = width.toFloat(),
                )
                canvas.drawText(label, x, baseline, labelPaint)
            }
        }

        return bitmap
    }

    private fun scaleFor(widthPx: Int, heightPx: Int): Float {
        val pixels = widthPx.toLong() * heightPx.toLong()
        if (pixels <= MAX_PIXELS) return 1f
        return Math.sqrt(MAX_PIXELS.toDouble() / pixels.toDouble()).toFloat()
    }

    /** Same every-6-hours cadence as the in-app chart, so the two read alike. */
    private fun hourLabel(index: Int, bucketStartMillis: Long, zone: ZoneId): String? {
        if (index % 6 != 0) return null
        return when (val hour = Instant.ofEpochMilli(bucketStartMillis).atZone(zone).hour) {
            0 -> "12a"
            12 -> "12p"
            in 1..11 -> "${hour}a"
            else -> "${hour - 12}p"
        }
    }
}
