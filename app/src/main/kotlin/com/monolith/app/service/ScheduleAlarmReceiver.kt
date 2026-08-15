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
 * Target of the single alarm [ScheduleAlarmScheduler] keeps armed. Unexported: it is only ever
 * reached through Monolith's own PendingIntent, and letting another app broadcast to it would let
 * that app force blocking on -- which, given that only the tag can lift it, is not a small thing.
 */
@AndroidEntryPoint
class ScheduleAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduleTrigger: ScheduleTrigger

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                scheduleTrigger.reconcile()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
