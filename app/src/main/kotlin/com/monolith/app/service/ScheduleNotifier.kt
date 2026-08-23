package com.monolith.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import com.monolith.app.R
import com.monolith.app.ui.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user a schedule just switched Monolith on. Worth a notification because this is the
 * one activation path they didn't initiate: without it, the first sign would be an app refusing
 * to open, with no clue why.
 *
 * Its own channel, so it can be silenced without touching the enforcement notification the
 * foreground service depends on.
 */
@Singleton
class ScheduleNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notifyActivated() {
        ensureChannel()

        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_HOME
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_HOME, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monolith_mark)
            .setColor(ContextCompat.getColor(context, R.color.monolith_amber))
            .setContentTitle(context.getString(R.string.schedule_notification_title))
            .setContentText(context.getString(R.string.schedule_notification_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        // Silently dropped if POST_NOTIFICATIONS was never granted (onboarding asks for it), which
        // is the right failure: enforcement is already running either way.
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.schedule_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.schedule_notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "monolith_schedule"

        // Deliberately not 1001: the enforcement service and the boot receiver already share that
        // id, and a third claimant would make the collision worse.
        const val NOTIFICATION_ID = 1002
    }
}
