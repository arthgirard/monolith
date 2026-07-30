package com.monolith.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.AppInfo
import com.monolith.app.domain.model.SystemPackages
import com.monolith.app.domain.repository.AppRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: MonolithPreferences,
) : AppRepository {

    override suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    override fun observeBlockedPackages(): Flow<Set<String>> = preferences.blockedPackages

    override suspend fun setBlockedPackages(packages: Set<String>) {
        preferences.setBlockedPackages(packages)
    }

    override suspend fun getEssentialPackages(): Set<String> = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        val dialerPackage = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        val smsPackage = Telephony.Sms.getDefaultSmsPackage(context)
        val clockPackage = resolveActivityPackage(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS))
        val cameraPackage = resolveActivityPackage(pm, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        val calculatorPackage = resolveAppCategoryPackage(pm, Intent.CATEGORY_APP_CALCULATOR)
        val contactsPackage = resolveAppCategoryPackage(pm, Intent.CATEGORY_APP_CONTACTS)
        val walletPackage = resolveWalletPackage(pm)

        setOfNotNull(
            dialerPackage,
            smsPackage,
            clockPackage,
            cameraPackage,
            calculatorPackage,
            contactsPackage,
            walletPackage,
            SystemPackages.SETTINGS,
        )
    }

    private fun resolveActivityPackage(pm: PackageManager, intent: Intent): String? =
        pm.resolveActivity(intent, 0)?.activityInfo?.packageName

    private fun resolveAppCategoryPackage(pm: PackageManager, category: String): String? =
        resolveActivityPackage(pm, Intent(Intent.ACTION_MAIN).addCategory(category))

    /**
     * No public API exists for "which app holds the Wallet role": RoleManager.getRoleHolders is
     * restricted to system callers, and there's no ACTION_APP_WALLET-style resolvable intent
     * either. Best-effort only: checks for the most common wallet packages actually installed.
     * A device running a wallet app outside this list won't be recognized.
     */
    private fun resolveWalletPackage(pm: PackageManager): String? =
        COMMON_WALLET_PACKAGES.firstOrNull { isPackageInstalled(pm, it) }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean =
        runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess

    companion object {
        private val COMMON_WALLET_PACKAGES = listOf(
            "com.google.android.apps.walletnfcrel",
            "com.samsung.android.spay",
        )
    }
}
