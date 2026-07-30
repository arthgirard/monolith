package com.monolith.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.BlockRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

/**
 * Cancels notifications from blocked apps while Block Mode is enforcing, so a blocked app can't
 * reach the user through the notification shade either. Mirrors [AppBlockAccessibilityService]'s
 * state-tracking pattern; the two run independently since a device can enable one without the
 * other. Every cancelled notification is held in memory and reposted (as a Monolith-authored
 * stand-in — the OS doesn't let a listener repost another app's notification under its own
 * identity) the moment Block Mode turns off, so nothing is silently lost. The held queue is
 * memory-only: a process death mid-block drops it, same as the notifications themselves would
 * have been dropped by the block.
 */
@AndroidEntryPoint
class NotificationBlockListenerService : NotificationListenerService() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var appRepository: AppRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var blockState: BlockState = BlockState()
    @Volatile private var blockedPackages: Set<String> = emptySet()
    private val heldNotifications = ConcurrentLinkedQueue<StatusBarNotification>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        serviceScope.launch {
            combine(
                blockRepository.observeBlockState(),
                appRepository.observeBlockedPackages(),
            ) { state, packages -> state to packages }
                .collect { (state, packages) ->
                    val wasActive = blockState.isActive
                    blockState = state
                    blockedPackages = packages
                    if (wasActive && !state.isActive) restoreHeldNotifications()
                }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (!blockState.isEnforcing(System.currentTimeMillis())) return
        if (sbn.packageName !in blockedPackages) return
        heldNotifications.add(sbn)
        cancelNotification(sbn.key)
    }

    /** Block Mode just turned off: repost everything it swallowed so the user can catch up. */
    private fun restoreHeldNotifications() {
        val toRestore = generateSequence { heldNotifications.poll() }.toList()
        if (toRestore.isEmpty()) return

        ensureMissedChannel()
        val notificationManager = getSystemService(NotificationManager::class.java)
        val pm = packageManager

        toRestore.forEach { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)
            val appLabel = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0))
            }.getOrDefault(sbn.packageName)
            val appIcon = runCatching {
                pm.getApplicationIcon(sbn.packageName).toBitmap()
            }.getOrNull()

            // Reuse the original notification's own PendingIntent where possible — it opens the
            // exact screen the source app intended (e.g. a specific chat), not just its launcher.
            val pendingIntent = sbn.notification.contentIntent
                ?: pm.getLaunchIntentForPackage(sbn.packageName)?.let { launchIntent ->
                    PendingIntent.getActivity(
                        this,
                        sbn.key.hashCode(),
                        launchIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }

            val restored = NotificationCompat.Builder(this, MISSED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title ?: appLabel)
                .setContentText(text)
                .setSubText("Missed while Block Mode was on • $appLabel")
                .setLargeIcon(appIcon)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setGroup(MISSED_GROUP_KEY)
                .build()

            notificationManager.notify(sbn.key.hashCode(), restored)
        }
    }

    private fun ensureMissedChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MISSED_CHANNEL_ID,
            "Missed notifications",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MISSED_CHANNEL_ID = "monolith_missed_notifications"
        private const val MISSED_GROUP_KEY = "monolith_missed_group"
    }
}
