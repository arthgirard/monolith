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
    val appRows: Int,
    val reservedBandDp: Int,
) {
    val showApps: Boolean get() = appRows > 0
}

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

    /** A line of text, its rule, and the air above and below the rule. */
    const val APP_ROW_HEIGHT_DP = 26

    /** The apps band's top margin, on top of however many rows it's showing. */
    const val APPS_BAND_MARGIN_DP = 8

    /**
     * Slack a resize has to clear, beyond one row's own height, before it earns another row. A
     * launcher's grid cells rarely land exactly on APPS_MIN_HEIGHT_DP, so without this an
     * placement barely past the threshold could tip into 4 rows unasked -- the same jitter
     * STATS_MIN_HEIGHT_DP and APPS_MIN_HEIGHT_DP already leave room for.
     */
    const val APP_ROW_MARGIN_DP = 24

    /** Rows shown the moment the band clears [APPS_MIN_HEIGHT_DP]. */
    const val APP_ROWS = 3

    /** Rows shown once the widget is tall enough to earn every one of them. */
    const val MAX_APP_ROWS = 6

    /**
     * Heights at which each band starts being worth drawing. Both are the point where the chart
     * would still clear MIN_CHART_HEIGHT_DP with room to spare rather than collapsing onto its
     * floor: 32dp of padding and the bands above leave ~52dp of chart at 200dp, and the same
     * again once the app list is added at 272dp. Roughly three and four launcher rows, but
     * derived from the layout rather than from any launcher's cell size.
     */
    const val STATS_MIN_HEIGHT_DP = 200
    const val APPS_MIN_HEIGHT_DP = 272

    /** Space the apps band claims for [appRows] rows, including its own top margin. */
    fun appsBandDp(appRows: Int): Int = APPS_BAND_MARGIN_DP + appRows * APP_ROW_HEIGHT_DP

    /**
     * Everything above and below the chart for a widget drawing [appRows] rows. A tier's own
     * number is the most rows its height can hold, but a day with fewer apps than that draws
     * fewer, and the caller passes what it actually drew: the band is wrap_content and the chart
     * is the only weighted view, so rows that aren't there are chart height, not blank space.
     */
    fun reservedBandDp(showStats: Boolean, appRows: Int): Int =
        TEXT_BAND_DP +
            (if (showStats) DIVIDER_BAND_DP + STATS_BAND_DP else 0) +
            (if (appRows > 0) appsBandDp(appRows) else 0)

    fun forHeight(heightDp: Int): WidgetTier {
        val showStats = heightDp >= STATS_MIN_HEIGHT_DP
        val showApps = heightDp >= APPS_MIN_HEIGHT_DP
        // Every row past the base 3 costs its own height plus APP_ROW_MARGIN_DP of slack, so a
        // resize has to clearly mean the next row rather than barely clip its threshold.
        val appRows = if (showApps) {
            val extraRowStep = APP_ROW_HEIGHT_DP + APP_ROW_MARGIN_DP
            val extraRows = (heightDp - APPS_MIN_HEIGHT_DP) / extraRowStep
            (APP_ROWS + extraRows).coerceAtMost(MAX_APP_ROWS)
        } else {
            0
        }
        return WidgetTier(
            showStats = showStats,
            appRows = appRows,
            reservedBandDp = reservedBandDp(showStats, appRows),
        )
    }
}
