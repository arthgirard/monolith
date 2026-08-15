package com.monolith.app.service

import com.monolith.app.domain.usecase.ApplyPendingScheduleUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point every schedule trigger goes through -- the alarm landing, a post-boot
 * catch-up, a plain app start. Keeping the sequence in one place means a new trigger can't ship
 * with half of it: apply what's owed, say so if anything changed, then re-arm for next time.
 */
@Singleton
class ScheduleTrigger @Inject constructor(
    private val applyPendingSchedule: ApplyPendingScheduleUseCase,
    private val alarmScheduler: ScheduleAlarmScheduler,
    private val notifier: ScheduleNotifier,
) {
    suspend fun reconcile() {
        // Only notifies on a real off-to-on transition. An occurrence landing on an already-running
        // session tells the user nothing they don't know, and would be pure noise.
        if (applyPendingSchedule()) notifier.notifyActivated()
        // A one-shot alarm is spent once it fires, so this is what makes a schedule recurring.
        alarmScheduler.rearm()
    }
}
