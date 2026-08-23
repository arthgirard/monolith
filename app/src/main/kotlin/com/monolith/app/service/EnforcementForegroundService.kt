package com.monolith.app.service

import android.app.Notification
import android.graphics.Bitmap
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import com.monolith.app.R
import com.monolith.app.domain.model.BlockSession
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

    // Keeps the drawn session honest between state changes. It walks a breach's countdown down a
    // minute at a time and draws the last one at the moment the window closes -- so the
    // chronometer resumes the instant it's over even though nothing writes to the block-state
    // store then -- and then keeps going at a slower step, because the portrait's right edge is
    // now and the newest held time is missing from the bitmap until something redraws it.
    // Re-armed (and any stale prior instance cancelled) on every change.
    private var renderJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Nothing has been read yet, so this first post claims only what is certain: Monolith is
        // on, the session is at zero, and the wall is whole. Any breach lands on the next render.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                status = EnforcementStatus.Enforcing,
                elapsedMillis = 0L,
                spans = emptyList(),
                now = System.currentTimeMillis(),
            ),
        )

        combine(
            blockRepository.observeBlockState(),
            blockRepository.observeActiveSessionStart(),
            appUnlockRepository.observeUnlockedPackages(),
            blockRepository.observeCycleStart(),
            blockRepository.observeBlockSessions(),
        ) { state, sessionStart, unlocks, cycleStart, sessions ->
            Snapshot(state, sessionStart, unlocks, cycleStart, sessions)
        }
            .onEach(::onStateChanged)
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Everything one render needs, so the five flows above stay one value from here down. */
    private data class Snapshot(
        val state: BlockState,
        val sessionStart: Long?,
        val unlocks: Map<String, Long>,
        val cycleStart: Long?,
        val sessions: List<BlockSession>,
    )

    private fun onStateChanged(snapshot: Snapshot) {
        renderJob?.cancel()

        if (!snapshot.state.isActive) {
            stopSelf()
            return
        }

        render(snapshot)
        renderJob = serviceScope.launch { keepDrawn(snapshot) }
    }

    /**
     * Redraws for as long as Monolith is on: once per remaining minute while a breach counts
     * down, and at a slower step the rest of the time, since a bar whose right edge is now goes
     * stale on its own. The step is deliberately coarse -- every re-post is a chance for the
     * shade to collapse a row the user had opened.
     *
     * The state is recomputed each pass rather than captured, because a bypass ending while an
     * app unlock is still live moves the display from one to the other at an instant no store is
     * written to, and nothing else would notice.
     */
    private suspend fun keepDrawn(snapshot: Snapshot) {
        while (true) {
            val now = System.currentTimeMillis()
            val expiresAt = EnforcementSegments
                .statusFor(snapshot.state, snapshot.unlocks, now)
                .expiresAtMillis
            val wait = if (expiresAt != null) {
                EnforcementSegments.millisToNextMinute(expiresAt, now)
            } else {
                EnforcementSegments.millisToNextPortraitStep(cycleStartOf(snapshot, now), now)
            }
            // A breach reported live but already expired: let the clock move on rather than
            // spinning, and re-read above.
            delay(if (wait <= 0L) SETTLE_MILLIS else wait)
            render(snapshot)
        }
    }

    /**
     * Time already accrued this session is [TimeSavedCalculator.ongoingSessions]' sum, which
     * already carves out any bypass window -- so pausing and resuming just means recomputing this
     * against the same [sessionStart] rather than restarting a fresh clock. A per-app unlock is
     * different in kind: granting it ends the streak outright and fast-forwards [sessionStart]
     * past the unlock window (see MonolithPreferences.grantAppUnlock), so this reads as zero for
     * the whole window and the timer starts counting up from zero when it expires.
     */
    /**
     * The portrait's left edge. A session already running when the cycle start was first recorded
     * has none, so it falls back to the streak's own start: that draws less than the whole cycle,
     * which is honest, where claiming an unbroken bar would not be.
     */
    private fun cycleStartOf(snapshot: Snapshot, now: Long): Long =
        snapshot.cycleStart ?: snapshot.sessionStart?.coerceAtMost(now) ?: now

    private fun render(snapshot: Snapshot) {
        val now = System.currentTimeMillis()
        val ongoing = TimeSavedCalculator.ongoingSessions(snapshot.state, snapshot.sessionStart, now)
        val status = EnforcementSegments.statusFor(snapshot.state, snapshot.unlocks, now)

        // The cycle's held time comes from two places: the segments already committed -- an app
        // unlock commits the running one on its way past -- and the one still running. Everything
        // between them is a pause, which is what draws the holes without this having to know what
        // kind of pause each one was.
        val spans = SessionPortrait.spansFor(cycleStartOf(snapshot, now), now, snapshot.sessions + ongoing)

        val notification = buildNotification(status, ongoing.sumOf { it.durationMillis }, spans, now)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        status: EnforcementStatus,
        elapsedMillis: Long,
        spans: List<PortraitSpan>,
        now: Long,
    ): Notification {
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

        builder
            .setContentText(stateLine(status))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(buildStatusView(status, spans, now))
            .setShowWhen(true)
            .setUsesChronometer(true)

        val expiresAt = status.expiresAtMillis
        if (expiresAt != null) {
            // Counting down to the moment the wall closes again, rather than freezing the elapsed
            // time as this used to. The chronometer counts toward `when`, so the remaining time
            // ticks itself and a breach costs no re-posts beyond the once-a-minute segment redraw.
            builder.setChronometerCountDown(true).setWhen(expiresAt)
        } else {
            builder.setChronometerCountDown(false).setWhen(now - elapsedMillis)
        }

        return builder.build()
    }

    /** The one line of text, in the collapsed row and again above the segments. */
    private fun stateLine(status: EnforcementStatus): String = when (status) {
        EnforcementStatus.Enforcing -> getString(R.string.enforcement_notification_body)
        is EnforcementStatus.Bypass -> getString(R.string.enforcement_notification_body_paused)
        is EnforcementStatus.AppUnlocked -> getString(
            R.string.enforcement_notification_body_paused_unlock_app,
            appLabel(status.packageName),
        )
    }

    /**
     * The expanded body: the state named, and the wall drawn either whole or with a segment left
     * for each minute the breach still has.
     */
    private fun buildStatusView(
        status: EnforcementStatus,
        spans: List<PortraitSpan>,
        now: Long,
    ): RemoteViews {
        val views = RemoteViews(packageName, R.layout.notification_enforcement)

        // Amber marks the wall that is open right now; a pause already closed recedes to a hole.
        // All three are resolved here rather than left to the shade because none has a night
        // variant to resolve wrongly, the same exemption the widget's mark relies on.
        val muted = ContextCompat.getColor(this, R.color.widget_muted)
        views.setImageViewBitmap(
            R.id.notification_segments,
            SessionPortraitRenderer.render(
                spans = spans,
                heldColor = muted,
                pausedColor = ColorUtils.setAlphaComponent(muted, PAST_PAUSE_ALPHA),
                livePausedColor = ContextCompat.getColor(this, R.color.monolith_amber),
            ),
        )

        // The minutes left in the pause, under the session it is interrupting. Both the bitmap and
        // the visibility are written every render: the host reapplies onto the views already on
        // screen, so a row left unmentioned keeps whatever the last render put in it.
        val minutes = EnforcementSegments.rowFor(status, now)
        if (minutes != null) {
            views.setImageViewBitmap(
                R.id.notification_minutes,
                MinuteSegmentRenderer.render(
                    row = minutes,
                    litColor = ContextCompat.getColor(this, R.color.monolith_amber),
                    spentColor = ColorUtils.setAlphaComponent(muted, PAST_PAUSE_ALPHA),
                ),
            )
        }
        views.setViewVisibility(
            R.id.notification_minutes,
            if (minutes != null) View.VISIBLE else View.GONE,
        )

        views.setTextViewText(
            R.id.notification_state,
            if (status is EnforcementStatus.Enforcing) {
                getString(R.string.enforcement_notification_state_held)
            } else {
                stateLine(status)
            },
        )

        val icon = (status as? EnforcementStatus.AppUnlocked)?.let { appIcon(it.packageName) }
        views.setViewVisibility(
            R.id.notification_app_icon,
            if (icon != null) View.VISIBLE else View.GONE,
        )
        if (icon != null) views.setImageViewBitmap(R.id.notification_app_icon, icon)

        return views
    }

    // Read straight off PackageManager, the way NotificationBlockListenerService does for the
    // notifications it restores: both run off the main thread and both want the label the user
    // knows the app by. An uninstall between the unlock and this render falls back to the
    // package name, and to no icon at all.
    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * Sized explicitly: an adaptive icon's intrinsic size is 108dp at the display's density, which
     * is a few hundred kilobytes of ARGB_8888 posted into a notification once a minute. The icon
     * draws as the full square the app ships, unmasked, matching how the app selector shows it.
     */
    private fun appIcon(packageName: String): Bitmap? {
        val sizePx = (ICON_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return runCatching {
            packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
        }.getOrNull()
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

        /** How long the breach loop waits before re-reading a state that has just run out. */
        private const val SETTLE_MILLIS = 1_000L

        /** A pause that has closed, held back far enough to read as a hole without vanishing. */
        private const val PAST_PAUSE_ALPHA = 56

        private const val ICON_DP = 32f
        private const val NOTIFICATION_ID = 1001
    }
}
