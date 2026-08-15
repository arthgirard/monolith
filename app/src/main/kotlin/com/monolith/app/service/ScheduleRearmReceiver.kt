package com.monolith.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores the schedule alarm after any event that invalidates it. Alarms don't survive a reboot
 * or a package replace, and a clock or timezone change silently moves every rule's real instant --
 * without this, a schedule would quietly stop firing and the user would never be told.
 *
 * Kept separate from [BootCompletedReceiver], whose single job is nagging about a disabled
 * accessibility service.
 */
@AndroidEntryPoint
class ScheduleRearmReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduleTrigger: ScheduleTrigger

    override fun onReceive(context: Context, intent: Intent) {
        // All four are protected broadcasts only the system can send, and the allowlist keeps it
        // that way even though the receiver has to be exported to hear them.
        if (intent.action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Catch-up happens inside reconcile: a reboot straddling a scheduled fire should
                // still switch Monolith on, or powering the phone off at 21:59 would defeat a
                // 22:00 rule.
                scheduleTrigger.reconcile()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
