package com.monolith.app.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import com.monolith.app.domain.model.earliestNextFire
import com.monolith.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps exactly one OS alarm armed: the soonest fire across every enabled rule. Re-arming after
 * each fire (rather than registering one alarm per rule) means the OS only ever holds a single
 * wakeup for Monolith, and a rule edit can't leave an orphaned alarm behind -- there is only one
 * to replace.
 */
@Singleton
class ScheduleAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleRepository: ScheduleRepository,
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    /** Cancels any pending alarm and arms the next one, or leaves none armed if nothing is due. */
    // Lint looks for SCHEDULE_EXACT_ALARM specifically; the manifest declares USE_EXACT_ALARM,
    // which grants the same capability at install time, and [canScheduleExact] falls back to an
    // inexact alarm if the permission ever isn't held.
    @SuppressLint("MissingPermission")
    suspend fun rearm() {
        val next = scheduleRepository.observeSchedules().first().earliestNextFire(ZonedDateTime.now())
        val pendingIntent = pendingIntent()
        alarmManager.cancel(pendingIntent)
        if (next == null) return

        val triggerAtMillis = next.toInstant().toEpochMilli()
        // Doze defers ordinary alarms for as long as it likes, which for a blocker that's supposed
        // to come on at 22:00 is the difference between working and not. The allowWhileIdle
        // variants are the ones that punch through it.
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            // USE_EXACT_ALARM is granted at install, so this branch means an OEM or a future
            // policy took it away. Firing late beats throwing SecurityException and firing never.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * One request code for the lifetime of the app, so [PendingIntent.FLAG_UPDATE_CURRENT] makes
     * every re-arm replace the previous alarm instead of stacking a second one beside it.
     */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ScheduleAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST_CODE = 2001
    }
}
