package com.monolith.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.ui.MainActivity
import com.monolith.app.util.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The accessibility service itself resumes automatically once Android finishes booting, since
 * the OS re-binds any service the user enabled in Accessibility settings. This receiver exists
 * for the case that matters: Block Mode was active before reboot but the service somehow isn't
 * enabled (OEM battery managers love disabling accessibility services) — nudge the user instead
 * of silently leaving blocking off.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var blockRepository: BlockRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val state = blockRepository.observeBlockState().first()
                val serviceEnabled = PermissionUtils.isAccessibilityServiceEnabled(
                    context,
                    AppBlockAccessibilityService::class.java,
                )
                if (state.isActive && !serviceEnabled) {
                    notifyServiceDisabled(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun notifyServiceDisabled(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monolith status",
                NotificationManager.IMPORTANCE_HIGH,
            )
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Monolith needs re-enabling")
            .setContentText("Block Mode was active before reboot, but the Accessibility service was turned off. Tap to fix.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(BOOT_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "monolith_status"
        private const val BOOT_NOTIFICATION_ID = 1001
    }
}
