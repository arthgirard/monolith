package com.monolith.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.monolith.app.R
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.ImportantPersonRepository
import com.monolith.app.util.AppLocale
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
 * Cancels notifications from blocked apps while Monolith is enforcing, so a blocked app can't
 * reach the user through the notification shade either. Mirrors [AppBlockAccessibilityService]'s
 * state-tracking pattern, including its per-app unlock awareness ([AppUnlockRepository]) so a
 * code-breaker unlock exempts one app's notifications the same way it exempts that app's UI; the
 * two run independently since a device can enable one without the other. Cancels both newly
 * posted notifications and ones already in the shade the moment enforcement starts (Monolith
 * turning on). Every cancelled notification is held in memory and reposted (as a
 * Monolith-authored stand-in, since the OS doesn't let a listener repost another app's
 * notification under its own identity) the moment it stops being held back — Monolith turning
 * off, an emergency bypass starting, or that one app individually being unlocked — so nothing is
 * silently lost. The held queue is memory-only: a process death mid-block drops it, same as the
 * notifications themselves would have been dropped by the block.
 */
@AndroidEntryPoint
class NotificationBlockListenerService : NotificationListenerService() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var appRepository: AppRepository
    @Inject lateinit var importantPersonRepository: ImportantPersonRepository
    @Inject lateinit var appUnlockRepository: AppUnlockRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var blockState: BlockState = BlockState()
    @Volatile private var blockedPackages: Set<String> = emptySet()
    @Volatile private var importantPeople: List<ImportantPerson> = emptyList()
    @Volatile private var unlockedPackages: Map<String, Long> = emptyMap()
    private val heldNotifications = ConcurrentLinkedQueue<StatusBarNotification>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        serviceScope.launch {
            combine(
                blockRepository.observeBlockState(),
                appRepository.observeBlockedPackages(),
                importantPersonRepository.observeImportantPeople(),
                appUnlockRepository.observeUnlockedPackages(),
            ) { state, packages, people, unlocks -> Snapshot(state, packages, people, unlocks) }
                .collect { snapshot ->
                    val now = System.currentTimeMillis()
                    val wasEnforcing = blockState.isEnforcing(now)
                    val previousUnlocked = unlockedPackages
                    blockState = snapshot.blockState
                    blockedPackages = snapshot.blockedPackages
                    importantPeople = snapshot.importantPeople
                    unlockedPackages = snapshot.unlockedPackages
                    val isEnforcingNow = snapshot.blockState.isEnforcing(now)
                    // Enforcement can stop either because Monolith turned off or because an
                    // emergency bypass just started; either way, held notifications should
                    // surface. Enforcement resuming (bypass starting, not just Monolith turning
                    // on) re-arms the sweep too.
                    if (!wasEnforcing && isEnforcingNow) sweepExistingNotifications()
                    if (wasEnforcing && !isEnforcingNow) restoreHeldNotifications()

                    // A package that just became individually unlocked (code-breaker solved,
                    // waiver confirmed) releases only its own held notifications -- every other
                    // still-blocked package's queue is untouched.
                    snapshot.unlockedPackages.keys
                        .filter { pkg -> isCurrentlyUnlocked(pkg, now) && (previousUnlocked[pkg] ?: 0L) <= now }
                        .forEach { pkg -> restoreHeldNotifications(onlyPackage = pkg) }
                }
        }
    }

    private fun isCurrentlyUnlocked(packageName: String, now: Long) = (unlockedPackages[packageName] ?: 0L) > now

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()
        if (sbn.packageName == packageName) return
        if (!blockState.isEnforcing(now)) return
        if (sbn.packageName !in blockedPackages) return
        if (isCurrentlyUnlocked(sbn.packageName, now)) return
        if (isFromImportantPerson(sbn)) return
        heldNotifications.add(sbn)
        cancelNotification(sbn.key)
    }

    private data class Snapshot(
        val blockState: BlockState,
        val blockedPackages: Set<String>,
        val importantPeople: List<ImportantPerson>,
        val unlockedPackages: Map<String, Long>,
    )

    /** Lets a notification through untouched when its sender matches an allowlisted person for this app. */
    private fun isFromImportantPerson(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        return importantPeople
            .filter { it.packageName == sbn.packageName }
            .any { it.matches(title, text) }
    }

    /**
     * Monolith just switched on: notifications from blocked apps already sitting in the shade
     * were posted before we started enforcing, so [onNotificationPosted] never saw them. Sweep
     * them the same way, hold then cancel, so they get restored later instead of just lingering.
     */
    private fun sweepExistingNotifications() {
        val now = System.currentTimeMillis()
        val existing = runCatching { activeNotifications }.getOrNull() ?: return
        existing
            .filter {
                it.packageName != packageName &&
                    it.packageName in blockedPackages &&
                    !isCurrentlyUnlocked(it.packageName, now) &&
                    !isFromImportantPerson(it)
            }
            .forEach { sbn ->
                heldNotifications.add(sbn)
                cancelNotification(sbn.key)
            }
    }

    /**
     * Repost everything held back for [onlyPackage], or everything held if null (enforcement
     * stopping entirely -- off or bypassed) so the user can catch up.
     */
    private fun restoreHeldNotifications(onlyPackage: String? = null) {
        val toRestore = if (onlyPackage == null) {
            generateSequence { heldNotifications.poll() }.toList()
        } else {
            heldNotifications.filter { it.packageName == onlyPackage }.also { heldNotifications.removeAll(it) }
        }
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
            // The original app's own status-bar icon (not its launcher icon): reusing it keeps
            // the restored notification looking like it came from that app, not from Monolith.
            val smallIcon = runCatching {
                sbn.notification.smallIcon?.loadDrawable(this)?.toBitmap()
            }.getOrNull()?.let { IconCompat.createWithBitmap(it) }
                ?: IconCompat.createWithResource(this, R.drawable.ic_monolith_mark)

            // Reuse the original notification's own PendingIntent where possible: it opens the
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
                .setSmallIcon(smallIcon)
                .setContentTitle(title ?: appLabel)
                .setContentText(text)
                .setSubText(getString(R.string.missed_notification_subtext, appLabel))
                .setLargeIcon(appIcon)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setGroup(MISSED_GROUP_KEY)
                .build()

            notificationManager.notify(sbn.key.hashCode(), restored)
        }

        postMissedSummary(notificationManager, toRestore.size)
    }

    /**
     * The group summary. Without one, unlocking after a long block drops the whole held backlog
     * into the shade as N loose notifications -- the individual entries already carry
     * MISSED_GROUP_KEY, but a group with no summary is not reliably collapsed. One line saying
     * how many there are keeps a catch-up from reading as an explosion.
     */
    private fun postMissedSummary(notificationManager: NotificationManager, count: Int) {
        val summary = NotificationCompat.Builder(this, MISSED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monolith_mark)
            .setColor(ContextCompat.getColor(this, R.color.monolith_amber))
            .setContentTitle(getString(R.string.missed_notification_summary, count))
            .setGroup(MISSED_GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(MISSED_SUMMARY_ID, summary)
    }

    private fun ensureMissedChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MISSED_CHANNEL_ID,
            getString(R.string.missed_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.missed_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MISSED_CHANNEL_ID = "monolith_missed_notifications"
        private const val MISSED_GROUP_KEY = "monolith_missed_group"

        // In the 2xxx status range, clear of EnforcementForegroundService's 1001 and of the
        // restored notifications, which key off the original notification's own hash.
        private const val MISSED_SUMMARY_ID = 2003
    }
}
