package com.monolith.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.monolith.app.domain.usecase.ResumeBlockingUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Target of the enforcement notification's "Resume blocking" action, so a pause can be ended from
 * the shade without opening the app. Unexported for the same reason as [ScheduleAlarmReceiver]:
 * only Monolith's own PendingIntent should be able to move enforcement state.
 */
@AndroidEntryPoint
class ResumeBlockingReceiver : BroadcastReceiver() {

    @Inject lateinit var resumeBlocking: ResumeBlockingUseCase

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                resumeBlocking()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
