package com.monolith.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
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
 * silently lost. The backlog is memory-only and bounded ([HeldBacklog]): a process death mid-block
 * drops it, same as the notifications themselves would have been dropped by the block.
 *
 * A catch-up posts one stand-in per app, not one per notification: Android keeps at most 50 of a
 * package's notifications and silently drops the rest, and a days-long block over dozens of apps
 * holds far more than that. Past [MAX_RESTORED_APPS] apps, the least recent fold into a single
 * "other apps" stand-in. Only the summary alerts, once.
 *
 * A stand-in can't be dismissed by its source app the way the original could (the app cancels
 * an original that's already gone), so [clearRestored] does it instead once the user has been
 * in that app, see AppBlockAccessibilityService.
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
    private val held = HeldBacklog<HeldNotification>()

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
        hold(sbn)
    }

    /**
     * Keeps only what a stand-in shows. An app's own group summary is cancelled but not kept: it
     * repeats its children, and would read as one more missed notification.
     */
    private fun hold(sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) {
            val extras = notification.extras
            held.add(
                sbn.packageName,
                sbn.key,
                HeldNotification(
                    title = extras.getCharSequence(Notification.EXTRA_TITLE),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT),
                    contentIntent = notification.contentIntent,
                    smallIcon = notification.smallIcon,
                ),
                sbn.postTime,
            )
        }
        cancelNotification(sbn.key)
    }

    private class HeldNotification(
        val title: CharSequence?,
        val text: CharSequence?,
        val contentIntent: PendingIntent?,
        val smallIcon: Icon?,
    )

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
            .forEach(::hold)
    }

    /**
     * Repost everything held back for [onlyPackage], or everything held if null (enforcement
     * stopping entirely -- off or bypassed) so the user can catch up.
     */
    private fun restoreHeldNotifications(onlyPackage: String? = null) {
        val apps = held.drain(onlyPackage)
        if (apps.isEmpty()) return

        ensureMissedChannel()
        val notificationManager = getSystemService(NotificationManager::class.java)
        // Posting is asynchronous, so the shade is read before this batch goes in. An earlier
        // catch-up may still be sitting there: its stand-ins are merged into, not stacked beside.
        val inShade = restoredInShade(notificationManager).groupBy { it.tag!! }
        val counts = inShade.mapValues { (_, standIns) -> standIns.sumOf(::missedCount) }.toMutableMap()

        // Most recent first, so the apps that fold into "other apps" are the least recent.
        var free = MAX_RESTORED_APPS - (inShade.keys - OTHERS_TAG).size
        val (own, others) = apps.partition { it.packageName in inShade || free-- > 0 }
        own.forEach { app ->
            counts[app.packageName] = postStandIn(notificationManager, app, inShade[app.packageName].orEmpty())
        }
        if (others.isNotEmpty()) {
            counts[OTHERS_TAG] = postOthers(notificationManager, others, inShade[OTHERS_TAG].orEmpty())
        }
        postMissedSummary(this, notificationManager, counts.values.sum())
    }

    /** One app's catch-up, merged with what an earlier one left in the shade. Returns its count. */
    private fun postStandIn(
        notificationManager: NotificationManager,
        app: HeldBacklog.App<HeldNotification>,
        previous: List<StatusBarNotification>,
    ): Int {
        val latest = app.newestFirst.first()
        val appLabel = labelOf(app.packageName)
        val count = app.count + previous.sumOf(::missedCount)
        val lines = (app.newestFirst.map { lineOf(it.title, it.text) } + previous.flatMap(::linesOf))
            .take(HeldBacklog.PER_APP)

        // The original app's own status-bar icon (not its launcher icon): reusing it keeps the
        // restored notification looking like it came from that app, not from Monolith.
        val smallIcon = runCatching { latest.smallIcon?.loadDrawable(this)?.toBitmap() }
            .getOrNull()?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithResource(this, R.drawable.ic_monolith_mark)

        // Reuse the newest notification's own PendingIntent where possible: it opens the exact
        // screen the source app intended (e.g. a specific chat), not just its launcher.
        val pendingIntent = latest.contentIntent
            ?: packageManager.getLaunchIntentForPackage(app.packageName)?.let { launchIntent ->
                PendingIntent.getActivity(
                    this,
                    app.packageName.hashCode(),
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        val builder = standInBuilder(count, lines)
            .setSmallIcon(smallIcon)
            .setContentTitle(latest.title ?: appLabel)
            .setContentText(latest.text)
            .setSubText(getString(R.string.missed_notification_subtext, appLabel))
            .setLargeIcon(runCatching { packageManager.getApplicationIcon(app.packageName).toBitmap() }.getOrNull())
            .setContentIntent(pendingIntent)
        if (count > 1) {
            builder.setStyle(
                NotificationCompat.InboxStyle()
                    .setBigContentTitle(appLabel)
                    .also { style -> lines.forEach(style::addLine) }
                    .also { style ->
                        if (count > lines.size) style.setSummaryText(getString(R.string.missed_notification_more, count - lines.size))
                    },
            )
        }

        // Tagged with the source package so clearRestored can find this app's stand-in. One id
        // per app, so a later catch-up replaces it; any other id is one per notification, from
        // before stand-ins were merged.
        notificationManager.notify(app.packageName, STAND_IN_ID, builder.build())
        previous.filter { it.id != STAND_IN_ID }.forEach { notificationManager.cancel(it.tag, it.id) }
        return count
    }

    /** The apps past [MAX_RESTORED_APPS], one line each. Returns how many notifications it covers. */
    private fun postOthers(
        notificationManager: NotificationManager,
        apps: List<HeldBacklog.App<HeldNotification>>,
        previous: List<StatusBarNotification>,
    ): Int {
        val perApp = LinkedHashMap<String, Int>()
        apps.forEach { perApp[it.packageName] = it.count }
        previous.forEach { standIn ->
            val extras = standIn.notification.extras
            val packages = extras.getStringArray(EXTRA_OTHER_PACKAGES) ?: return@forEach
            val counts = extras.getIntArray(EXTRA_OTHER_COUNTS) ?: return@forEach
            packages.zip(counts.toList()).forEach { (pkg, n) -> perApp[pkg] = (perApp[pkg] ?: 0) + n }
        }
        val count = perApp.values.sum()
        val lines = perApp.map { (pkg, n) -> "${labelOf(pkg)} · $n" }
        val title = resources.getQuantityString(R.plurals.missed_notification_other_apps, perApp.size, perApp.size)

        val builder = standInBuilder(count, lines)
            .setSmallIcon(R.drawable.ic_monolith_mark)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", "))
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setBigContentTitle(title)
                    .also { style -> lines.forEach(style::addLine) },
            )
            .addExtras(
                Bundle().apply {
                    putStringArray(EXTRA_OTHER_PACKAGES, perApp.keys.toTypedArray())
                    putIntArray(EXTRA_OTHER_COUNTS, perApp.values.toIntArray())
                },
            )
        notificationManager.notify(OTHERS_TAG, STAND_IN_ID, builder.build())
        return count
    }

    /** What every stand-in shares: grouped, silent under the summary, and carrying its count. */
    private fun standInBuilder(count: Int, lines: List<CharSequence>) =
        NotificationCompat.Builder(this, MISSED_CHANNEL_ID)
            .setAutoCancel(true)
            .setGroup(MISSED_GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setNumber(count)
            .addExtras(
                Bundle().apply {
                    putInt(EXTRA_MISSED_COUNT, count)
                    putCharSequenceArray(EXTRA_MISSED_LINES, lines.toTypedArray())
                },
            )

    private fun labelOf(packageName: String): CharSequence = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
    }.getOrDefault(packageName)

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

        // Tagged ids live apart from untagged ones, so this only has to be one value.
        private const val STAND_IN_ID = 2004

        // Not a valid package name (no dot), so it can't collide with an app's stand-in.
        private const val OTHERS_TAG = "monolith-other-apps"

        // Beside this, the summary and Monolith's own status notifications, well under the 50
        // Android keeps per package.
        private const val MAX_RESTORED_APPS = 20

        private const val EXTRA_MISSED_COUNT = "com.monolith.app.MISSED_COUNT"
        private const val EXTRA_MISSED_LINES = "com.monolith.app.MISSED_LINES"
        private const val EXTRA_OTHER_PACKAGES = "com.monolith.app.OTHER_PACKAGES"
        private const val EXTRA_OTHER_COUNTS = "com.monolith.app.OTHER_COUNTS"

        // A stand-in from before counts were stored stands for one notification.
        private fun missedCount(standIn: StatusBarNotification) =
            standIn.notification.extras.getInt(EXTRA_MISSED_COUNT, 1)

        private fun linesOf(standIn: StatusBarNotification): List<CharSequence> {
            val extras = standIn.notification.extras
            return extras.getCharSequenceArray(EXTRA_MISSED_LINES)?.toList()
                ?: listOf(lineOf(extras.getCharSequence(Notification.EXTRA_TITLE), extras.getCharSequence(Notification.EXTRA_TEXT)))
        }

        private fun lineOf(title: CharSequence?, text: CharSequence?): CharSequence =
            listOfNotNull(title, text).joinToString(": ")

        private fun restoredInShade(notificationManager: NotificationManager) =
            runCatching { notificationManager.activeNotifications }.getOrDefault(emptyArray())
                .filter { it.notification.group == MISSED_GROUP_KEY && it.tag != null }

        /**
         * Dismisses every stand-in restored from [sourcePackage], then shrinks the summary to
         * what's left, or drops it with the last one.
         */
        fun clearRestored(context: Context, sourcePackage: String) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val inShade = restoredInShade(notificationManager)
            val (stale, kept) = inShade.partition { it.tag == sourcePackage }
            if (stale.isEmpty()) return
            stale.forEach { notificationManager.cancel(it.tag, it.id) }
            postMissedSummary(context, notificationManager, kept.sumOf(::missedCount))
        }

        /**
         * The group summary. Without one, unlocking after a long block drops the whole held backlog
         * into the shade as N loose notifications -- the individual entries already carry
         * MISSED_GROUP_KEY, but a group with no summary is not reliably collapsed. One line saying
         * how many there are keeps a catch-up from reading as an explosion.
         */
        private fun postMissedSummary(context: Context, notificationManager: NotificationManager, count: Int) {
            if (count == 0) {
                notificationManager.cancel(MISSED_SUMMARY_ID)
                return
            }
            val summary = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_monolith_mark)
                .setColor(ContextCompat.getColor(context, R.color.monolith_amber))
                .setContentTitle(context.getString(R.string.missed_notification_summary, count))
                .setGroup(MISSED_GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            notificationManager.notify(MISSED_SUMMARY_ID, summary)
        }
    }
}
