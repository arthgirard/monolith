package com.monolith.app

import android.app.Application
import android.content.Intent
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.service.EnforcementForegroundService
import com.monolith.app.service.ScheduleTrigger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MonolithApplication : Application() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var scheduleTrigger: ScheduleTrigger

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Reconciled here, not just at the toggle call sites, so a process that gets killed and
        // later revived (by the OS, or by the accessibility service needing to deliver an event)
        // while Monolith was still active starts the foreground service right back up instead of
        // running unprotected until the next explicit toggle.
        blockRepository.observeBlockState()
            .map { it.isActive }
            .distinctUntilChanged()
            .onEach { isActive ->
                if (!isActive) return@onEach
                val intent = Intent(this, EnforcementForegroundService::class.java)
                // Android 12+ refuses a foreground-service start from the background unless the
                // app has an exemption. The paths that matter here do have one -- an exact alarm
                // firing, a BOOT_COMPLETED receiver -- but a timezone change or a plain
                // background process revival doesn't, and an uncaught
                // ForegroundServiceStartNotAllowedException there would take the process down
                // during the very activation it was meant to protect. Enforcement itself doesn't
                // depend on this service; it only keeps the process warm, and the next reconcile
                // from the foreground picks it back up.
                runCatching { startForegroundService(intent) }
            }
            .launchIn(appScope)

        // Belt and braces for the schedule alarm. ScheduleRearmReceiver handles the normal cases,
        // but OEM battery managers are known to swallow boot broadcasts, and a fresh install has
        // never received one at all. Both calls are idempotent -- the last-handled watermark stops
        // a fire being replayed, and re-arming replaces the pending alarm rather than stacking.
        appScope.launch { scheduleTrigger.reconcile() }
    }
}
