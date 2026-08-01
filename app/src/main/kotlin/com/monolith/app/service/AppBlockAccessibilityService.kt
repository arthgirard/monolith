package com.monolith.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.model.SystemPackages
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.ui.bypass.BlockOverlayActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Watches foreground-app changes and throws up the block overlay whenever Monolith is
 * enforcing and the foreground package is on the blocked list. Settings is hard-blocked too,
 * so a user can't disable this service to escape Monolith: the NFC tag or the timed
 * emergency bypass are the only ways out. Monolith's own UI is deliberately left reachable,
 * since the emergency bypass button lives there.
 */
@AndroidEntryPoint
class AppBlockAccessibilityService : AccessibilityService() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var appRepository: AppRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var blockState: BlockState = BlockState()
    @Volatile private var blockedPackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.launch {
            combine(
                blockRepository.observeBlockState(),
                appRepository.observeBlockedPackages(),
            ) { state, packages -> state to packages }
                .collect { (state, packages) ->
                    blockState = state
                    blockedPackages = packages
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foregroundPackage = event.packageName?.toString() ?: return
        if (foregroundPackage == packageName) return

        val now = System.currentTimeMillis()
        if (!blockState.isEnforcing(now)) return

        val foregroundClass = event.className?.toString()
        // Some system dialogs live inside the Settings package but aren't a user navigating to
        // Settings, e.g. Android's location-accuracy resolution dialog: any app requesting
        // high-accuracy location (Maps included) can trigger it, and its window reports
        // com.android.settings just like the real Settings app would. Without this carve-out
        // that dialog gets hard-blocked, which throws the overlay over a non-blocked app that
        // merely asked for location and stalls it until the app is force-killed and reopened.
        if (foregroundPackage == SystemPackages.SETTINGS && foregroundClass in TRANSIENT_SETTINGS_DIALOG_CLASSES) {
            return
        }

        val shouldBlock = foregroundPackage in blockedPackages || foregroundPackage in HARD_BLOCKED_PACKAGES
        if (!shouldBlock) return

        Log.d(LOG_TAG, "blocking foreground=$foregroundPackage class=$foregroundClass")

        val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
        private val HARD_BLOCKED_PACKAGES = setOf(SystemPackages.SETTINGS)

        /**
         * AOSP's location-accuracy resolution dialog (see [SystemPackages.SETTINGS]'s
         * `location.LocationAccuracyDialogActivity`), the known case of a transient helper
         * window that reports under the Settings package without being a real Settings visit.
         * Add more class names here if other false positives like this turn up.
         */
        private val TRANSIENT_SETTINGS_DIALOG_CLASSES = setOf(
            "com.android.settings.location.LocationAccuracyDialogActivity",
        )
    }
}
