package com.monolith.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.util.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The accessibility service itself resumes automatically once Android finishes booting, since
 * the OS re-binds any service the user enabled in Accessibility settings. This receiver exists
 * for the case that matters: Monolith was active before reboot but the service somehow isn't
 * enabled (OEM battery managers love disabling accessibility services), so nudge the user instead
 * of silently leaving blocking off.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var statusNotifier: StatusNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val state = blockRepository.observeBlockState().first()
                val serviceEnabled = PermissionUtils.isAccessibilityServiceEnabled(
                    context,
                    AppBlockAccessibilityService::class.java,
                )
                if (state.isActive && !serviceEnabled) {
                    statusNotifier.notifyAccessibilityServiceOff()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
