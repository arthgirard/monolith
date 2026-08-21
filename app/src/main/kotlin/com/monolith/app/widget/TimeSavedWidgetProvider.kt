package com.monolith.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.monolith.app.R
import com.monolith.app.domain.model.TimePeriodType
import com.monolith.app.domain.model.TimeSavedBucket
import com.monolith.app.domain.usecase.ObserveActiveSessionStartUseCase
import com.monolith.app.domain.usecase.ObserveBlockSessionsUseCase
import com.monolith.app.domain.usecase.ObserveBlockStateUseCase
import com.monolith.app.domain.usecase.TimeSavedCalculator
import com.monolith.app.ui.MainActivity
import com.monolith.app.util.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Midnight, a manual clock change or a flight across timezones all move which day "today"
        // is; without these the widget would keep drawing yesterday's bars until its next tick.
        if (intent.action in ROLLOVER_ACTIONS) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimeSavedWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
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

    private fun render(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
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

                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(id, buildViews(context, manager, id, buckets, total))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        buckets: List<TimeSavedBucket>,
        totalMillis: Long,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_time_saved)
        views.setTextViewText(R.id.widget_total, formatDuration(totalMillis))

        val density = context.resources.displayMetrics.density
        val options = manager.getAppWidgetOptions(appWidgetId)
        // The chart bitmap is stretched to fill its ImageView, so it has to be sized for the
        // orientation actually on screen or the labels get squashed. The host reports both: the
        // portrait box is MIN_WIDTH x MAX_HEIGHT, the landscape one MAX_WIDTH x MIN_HEIGHT.
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val widthKey = if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
        val heightKey = if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        val widthDp = options.getInt(widthKey, DEFAULT_WIDTH_DP).takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
        val heightDp = options.getInt(heightKey, DEFAULT_HEIGHT_DP).takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP

        val chartWidthDp = (widthDp - PADDING_DP * 2).coerceAtLeast(MIN_CHART_WIDTH_DP)
        val chartHeightDp = (heightDp - PADDING_DP * 2 - TEXT_BAND_DP).coerceAtLeast(MIN_CHART_HEIGHT_DP)

        val chart = TimeSavedChartRenderer.render(
            buckets = buckets,
            widthPx = (chartWidthDp * density).toInt(),
            heightPx = (chartHeightDp * density).toInt(),
            density = density,
            colors = TimeSavedChartRenderer.Colors(
                bar = ContextCompat.getColor(context, R.color.widget_bar),
                track = ContextCompat.getColor(context, R.color.widget_track),
                label = ContextCompat.getColor(context, R.color.widget_muted),
            ),
        )
        views.setImageViewBitmap(R.id.widget_chart, chart)

        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TIME_SAVED, true)
        }
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return views
    }

    private companion object {
        val ROLLOVER_ACTIONS = setOf(
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )

        // Fallbacks for the window between a widget being placed and the host reporting its size.
        const val DEFAULT_WIDTH_DP = 250
        const val DEFAULT_HEIGHT_DP = 110

        // All from widget_time_saved.xml: its padding, and the header plus total above the chart.
        const val PADDING_DP = 16
        const val TEXT_BAND_DP = 63
        /**
         * Floors for the bitmap itself. A host that honours minResizeHeight can't squeeze the
         * widget this far (see time_saved_widget_info.xml), but some launchers ignore it, and a
         * zero-sized bitmap would throw rather than just look bad.
         */
        const val MIN_CHART_WIDTH_DP = 48
        const val MIN_CHART_HEIGHT_DP = 32
    }
}
