package com.monolith.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.monolith.app.R
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.SystemPackages
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.StrictnessRepository
import com.monolith.app.domain.usecase.RecordBlockHitUseCase
import com.monolith.app.ui.bypass.BlockOverlayActivity
import com.monolith.app.ui.guard.TamperGuardActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Watches foreground-app changes and throws up the block overlay whenever Monolith is
 * enforcing and the foreground package is on the blocked list. Settings as a whole isn't
 * hard-blocked: that would catch system dialogs (biometric/PIN confirmation, location prompts,
 * ...) that happen to be hosted inside the Settings package. Instead only the pages that would
 * take Monolith apart are shut while it's active (see [TamperGuard]).
 * Monolith's own UI is deliberately left reachable, since the emergency bypass button lives there.
 */
@AndroidEntryPoint
class AppBlockAccessibilityService : AccessibilityService() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var appRepository: AppRepository
    @Inject lateinit var appUnlockRepository: AppUnlockRepository
    @Inject lateinit var overlayGuard: BlockOverlayGuard
    @Inject lateinit var recordBlockHit: RecordBlockHitUseCase
    @Inject lateinit var statusNotifier: StatusNotifier
    @Inject lateinit var strictnessRepository: StrictnessRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var blockState: BlockState = BlockState()
    @Volatile private var blockedPackages: Set<String> = emptySet()
    @Volatile private var unlockedPackages: Map<String, Long> = emptyMap()

    /** Last time the missing-overlay-permission notice was posted, to keep it from repeating
     *  on every single blocked app while the permission stays revoked. */
    @Volatile private var overlayPermissionNotifiedAt: Long = 0L

    /** The app on screen that was let through, whose restored notifications go once it's left. */
    @Volatile private var visitedPackage: String? = null

    @Volatile private var uninstallGuardOn: Boolean = true

    /** Main-thread only, like everything below that touches it. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appLabel: String by lazy { getString(R.string.app_name) }
    private var watchingContent = false
    private var guardShownAt = 0L
    private val guardCheck = Runnable { guardedScreenShown()?.let(::showGuard) }
    private val guardFollowUp = Runnable { guardedScreenShown()?.let(::showGuard) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.launch {
            combine(
                blockRepository.observeBlockState(),
                appRepository.observeBlockedPackages(),
                appUnlockRepository.observeUnlockedPackages(),
            ) { state, packages, unlocks -> Triple(state, packages, unlocks) }
                .collect { (state, packages, unlocks) ->
                    val now = System.currentTimeMillis()
                    val pauseCutShort = pauseCutShort(blockState, unlockedPackages, state, unlocks, now)
                    blockState = state
                    blockedPackages = packages
                    unlockedPackages = unlocks
                    if (pauseCutShort) withContext(Dispatchers.Main) { blockAppInFront() }
                }
        }
        serviceScope.launch {
            strictnessRepository.observeUninstallGuard().collect { uninstallGuardOn = it }
        }
    }

    /**
     * A pause was ended before it ran out (see ResumeBlockingUseCase). Natural expiry writes
     * nothing, so it never shows up here. Monolith being switched on doesn't count either: it has
     * to have been on both before and after, or a schedule firing would snatch the app in front.
     */
    private fun pauseCutShort(
        before: BlockState,
        unlocksBefore: Map<String, Long>,
        after: BlockState,
        unlocksAfter: Map<String, Long>,
        now: Long,
    ): Boolean {
        if (!before.isActive || !after.isActive) return false
        if (before.isBypassActive(now) && !after.isBypassActive(now)) return true
        return unlocksBefore.any { (pkg, expiresAt) -> expiresAt > now && (unlocksAfter[pkg] ?: 0L) <= now }
    }

    /**
     * Resuming from the shade changes no window, so no event arrives for the app already on
     * screen and it would stay usable until the user left it. The shade itself is likely still
     * open over it, which is why this looks for the topmost application window rather than the
     * active one. Settings is left alone here: without the window's class there is no telling a
     * transient credential prompt from the real thing.
     */
    private fun blockAppInFront() {
        val foregroundPackage = runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.root?.packageName?.toString()
        }.getOrNull() ?: return
        if (foregroundPackage == SystemPackages.SETTINGS) return
        blockIfNeeded(foregroundPackage, foregroundClass = null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (TamperGuard.watches(eventPackage)) scheduleGuardCheck()
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Unit
            else -> return
        }
        watchContentFor(eventPackage)
        if (guardIfNeeded(eventPackage)) {
            trackVisit(eventPackage, blocked = true)
            return
        }
        val blocked = blockIfNeeded(eventPackage, event.className?.toString())
        trackVisit(eventPackage, blocked)
    }

    /**
     * The whole while Monolith is on, bypass included: a force stop during a bypass would still
     * leave the accessibility service switched off once it ends. The uninstall prompt is the one
     * screen that also depends on the user's setting, and [TamperGuard] applies that itself.
     */
    private fun guardArmed(): Boolean = blockState.isActive

    /**
     * Checked on arrival, and again a moment later: a Settings page often fills in its title after
     * the window has opened, the installer's prompt can do the same, and the shade's Active apps
     * panel loads its list after it appears. Returns whether the guard took over the screen.
     */
    private fun guardIfNeeded(foregroundPackage: String): Boolean {
        if (!guardArmed() || !TamperGuard.watches(foregroundPackage)) return false
        val caught = guardedScreenShown()
        if (caught != null) {
            showGuard(caught)
            return true
        }
        scheduleGuardCheck()
        return false
    }

    /**
     * Settings moves between its pages without opening a new window, so window changes alone
     * would miss App info reached from inside Settings. Content changes fire constantly in every
     * app, though, so they're only subscribed to while a watched package is on screen.
     *
     * An event from another package doesn't mean Settings has gone: the keyboard sends its own
     * window changes while Settings stays in front (Gboard does, on a Pixel 10), and switching off
     * then let Settings' Compose app list open App info unseen. So switching off waits until no
     * watched app window is left on screen.
     */
    private fun watchContentFor(eventPackage: String) {
        // The shade over Settings says nothing about what's under it, and Monolith's own windows
        // include the guard's flash.
        if (eventPackage == SystemPackages.SYSTEM_UI || eventPackage == packageName) return
        val watch = guardArmed() && (TamperGuard.watches(eventPackage) || watchedAppWindowShown())
        if (watch == watchingContent) return
        val info = serviceInfo ?: return
        info.eventTypes = if (watch) {
            info.eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            info.eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED.inv()
        }
        serviceInfo = info
        watchingContent = watch
        if (!watch) cancelGuardChecks()
    }

    private fun watchedAppWindowShown(): Boolean {
        val windows = runCatching { windows }.getOrNull() ?: return false
        return windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                runCatching { window.root?.packageName?.toString() }.getOrNull()
                    ?.let { it != SystemPackages.SYSTEM_UI && TamperGuard.watches(it) } == true
        }
    }

    /**
     * Coalesces a burst of content changes into one look once the page has settled, plus a later
     * one for the screens that never send content changes here (the shade isn't subscribed to).
     */
    private fun scheduleGuardCheck() {
        cancelGuardChecks()
        mainHandler.postDelayed(guardCheck, GUARD_CHECK_DELAY_MILLIS)
        mainHandler.postDelayed(guardFollowUp, GUARD_FOLLOW_UP_DELAY_MILLIS)
    }

    private fun cancelGuardChecks() {
        mainHandler.removeCallbacks(guardCheck)
        mainHandler.removeCallbacks(guardFollowUp)
    }

    /** The package of the guarded screen on display, or null when there is none. */
    private fun guardedScreenShown(): String? {
        if (!guardArmed()) return null
        val windows = runCatching { windows }.getOrNull() ?: return null
        // System windows for the shade's Active apps panel; everything else is an app window.
        val candidates = windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM
        }
        for (window in candidates) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val windowPackage = root.packageName?.toString() ?: continue
            if (!TamperGuard.watches(windowPackage)) continue
            val title = window.title?.toString()
            val guarded = runCatching {
                TamperGuard.isGuardedScreen(windowPackage, appLabel, title, guardNodes(root), uninstallGuardOn)
            }.getOrDefault(false)
            if (guarded) return windowPackage
            // Diagnostic only, as for the Settings classes below: when a skin's App info page
            // slips through, this is what shows which title it uses instead. Not for the shade,
            // which is checked far too often for the line to say anything.
            if (windowPackage != SystemPackages.SYSTEM_UI) {
                Log.d(LOG_TAG, "guard passed $windowPackage window title=$title")
            }
        }
        return null
    }

    /** The window's nodes, breadth first and capped, so a long list can't stall the check. */
    private fun guardNodes(root: AccessibilityNodeInfo): Sequence<GuardNode> = sequence {
        // Each node queued with whether something above it was clickable.
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>().apply { add(root to false) }
        var visited = 0
        while (queue.isNotEmpty() && visited < GUARD_NODE_LIMIT) {
            val (node, parentClickable) = queue.removeFirst()
            visited++
            val insideClickable = parentClickable || node.isClickable
            yield(
                GuardNode(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    viewId = node.viewIdResourceName,
                    isCheckable = node.isCheckable,
                    insideClickable = insideClickable,
                ),
            )
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to insideClickable) }
        }
    }

    private fun showGuard(caughtPackage: String) {
        cancelGuardChecks()
        val now = System.currentTimeMillis()
        // One flash per attempt: the page underneath keeps reporting changes until the flash
        // covers it. Shorter than the flash itself, so coming straight back via Recents is caught.
        if (now - guardShownAt < GUARD_REPEAT_WINDOW_MILLIS) return
        guardShownAt = now
        Log.d(LOG_TAG, "tamper guard caught a screen about Monolith in $caughtPackage")
        if (caughtPackage == SystemPackages.SYSTEM_UI) {
            // The panel and the shade sit above every app window, the flash included, so they
            // have to be put away first or the Stop button stays within reach on top of it.
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
        } else {
            // Covered at once, as for a blocked app: the uninstall prompt's OK button and the Force
            // stop button are each one tap away.
            overlayGuard.show()
        }
        startActivity(
            Intent(this, TamperGuardActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            ),
        )
    }

    /**
     * Opening a chat normally dismisses its notification, but a stand-in restored by
     * NotificationBlockListenerService is out of the source app's reach. So once the user has
     * been in an app and moves on, its stand-ins are taken as read and cleared. On leaving rather
     * than arriving: unlocking one app posts its stand-ins and drops the user straight into it,
     * and they'd be gone before they could be used. The shade doesn't count as leaving, since
     * tapping a stand-in means pulling it down first. A blocked arrival isn't a visit.
     */
    private fun trackVisit(foregroundPackage: String, blocked: Boolean) {
        if (foregroundPackage == SystemPackages.SYSTEM_UI) return
        val previous = visitedPackage
        if (foregroundPackage == previous) return
        visitedPackage = foregroundPackage.takeUnless { blocked }
        if (previous != null) NotificationBlockListenerService.clearRestored(this, previous)
    }

    /** Returns whether [foregroundPackage] was blocked. */
    private fun blockIfNeeded(foregroundPackage: String, foregroundClass: String?): Boolean {
        if (foregroundPackage == packageName) return false

        val now = System.currentTimeMillis()
        if (!blockState.isEnforcing(now)) return false

        // Some system dialogs live inside the Settings package but aren't a user navigating to
        // Settings, e.g. Android's location-accuracy resolution dialog or a biometric/PIN
        // confirmation prompt (Microsoft Authenticator's "verify it's you" step included): any
        // app can trigger these, and their window reports com.android.settings just like the
        // real Settings app would. If the user has Settings on their blocked list, this carve-out
        // stops that collateral blocking from throwing the overlay over an unrelated, unblocked
        // app that merely triggered one of these system dialogs.
        if (foregroundPackage == SystemPackages.SETTINGS) {
            if (foregroundClass !in TRANSIENT_SETTINGS_DIALOG_CLASSES) {
                // Diagnostic only: lets a real false positive be confirmed and its exact class
                // added above, instead of guessing at OEM-specific confirm-credential activities.
                Log.d(LOG_TAG, "settings window class=$foregroundClass (not in exemption list)")
            }
            if (foregroundClass in TRANSIENT_SETTINGS_DIALOG_CLASSES) return false
        }

        if (foregroundPackage !in blockedPackages) return false
        if ((unlockedPackages[foregroundPackage] ?: 0L) > now) return false

        Log.d(LOG_TAG, "blocking foreground=$foregroundPackage class=$foregroundClass")

        // Recorded here rather than in the overlay: this is the moment the wall was actually met.
        // The overlay can be retargeted, recreated or never reach onResume at all, so counting
        // from its side would count something else. RecordBlockHitUseCase dedupes the repeat
        // events one reach produces.
        serviceScope.launch { recordBlockHit(foregroundPackage) }

        // Paint the opaque overlay before anything else: it's a pre-inflated window with no
        // Activity launch in the critical path, so it covers the blocked app's already-drawn
        // frame in this same tick. BlockOverlayActivity below still cold-starts its Hilt/Compose
        // graph, but that now happens behind this cover instead of in full view.
        val overlayShown = overlayGuard.show()

        // GLOBAL_ACTION_HOME is dispatched to the system server asynchronously, so it can land
        // *after* BlockOverlayActivity's own task has started, bumping that Activity behind the
        // home launcher and leaving the block screen never shown. Only worth the race when the
        // pixel-cover above couldn't paint (e.g. overlay permission revoked) and something needs
        // to hide the blocked app immediately regardless.
        if (!overlayShown) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            // Without this the degradation is invisible: blocked apps just bounce to the home
            // screen with no block screen and no explanation, which reads as Monolith breaking
            // rather than as a permission it needs having been taken away.
            if (now - overlayPermissionNotifiedAt > OVERLAY_PERMISSION_NOTICE_INTERVAL_MILLIS) {
                overlayPermissionNotifiedAt = now
                statusNotifier.notifyOverlayPermissionMissing()
            }
        }

        val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, foregroundPackage)
        }
        startActivity(overlayIntent)
        return true
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cancelGuardChecks()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "MonolithBlock"
        private const val OVERLAY_PERMISSION_NOTICE_INTERVAL_MILLIS = 60L * 60 * 1000
        private const val GUARD_CHECK_DELAY_MILLIS = 150L
        private const val GUARD_FOLLOW_UP_DELAY_MILLIS = 600L
        private const val GUARD_REPEAT_WINDOW_MILLIS = 1_000L
        private const val GUARD_NODE_LIMIT = 400

        /**
         * Transient helper windows that report under the Settings package without being a real
         * Settings visit -- AOSP's location-accuracy resolution dialog, plus the device-credential
         * confirmation screen (`KeyguardManager.createConfirmDeviceCredentialIntent()`) that any
         * app can trigger for a "verify it's you" step, Microsoft Authenticator included. The
         * confirm-credential entries are stock AOSP class names, unverified on a real device --
         * OEM skins (Samsung, Xiaomi, ...) commonly ship their own class here instead. Watch
         * logcat for "settings window class=... (not in exemption list)" from this service to
         * catch and add whatever a given device actually reports.
         */
        private val TRANSIENT_SETTINGS_DIALOG_CLASSES = setOf(
            "com.android.settings.location.LocationAccuracyDialogActivity",
            "com.android.settings.password.ConfirmDeviceCredentialActivity",
            "com.android.settings.password.ConfirmLockPattern",
            "com.android.settings.password.ConfirmLockPassword",
        )
    }
}
