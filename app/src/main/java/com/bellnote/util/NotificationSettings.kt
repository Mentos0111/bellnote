package com.bellnote.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 系统的「通知」设置页（App 级 / 通道级）。
 *
 * 为什么需要它：Android 里「通知横幅（弹窗）」是用户侧开关，App 自己改不了
 * （ColorOS 上还多一层 App 级的「重要程度 / 横幅通知」，默认可能不是高）。
 * 所以只能一键把用户送到对应页面，让他把开关打开。
 */
object NotificationSettings {

    /** 本 App 的通知设置页（Android 8+ 是「通知」页，再低是应用详情）。 */
    fun openAppNotificationSettings(context: Context): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }
        return SystemPages.start(context, intent) || SystemPages.openAppDetails(context)
    }

    /** 某个通知通道的设置页（能直接看到「横幅 / 重要程度」）。 */
    fun openChannelSettings(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return openAppNotificationSettings(context)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        return SystemPages.start(context, intent) || openAppNotificationSettings(context)
    }
}
