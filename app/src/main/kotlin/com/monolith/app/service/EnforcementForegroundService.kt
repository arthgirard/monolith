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
import androidx.core.content.ContextCompat
import com.monolith.app.R
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.repository.AppUnlockRepository
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
    @Inject lateinit var appUnlockRepository: AppUnlockRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Fires exactly when the last thing pausing the timer (a bypass, a per-app unlock) expires, so
    // the chronometer resumes the instant it's over even though nothing writes to the block-state
    // store at that moment. Re-armed (and any stale prior instance cancelled) on every change.
    private var resumeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(elapsedMillis = 0L, pauseBodyRes = null))

        combine(
            blockRepository.observeBlockState(),
            blockRepository.observeActiveSessionStart(),
            appUnlockRepository.observeUnlockedPackages(),
        ) { state, sessionStart, unlocks -> Triple(state, sessionStart, unlocks) }
            .onEach { (state, sessionStart, unlocks) -> onStateChanged(state, sessionStart, unlocks) }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onStateChanged(state: BlockState, sessionStart: Long?, unlocks: Map<String, Long>) {
        resumeJob?.cancel()

        if (!state.isActive) {
            stopSelf()
            return
        }

        render(state, sessionStart, unlocks)

        val now = System.currentTimeMillis()
        val pausedUntil = pausedUntil(state, unlocks, now) ?: return
        resumeJob = serviceScope.launch {
            delay(pausedUntil - now)
            render(state, sessionStart, unlocks)
        }
    }

    /**
     * When the timer stops being paused, or null if it isn't. Both a running emergency bypass and
     * a live per-app unlock pause it: neither one's minutes count toward the streak, so neither
     * should tick. Overlapping windows hold the pause until the last of them expires.
     */
    private fun pausedUntil(state: BlockState, unlocks: Map<String, Long>, now: Long): Long? {
        val bypassUntil = state.bypassExpiresAtMillis?.takeIf { state.isBypassActive(now) }
        val unlockUntil = unlocks.values.filter { it > now }.maxOrNull()
        return listOfNotNull(bypassUntil, unlockUntil).maxOrNull()
    }

    /**
     * Time already accrued this session is [TimeSavedCalculator.ongoingSessions]' sum, which
     * already carves out any bypass window -- so pausing and resuming just means recomputing this
     * against the same [sessionStart] rather than restarting a fresh clock. A per-app unlock is
     * different in kind: granting it ends the streak outright and fast-forwards [sessionStart]
     * past the unlock window (see MonolithPreferences.grantAppUnlock), so this reads as zero for
     * the whole window and the timer starts counting up from zero when it expires.
     */
    private fun render(state: BlockState, sessionStart: Long?, unlocks: Map<String, Long>) {
        val now = System.currentTimeMillis()
        val elapsedMillis = TimeSavedCalculator.ongoingSessions(state, sessionStart, now).sumOf { it.durationMillis }
        val pauseBodyRes = when {
            pausedUntil(state, unlocks, now) == null -> null
            state.isBypassActive(now) -> R.string.enforcement_notification_body_paused
            else -> R.string.enforcement_notification_body_paused_unlock
        }
        val notification = buildNotification(elapsedMillis, pauseBodyRes)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /** [pauseBodyRes] non-null means the timer is paused, and says which pause the user is in. */
    private fun buildNotification(elapsedMillis: Long, pauseBodyRes: Int?): Notification {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // Monolith's own mark rather than Android's stock padlock, and the brand amber
            // for the tint the system applies to it. Notifications are the app's only presence
            // in the shade; borrowing a system glyph there gives that presence to no one.
            .setSmallIcon(R.drawable.ic_monolith_mark)
            .setColor(ContextCompat.getColor(this, R.color.monolith_amber))
            .setContentTitle(getString(R.string.enforcement_notification_title))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)

        if (pauseBodyRes != null) {
            // Chronometer off: freezes the displayed time at exactly what render() last computed,
            // instead of ticking through a window it's not supposed to count.
            builder
                .setContentText(getString(pauseBodyRes))
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
        ).apply {
            description = getString(R.string.enforcement_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "monolith_enforcement"
        private const val NOTIFICATION_ID = 1001
    }
}
