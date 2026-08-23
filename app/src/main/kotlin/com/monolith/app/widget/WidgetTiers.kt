package com.monolith.app.widget

/**
 * What a widget of a given height shows, and how much vertical space that leaves the chart.
 *
 * [reservedBandDp] is everything above and below the chart inside the widget's padding: the
 * header and total, plus whichever detail bands this height earns. The chart takes what's left,
 * so these numbers have to track widget_time_saved.xml -- they are that layout measured, not a
 * preference.
 */
data class WidgetTier(
    val showStats: Boolean,
    val showApps: Boolean,
    val reservedBandDp: Int,
)

/**
 * The detail the widget adds as it grows taller. Kept free of Android so the thresholds can be
 * tested without a launcher to resize against.
 */
object WidgetTiers {

    /** Header label plus the total beneath it, including the chart's top margin. */
    const val TEXT_BAND_DP = 63

    /** The hairline and the air either side of it. */
    const val DIVIDER_BAND_DP = 21

    /** One row of caption-over-value readouts. */
    const val STATS_BAND_DP = 32

    /** [APP_ROWS] rows of 20dp -- a line of text, its rule, and the air below it -- plus the
     * band's top margin. */
    const val APPS_BAND_DP = 68

    const val APP_ROWS = 3

    /**
     * Heights at which each band starts being worth drawing. Both are the point where the chart
     * would still clear MIN_CHART_HEIGHT_DP with room to spare rather than collapsing onto its
     * floor: 32dp of padding and the bands above leave ~52dp of chart at 200dp, and the same
     * again once the app list is added at 272dp. Roughly three and four launcher rows, but
     * derived from the layout rather than from any launcher's cell size.
     */
    const val STATS_MIN_HEIGHT_DP = 200
    const val APPS_MIN_HEIGHT_DP = 272

    fun forHeight(heightDp: Int): WidgetTier {
        val showStats = heightDp >= STATS_MIN_HEIGHT_DP
        val showApps = heightDp >= APPS_MIN_HEIGHT_DP
        return WidgetTier(
            showStats = showStats,
            showApps = showApps,
            reservedBandDp = TEXT_BAND_DP +
                (if (showStats) DIVIDER_BAND_DP + STATS_BAND_DP else 0) +
                (if (showApps) APPS_BAND_DP else 0),
        )
    }
}
