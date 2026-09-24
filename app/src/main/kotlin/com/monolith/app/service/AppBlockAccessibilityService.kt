package com.monolith.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.SystemPackages
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.AppUnlockRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.usecase.RecordBlockHitUseCase
import com.monolith.app.ui.bypass.BlockOverlayActivity
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
 * enforcing and the foreground package is on the blocked list. Settings isn't hard-blocked:
 * a user determined to disable the accessibility service could just uninstall Monolith from
 * the home screen instead, so hard-blocking it stops nothing while catching false positives
 * like system dialogs (biometric/PIN confirmation, location prompts, ...) that happen to be
 * hosted inside the Settings package. Monolith's own UI is deliberately left reachable, since
 * the emergency bypass button lives there.
 */
@AndroidEntryPoint
class AppBlockAccessibilityService : AccessibilityService() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var appRepository: AppRepository
    @Inject lateinit var appUnlockRepository: AppUnlockRepository
    @Inject lateinit var overlayGuard: BlockOverlayGuard
    @Inject lateinit var recordBlockHit: RecordBlockHitUseCase
    @Inject lateinit var statusNotifier: StatusNotifier

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var blockState: BlockState = BlockState()
    @Volatile private var blockedPackages: Set<String> = emptySet()
    @Volatile private var unlockedPackages: Map<String, Long> = emptyMap()

    /** Last time the missing-overlay-permission notice was posted, to keep it from repeating
     *  on every single blocked app while the permission stays revoked. */
    @Volatile private var overlayPermissionNotifiedAt: Long = 0L

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
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foregroundPackage = event.packageName?.toString() ?: return
        blockIfNeeded(foregroundPackage, event.className?.toString())
    }

    private fun blockIfNeeded(foregroundPackage: String, foregroundClass: String?) {
        if (foregroundPackage == packageName) return

        val now = System.currentTimeMillis()
        if (!blockState.isEnforcing(now)) return

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
            if (foregroundClass in TRANSIENT_SETTINGS_DIALOG_CLASSES) return
        }

        if (foregroundPackage !in blockedPackages) return
        if ((unlockedPackages[foregroundPackage] ?: 0L) > now) return

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
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "MonolithBlock"
        private const val OVERLAY_PERMISSION_NOTICE_INTERVAL_MILLIS = 60L * 60 * 1000

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
