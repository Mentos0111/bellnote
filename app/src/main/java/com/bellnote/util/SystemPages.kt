package com.bellnote.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/** 各种系统设置页跳转，realme / ColorOS 的组件名会先试，失败再退回通用应用详情页。 */
object SystemPages {

    private const val TAG = "SystemPages"

    fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "跳转失败：$intent", e)
        false
    }

    fun openNotificationAccess(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun openAppDetails(context: Context): Boolean =
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        )

    fun openAutostart(context: Context): Boolean {
        val candidates = listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")
        )
        for (component in candidates) {
            if (start(context, Intent().setComponent(component))) return true
        }
        return openAppDetails(context)
    }

    fun openBattery(context: Context): Boolean {
        val candidates = listOf(
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$HighPowerApplicationsActivity")
        )
        for (component in candidates) {
            if (start(context, Intent().setComponent(component))) return true
        }
        if (start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
        return openAppDetails(context)
    }

    fun openBatteryOptimizationRequest(context: Context): Boolean = try {
        start(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", context.packageName, null))
        )
    } catch (e: Exception) {
        openBattery(context)
    }
}
