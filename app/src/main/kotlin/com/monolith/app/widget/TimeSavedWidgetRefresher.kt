package com.monolith.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the widget past its 30-minute system refresh floor. Blocking starting or stopping, or a
 * bypass opening or closing, changes today's total immediately; waiting up to half an hour to
 * show that would make the widget look broken.
 */
@Singleton
class TimeSavedWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun refresh() {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, TimeSavedWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return

        context.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                this.component = component
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            },
        )
    }
}
