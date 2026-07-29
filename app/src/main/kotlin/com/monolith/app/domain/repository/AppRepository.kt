package com.monolith.app.domain.repository

import com.monolith.app.domain.model.AppInfo
import kotlinx.coroutines.flow.Flow

interface AppRepository {
    /** Snapshot of launchable, non-Monolith apps installed on the device. */
    suspend fun getInstalledApps(): List<AppInfo>

    fun observeBlockedPackages(): Flow<Set<String>>

    suspend fun setBlockedPackages(packages: Set<String>)

    /** The device's current default SMS, dialer, and clock/alarm app packages, where resolvable. */
    suspend fun getEssentialPackages(): Set<String>
}
