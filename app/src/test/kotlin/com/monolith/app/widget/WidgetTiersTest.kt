package com.monolith.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTiersTest {

    @Test
    fun `the default size shows neither band`() {
        val tier = WidgetTiers.forHeight(110)

        assertFalse(tier.showStats)
        assertFalse(tier.showApps)
        assertEquals(WidgetTiers.TEXT_BAND_DP, tier.reservedBandDp)
    }

    @Test
    fun `one dp below the stats threshold still shows neither band`() {
        val tier = WidgetTiers.forHeight(WidgetTiers.STATS_MIN_HEIGHT_DP - 1)

        assertFalse(tier.showStats)
    }

    @Test
    fun `the stats band appears at its threshold`() {
        val tier = WidgetTiers.forHeight(WidgetTiers.STATS_MIN_HEIGHT_DP)

        assertTrue(tier.showStats)
        assertFalse(tier.showApps)
        assertEquals(
            WidgetTiers.TEXT_BAND_DP + WidgetTiers.DIVIDER_BAND_DP + WidgetTiers.STATS_BAND_DP,
            tier.reservedBandDp,
        )
    }

    @Test
    fun `the app list appears at its own threshold, showing the base row count`() {
        val tier = WidgetTiers.forHeight(WidgetTiers.APPS_MIN_HEIGHT_DP)

        assertTrue(tier.showApps)
        assertEquals(WidgetTiers.APP_ROWS, tier.appRows)
        assertEquals(
            WidgetTiers.TEXT_BAND_DP + WidgetTiers.DIVIDER_BAND_DP +
                WidgetTiers.STATS_BAND_DP + WidgetTiers.appsBandDp(WidgetTiers.APP_ROWS),
            tier.reservedBandDp,
        )
    }

    @Test
    fun `the app list never appears without the stats above it`() {
        // The list hangs off the same hairline the stat row does, so a height that showed one
        // without the other would leave the list floating under a chart with no rule.
        (0..600 step 5).forEach { heightDp ->
            val tier = WidgetTiers.forHeight(heightDp)
            if (tier.showApps) assertTrue("height $heightDp", tier.showStats)
        }
    }

    @Test
    fun `a height just past the threshold does not yet earn a 4th row`() {
        // The margin exists precisely so a launcher grid that lands slightly over
        // APPS_MIN_HEIGHT_DP doesn't tip into an extra row nobody asked to resize for.
        val barelyTaller = WidgetTiers.forHeight(WidgetTiers.APPS_MIN_HEIGHT_DP + WidgetTiers.APP_ROW_HEIGHT_DP)

        assertEquals(WidgetTiers.APP_ROWS, barelyTaller.appRows)
    }

    @Test
    fun `clearing a row's height plus its margin earns one more app row`() {
        val extraRowStep = WidgetTiers.APP_ROW_HEIGHT_DP + WidgetTiers.APP_ROW_MARGIN_DP
        val oneRowTaller = WidgetTiers.forHeight(WidgetTiers.APPS_MIN_HEIGHT_DP + extraRowStep)

        assertEquals(WidgetTiers.APP_ROWS + 1, oneRowTaller.appRows)
        assertEquals(
            WidgetTiers.TEXT_BAND_DP + WidgetTiers.DIVIDER_BAND_DP +
                WidgetTiers.STATS_BAND_DP + WidgetTiers.appsBandDp(WidgetTiers.APP_ROWS + 1),
            oneRowTaller.reservedBandDp,
        )
    }

    @Test
    fun `app rows cap out at MAX_APP_ROWS and never grow further`() {
        val extraRowStep = WidgetTiers.APP_ROW_HEIGHT_DP + WidgetTiers.APP_ROW_MARGIN_DP
        val heightForMax = WidgetTiers.APPS_MIN_HEIGHT_DP +
            (WidgetTiers.MAX_APP_ROWS - WidgetTiers.APP_ROWS) * extraRowStep
        val atCap = WidgetTiers.forHeight(heightForMax)
        val beyondCap = WidgetTiers.forHeight(2000)

        assertEquals(WidgetTiers.MAX_APP_ROWS, atCap.appRows)
        assertEquals(atCap, beyondCap)
    }
}
