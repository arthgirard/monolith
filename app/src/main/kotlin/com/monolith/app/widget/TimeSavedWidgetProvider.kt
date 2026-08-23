package com.monolith.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.monolith.app.R
import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.usecase.BlockHitLog
import com.monolith.app.domain.usecase.GetCurrentStreakUseCase
import com.monolith.app.domain.usecase.ObserveActiveSessionStartUseCase
import com.monolith.app.domain.usecase.ObserveBlockHitsUseCase
import com.monolith.app.domain.usecase.ObserveBlockSessionsUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.TimeSavedCalculator
import com.monolith.app.ui.MainActivity
import com.monolith.app.util.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Home-screen widget showing today's time saved: the running total plus the same hourly bars as
 * the in-app Time Saved screen, computed from the same sessions through TimeSavedCalculator so
 * the two can never disagree.
 */
@AndroidEntryPoint
class TimeSavedWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var observeBlockSessions: ObserveBlockSessionsUseCase
    @Inject lateinit var observeBlockState: ObserveBlockStateUseCase
    @Inject lateinit var observeActiveSessionStart: ObserveActiveSessionStartUseCase
    @Inject lateinit var observeBlockHits: ObserveBlockHitsUseCase
    @Inject lateinit var appRepository: AppRepository

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Midnight, a manual clock change or a flight across timezones all move which day "today"
        // is; without these the widget would keep drawing yesterday's bars until its next tick.
        // The refresh button lands here too, for anyone who doesn't want to wait for a tick.
        if (intent.action in ROLLOVER_ACTIONS || intent.action == ACTION_MANUAL_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimeSavedWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                render(context, manager, ids, showProgress = intent.action == ACTION_MANUAL_REFRESH)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // The chart is a bitmap sized to the widget, so a resize needs a redraw, not a rescale.
        render(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
        showProgress: Boolean = false,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val startedAt = System.currentTimeMillis()
                if (showProgress) showRefreshSpinner(context, manager, appWidgetIds)

                val sessions = observeBlockSessions().first()
                val blockState = observeBlockState().first()
                val activeSessionStart = observeActiveSessionStart().first()
                val now = System.currentTimeMillis()

                val ongoing = TimeSavedCalculator.ongoingSessions(blockState, activeSessionStart, now)
                val buckets = TimeSavedCalculator.bucketsFor(
                    periodType = TimePeriodType.DAY,
                    anchor = LocalDate.now(ZoneId.systemDefault()),
                    sessions = sessions,
                    ongoing = ongoing,
                )
                val total = buckets.sumOf { it.durationMillis }

                // Sizes first, so the detail below is fetched only if some widget is tall enough
                // to show it. Two widgets can be placed at two sizes, so each renders its own.
                val sizes = appWidgetIds.associateWith { sizeOf(context, manager, it) }
                // A bypass pauses enforcement without ending the session, and the mark shouldn't claim to
                // be holding the line while it's paused.
                val detail = detailFor(sizes.values, ongoing, blockState.isEnforcing(now), now)

                // Reading a day of sessions is usually quicker than the eye can register, so
                // without a floor the spinner is a flicker that reads as a glitch rather than as
                // a refresh. Only the manual path waits: nothing is watching the other ones.
                if (showProgress) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed < MIN_PROGRESS_MILLIS) delay(MIN_PROGRESS_MILLIS - elapsed)
                }

                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(id, buildViews(context, sizes.getValue(id), buckets, total, detail))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Swaps the refresh icon for a spinner without touching the rest of the widget, so the bars
     * and total stay on screen while the new ones are computed. A partial update is safe here:
     * the button can only be tapped on a widget that has already been fully rendered once.
     */
    private fun showRefreshSpinner(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_time_saved).apply {
            setViewVisibility(R.id.widget_refresh_icon, View.INVISIBLE)
            setViewVisibility(R.id.widget_refresh_progress, View.VISIBLE)
        }
        appWidgetIds.forEach { id -> manager.partiallyUpdateAppWidget(id, views) }
    }

    /**
     * The widget's own size in dp and the tier it earns. The chart bitmap is stretched to fill
     * its ImageView, so it has to be sized for the orientation actually on screen or the labels
     * get squashed. The host reports both: the portrait box is MIN_WIDTH x MAX_HEIGHT, the
     * landscape one MAX_WIDTH x MIN_HEIGHT.
     */
    private fun sizeOf(context: Context, manager: AppWidgetManager, appWidgetId: Int): WidgetSize {
        val options = manager.getAppWidgetOptions(appWidgetId)
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val widthKey = if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
        val heightKey = if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        val widthDp = options.getInt(widthKey, DEFAULT_WIDTH_DP).takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
        val heightDp = options.getInt(heightKey, DEFAULT_HEIGHT_DP).takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        return WidgetSize(widthDp, heightDp, WidgetTiers.forHeight(heightDp))
    }

    /**
     * The numbers only the taller tiers draw. Read once for every widget rather than per widget,
     * and not read at all by a widget too short to show them, so the default size costs exactly
     * what it did before.
     */
    private suspend fun detailFor(
        sizes: Collection<WidgetSize>,
        ongoing: List<BlockSession>,
        enforcing: Boolean,
        now: Long,
    ): WidgetDetail {
        // The mark is coloured at every size, so that flag survives the early return the bands
        // don't need.
        if (sizes.none { it.tier.showStats }) return WidgetDetail(enforcing = enforcing)

        val hits = observeBlockHits().first()
        val topApps = if (sizes.any { it.tier.showApps }) {
            // A hit logged while an app was blocked shouldn't keep it on the list after it's been
            // removed from blocking -- the list reflects what's blocked now, not history.
            val blockedPackages = appRepository.observeBlockedPackages().first()
            val currentHits = hits.filter { it.packageName in blockedPackages }
            val ranked = BlockHitLog.topPackagesToday(currentHits, now, WidgetTiers.MAX_APP_ROWS)
            // Every bar is measured against the busiest app, so the leader's bar is always full
            // and the rest read as a share of it rather than of some invisible ceiling.
            val topCount = ranked.firstOrNull()?.second ?: 0
            ranked.map { (packageName, count) ->
                TopApp(
                    // Looked up fresh each time rather than cached: a provider is a
                    // BroadcastReceiver, a new instance per broadcast, so a field would never live
                    // long enough to be read back, and a static one would go stale on an app
                    // update or a locale change.
                    label = appRepository.getAppLabel(packageName),
                    icon = appRepository.getAppIcon(packageName, APP_ICON_DP),
                    count = count,
                    barFraction = WidgetRowBarRenderer.fraction(count, topCount),
                )
            }
        } else {
            emptyList()
        }

        return WidgetDetail(
            blocksToday = BlockHitLog.countToday(hits, now),
            // Through the use case rather than summed here, so the widget and the block wall can't
            // end up with two definitions of the same streak.
            streakMillis = GetCurrentStreakUseCase.streakOf(ongoing),
            topApps = topApps,
            enforcing = enforcing,
        )
    }

    /**
     * Writes the state of every band, on and off, with nothing behind a condition that could skip
     * a view. The host reuses the inflated views when the layout id hasn't changed and applies
     * only the actions this object carries, so a band left unmentioned keeps whatever the last
     * render left on screen -- which is how the refresh spinner once got stuck on.
     */
    private fun applyDetail(context: Context, views: RemoteViews, tier: WidgetTier, detail: WidgetDetail) {
        // Amber while Monolith is actually enforcing, muted otherwise. Resolved in this process
        // rather than left to the launcher, which the chart deliberately avoids -- safe only
        // because both of these are defined once in values/ with no night variant, so there is no
        // second value for this process to pick the wrong one of.
        views.setInt(
            R.id.widget_mark,
            "setColorFilter",
            context.getColor(if (detail.enforcing) R.color.monolith_amber else R.color.widget_muted),
        )

        val statsVisibility = if (tier.showStats) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widget_divider, statsVisibility)
        views.setViewVisibility(R.id.widget_stats, statsVisibility)
        views.setViewVisibility(R.id.widget_apps, if (tier.showApps) View.VISIBLE else View.GONE)

        views.setTextViewText(R.id.widget_stat_blocks_value, detail.blocksToday.toString())
        views.setTextViewText(R.id.widget_stat_held_value, formatDuration(detail.streakMillis))

        APP_ROW_IDS.forEachIndexed { index, row ->
            // Rows beyond what this height earns stay collapsed even if there's data for them --
            // the tier, not the data, decides how many rows the band is tall enough to hold.
            val app = if (index < tier.appRows) detail.topApps.getOrNull(index) else null
            // The first row carries the empty state, so a day with nothing reached for reads as a
            // finished list rather than as a band that failed to draw. The rows below it collapse,
            // but the band around them keeps its fixed height, so the chart's budget never moves.
            val isEmptyState = app == null && index == 0 && tier.appRows > 0
            views.setViewVisibility(row.rowId, if (app != null || isEmptyState) View.VISIBLE else View.GONE)
            views.setTextViewText(
                row.labelId,
                app?.label ?: if (isEmptyState) context.getString(R.string.widget_apps_empty) else "",
            )
            views.setTextViewText(row.countId, app?.count?.toString() ?: "")
            // The empty-state row is visible but has no share or icon to show, and either would
            // otherwise be whatever the last render left there.
            views.setViewVisibility(row.barId, if (app != null) View.VISIBLE else View.GONE)
            views.setViewVisibility(row.iconId, if (app?.icon != null) View.VISIBLE else View.GONE)
            if (app != null) {
                views.setImageViewBitmap(row.barId, WidgetRowBarRenderer.render(app.barFraction))
                app.icon?.let { views.setImageViewBitmap(row.iconId, it) }
            }
        }
    }

    private data class WidgetSize(val widthDp: Int, val heightDp: Int, val tier: WidgetTier)

    private data class WidgetDetail(
        val blocksToday: Int = 0,
        val streakMillis: Long = 0L,
        val topApps: List<TopApp> = emptyList(),
        /** Whether Monolith is actually holding the line right now, which colours the mark. */
        val enforcing: Boolean = false,
    )

    private data class TopApp(val label: String, val icon: Bitmap?, val count: Int, val barFraction: Float)

    private data class AppRowIds(val rowId: Int, val iconId: Int, val barId: Int, val labelId: Int, val countId: Int)

    private fun buildViews(
        context: Context,
        size: WidgetSize,
        buckets: List<TimeSavedBucket>,
        totalMillis: Long,
        detail: WidgetDetail,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_time_saved)
        views.setTextViewText(R.id.widget_total, formatDuration(totalMillis))

        // The host reuses the inflated views when the layout id hasn't changed, applying only the
        // actions this object carries rather than starting from the XML again. So the spinner
        // showRefreshSpinner() turned on stays on unless the resting state is stated every time.
        views.setViewVisibility(R.id.widget_refresh_icon, View.VISIBLE)
        views.setViewVisibility(R.id.widget_refresh_progress, View.GONE)

        applyDetail(context, views, size.tier, detail)

        val density = context.resources.displayMetrics.density
        val chartWidthDp = (size.widthDp - PADDING_DP * 2).coerceAtLeast(MIN_CHART_WIDTH_DP)
        // Whatever the visible bands claim comes out of the chart's budget, so the bitmap is drawn
        // for the space it will actually be given rather than being squashed into what's left.
        val chartHeightDp = (size.heightDp - PADDING_DP * 2 - size.tier.reservedBandDp)
            .coerceAtLeast(MIN_CHART_HEIGHT_DP)

        val chart = TimeSavedChartRenderer.render(
            buckets = buckets,
            widthPx = (chartWidthDp * density).toInt(),
            heightPx = (chartHeightDp * density).toInt(),
            density = density,
        )
        views.setImageViewBitmap(R.id.widget_chart, chart)

        val launch = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_TIME_SAVED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TIME_SAVED, true)
        }
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                WIDGET_LAUNCH_REQUEST_CODE,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        // A click bound to a child wins over the one on the root, so refreshing never also opens
        // the app. The broadcast names this component explicitly, which is what lets it reach the
        // receiver without a matching action in the manifest's intent-filter.
        val refresh = Intent(context, TimeSavedWidgetProvider::class.java).setAction(ACTION_MANUAL_REFRESH)
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(
                context,
                REFRESH_REQUEST_CODE,
                refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return views
    }

    private companion object {
        val APP_ROW_IDS = listOf(
            AppRowIds(R.id.widget_app_1, R.id.widget_app_1_icon, R.id.widget_app_1_bar, R.id.widget_app_1_label, R.id.widget_app_1_count),
            AppRowIds(R.id.widget_app_2, R.id.widget_app_2_icon, R.id.widget_app_2_bar, R.id.widget_app_2_label, R.id.widget_app_2_count),
            AppRowIds(R.id.widget_app_3, R.id.widget_app_3_icon, R.id.widget_app_3_bar, R.id.widget_app_3_label, R.id.widget_app_3_count),
            AppRowIds(R.id.widget_app_4, R.id.widget_app_4_icon, R.id.widget_app_4_bar, R.id.widget_app_4_label, R.id.widget_app_4_count),
            AppRowIds(R.id.widget_app_5, R.id.widget_app_5_icon, R.id.widget_app_5_bar, R.id.widget_app_5_label, R.id.widget_app_5_count),
            AppRowIds(R.id.widget_app_6, R.id.widget_app_6_icon, R.id.widget_app_6_bar, R.id.widget_app_6_label, R.id.widget_app_6_count),
        )

        /** Matches the 16dp ImageView each app row lays out to its left. */
        const val APP_ICON_DP = 16

        const val ACTION_MANUAL_REFRESH = "com.monolith.app.widget.MANUAL_REFRESH"
        const val REFRESH_REQUEST_CODE = 1
        const val WIDGET_LAUNCH_REQUEST_CODE = 100

        /** How long the spinner stays up even when the data comes back sooner. */
        const val MIN_PROGRESS_MILLIS = 400L

        val ROLLOVER_ACTIONS = setOf(
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )

        // Fallbacks for the window between a widget being placed and the host reporting its size.
        const val DEFAULT_WIDTH_DP = 250
        const val DEFAULT_HEIGHT_DP = 110

        // From widget_time_saved.xml. The bands above and below the chart live in WidgetTiers,
        // which is what decides how many of them this widget is tall enough to show.
        const val PADDING_DP = 16
        /**
         * Floors for the bitmap itself. A host that honours minResizeHeight can't squeeze the
         * widget this far (see time_saved_widget_info.xml), but some launchers ignore it, and a
         * zero-sized bitmap would throw rather than just look bad.
         */
        const val MIN_CHART_WIDTH_DP = 48
        const val MIN_CHART_HEIGHT_DP = 32
    }
}
