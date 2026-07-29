package com.monolith.app.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat

object PermissionUtils {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponent = "${context.packageName}/${serviceClass.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun allPermissionsGranted(context: Context, accessibilityServiceClass: Class<*>): Boolean =
        hasUsageAccess(context) &&
            hasOverlayPermission(context) &&
            isAccessibilityServiceEnabled(context, accessibilityServiceClass) &&
            hasNotificationAccess(context)
}
