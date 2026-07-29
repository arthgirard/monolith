package com.monolith.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.monolith.app.domain.model.BlockState
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.BlockRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cancels notifications from blocked apps while Block Mode is enforcing, so a blocked app can't
 * reach the user through the notification shade either. Mirrors [AppBlockAccessibilityService]'s
 * state-tracking pattern; the two run independently since a device can enable one without the
 * other.
 */
@AndroidEntryPoint
class NotificationBlockListenerService : NotificationListenerService() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var appRepository: AppRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var blockState: BlockState = BlockState()
    @Volatile private var blockedPackages: Set<String> = emptySet()

    override fun onListenerConnected() {
        super.onListenerConnected()
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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (!blockState.isEnforcing(System.currentTimeMillis())) return
        if (sbn.packageName !in blockedPackages) return
        cancelNotification(sbn.key)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
