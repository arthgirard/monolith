package com.monolith.app

import android.app.Application
import android.content.Intent
import android.os.Build
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.service.EnforcementForegroundService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltAndroidApp
class MonolithApplication : Application() {

    @Inject lateinit var blockRepository: BlockRepository

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            .launchIn(appScope)
    }
}
