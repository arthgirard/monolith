package com.monolith.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.monolith.app.R
import com.monolith.app.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one channel for "Monolith can't do its job right now". Both cases it covers used to be
 * handled separately or not at all: the boot notification built its own channel inline with
 * hardcoded copy, and a revoked overlay permission degraded silently to a home-press, leaving
 * the user with a blocker that appeared to have simply stopped working.
 *
 * Voice for everything here: the title states what is wrong, the body states the consequence,
 * and the single action goes to the place that fixes it.
 */
@Singleton
class StatusNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun notifyAccessibilityServiceOff() {
        notify(
            id = BOOT_NOTIFICATION_ID,
            title = context.getString(R.string.status_boot_title),
            body = context.getString(R.string.status_boot_body),
            actionIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_HOME
                putExtra(MainActivity.EXTRA_OPEN_HOME, true)
            },
        )
    }

    /**
     * Posted when [BlockOverlayGuard] can't paint, which means blocked apps are only being sent
     * to the home screen with no explanation on screen. Deep-links straight to the per-app
     * overlay permission page rather than to Monolith, since that's the only thing that fixes it.
     */
    fun notifyOverlayPermissionMissing() {
        notify(
            id = OVERLAY_PERMISSION_NOTIFICATION_ID,
            title = context.getString(R.string.status_overlay_permission_title),
            body = context.getString(R.string.status_overlay_permission_body),
            actionIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    private fun notify(id: Int, title: String, body: String, actionIntent: Intent) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.status_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.status_notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            actionIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monolith_mark)
            .setColor(ContextCompat.getColor(context, R.color.monolith_amber))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.status_open_settings), pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notification)
    }

    companion object {
        private const val CHANNEL_ID = "monolith_status"

        // Notification ids are per-app, not per-channel. The boot notice used to be 1001, the
        // same id EnforcementForegroundService posts its ongoing notification under, so posting
        // one silently replaced the other. Status notifications live in their own 2xxx range.
        private const val BOOT_NOTIFICATION_ID = 2001
        private const val OVERLAY_PERMISSION_NOTIFICATION_ID = 2002
    }
}
