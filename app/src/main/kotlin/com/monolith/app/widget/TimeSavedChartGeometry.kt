package com.monolith.app.widget

import com.monolith.app.domain.model.TimeSavedBucket

/**
 * One bar of the widget chart in pixels, top-left origin: the full-height track spans
 * [top, bottom], the filled part spans [fillTop, bottom].
 */
data class BarSlot(
    val left: Float,
    val right: Float,
    val top: Float,
    val fillTop: Float,
    val bottom: Float,
) {
    val hasFill: Boolean get() = fillTop < bottom
}

/**
 * Bar layout for the widget chart, kept free of Android graphics so it can be unit tested.
 * Mirrors TimeSavedBarChart's geometry: equal-width bars separated by a fixed gap, each filled
 * by its share of the bucket's capacity.
 */
object TimeSavedChartGeometry {

    fun slots(
        buckets: List<TimeSavedBucket>,
        width: Float,
        height: Float,
        gap: Float,
    ): List<BarSlot> {
        if (buckets.isEmpty()) return emptyList()

        val count = buckets.size
        // On a narrow widget the gaps can eat the whole width and leave bars inverted, so they
        // collapse before the bars do.
        val totalGap = gap * (count - 1)
        val effectiveGap = if (width - totalGap >= count) gap else 0f
        val barWidth = (width - effectiveGap * (count - 1)) / count

        return buckets.mapIndexed { index, bucket ->
            val left = index * (barWidth + effectiveGap)
            val capacity = bucket.capacityMillis.coerceAtLeast(1L).toFloat()
            val fraction = (bucket.durationMillis.toFloat() / capacity).coerceIn(0f, 1f)
            BarSlot(
                left = left,
                right = left + barWidth,
                top = 0f,
                fillTop = height - height * fraction,
                bottom = height,
            )
        }
    }

    /**
     * Where to draw a centred hour label so it stays inside the chart. The 12a label belongs to
     * the first bar, half a bar in from the left, so on a narrow widget its left half would be
     * clipped off the bitmap and it would read as 2a.
     */
    fun labelCenterX(barCenter: Float, textWidth: Float, chartWidth: Float): Float {
        val half = textWidth / 2f
        if (textWidth >= chartWidth) return chartWidth / 2f
        return barCenter.coerceIn(half, chartWidth - half)
    }
}
