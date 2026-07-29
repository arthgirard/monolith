package com.monolith.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
 * Watches foreground-app changes and throws up the block overlay whenever Block Mode is
 * enforcing and the foreground package is on the blocked list. Settings is hard-blocked too,
 * so a user can't disable this service to escape Block Mode — the NFC tag or the timed
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

        val shouldBlock = foregroundPackage in blockedPackages || foregroundPackage in HARD_BLOCKED_PACKAGES
        if (!shouldBlock) return

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
        private val HARD_BLOCKED_PACKAGES = setOf(SystemPackages.SETTINGS)
    }
}
