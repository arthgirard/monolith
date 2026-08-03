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
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.usecase.TimeSavedCalculator
import com.monolith.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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

    // Fires exactly when a running bypass expires, so the chronometer resumes the instant it's
    // over even though nothing writes to the block-state store at that moment. Re-armed (and any
    // stale prior instance cancelled) on every state change.
    private var bypassResumeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(elapsedMillis = 0L, bypassActive = false))

        blockRepository.observeBlockState()
            .combine(blockRepository.observeActiveSessionStart()) { state, sessionStart -> state to sessionStart }
            .onEach { (state, sessionStart) -> onStateChanged(state, sessionStart) }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onStateChanged(state: BlockState, sessionStart: Long?) {
        bypassResumeJob?.cancel()

        if (!state.isActive) {
            stopSelf()
            return
        }

        render(state, sessionStart)

        val bypassExpiresAt = state.bypassExpiresAtMillis
        val now = System.currentTimeMillis()
        if (bypassExpiresAt != null && state.isBypassActive(now)) {
            bypassResumeJob = serviceScope.launch {
                delay(bypassExpiresAt - now)
                render(state, sessionStart)
            }
        }
    }

    /**
     * Time already accrued this session is [TimeSavedCalculator.ongoingSessions]' sum, which
     * already carves out any bypass window -- so pausing and resuming just means recomputing this
     * against the same, never-cleared [sessionStart] rather than restarting a fresh clock.
     */
    private fun render(state: BlockState, sessionStart: Long?) {
        val now = System.currentTimeMillis()
        val elapsedMillis = TimeSavedCalculator.ongoingSessions(state, sessionStart, now).sumOf { it.durationMillis }
        val notification = buildNotification(elapsedMillis, bypassActive = state.isBypassActive(now))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(elapsedMillis: Long, bypassActive: Boolean): Notification {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.enforcement_notification_title))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)

        if (bypassActive) {
            // Chronometer off: freezes the displayed time at exactly what render() last computed,
            // instead of ticking through the bypass window it's not supposed to count.
            builder
                .setContentText(getString(R.string.enforcement_notification_body_paused))
                .setShowWhen(false)
                .setUsesChronometer(false)
        } else {
            builder
                .setContentText(getString(R.string.enforcement_notification_body))
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - elapsedMillis)
        }

        return builder.build()
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
