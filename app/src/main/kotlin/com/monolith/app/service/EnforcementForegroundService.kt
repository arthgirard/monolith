package com.monolith.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.monolith.app.R
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Keeps this process alive and foreground-prioritized for as long as Monolith is on. Without
 * this, Android can cache-kill an idle Monolith process between blocked-app attempts; the next
 * time a blocked app opens, [AppBlockAccessibilityService] has to cold-start the whole process
 * (Hilt graph, DataStore's first read, ...) before it can react, leaving a multi-second window
 * where the blocked app is visible. Started by [com.monolith.app.MonolithApplication] the moment
 * Monolith turns on (including right after a cold start, if it turns out to still be on); stops
 * itself the moment it observes Monolith turning off.
 */
@AndroidEntryPoint
class EnforcementForegroundService : Service() {

    @Inject lateinit var blockRepository: BlockRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        blockRepository.observeBlockState()
            .onEach { state -> if (!state.isActive) stopSelf() }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.enforcement_notification_title))
            .setContentText(getString(R.string.enforcement_notification_body))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.enforcement_notification_channel),
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "monolith_enforcement"
        private const val NOTIFICATION_ID = 1001
    }
}
