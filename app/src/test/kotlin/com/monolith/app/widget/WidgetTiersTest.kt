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
    fun `the app list appears at its own threshold`() {
        val tier = WidgetTiers.forHeight(WidgetTiers.APPS_MIN_HEIGHT_DP)

        assertTrue(tier.showApps)
        assertEquals(
            WidgetTiers.TEXT_BAND_DP + WidgetTiers.DIVIDER_BAND_DP +
                WidgetTiers.STATS_BAND_DP + WidgetTiers.APPS_BAND_DP,
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
    fun `a height beyond every threshold reserves every band`() {
        val tall = WidgetTiers.forHeight(1000)

        assertEquals(WidgetTiers.forHeight(WidgetTiers.APPS_MIN_HEIGHT_DP), tall)
    }
}
