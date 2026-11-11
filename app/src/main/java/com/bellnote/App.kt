package com.bellnote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.bellnote.service.NtfyFetcher
import com.bellnote.service.ReconcileScheduler
import com.bellnote.service.ScreenshotWatcher
import com.bellnote.util.CrashLog
import com.bellnote.util.ThemeManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 深浅色：跟随系统 / 浅色 / 深色，设置里可改
        ThemeManager.applyNightMode(this)
        // 崩了要留痕：realme 后台被杀是不留日志的，至少能分清「崩」和「被杀」
        CrashLog.install(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_EVENTS,
                "ntfy 待办日历",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "ntfy 通知写入日历的结果反馈、定时对账差异"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        // 每次进程起来重排一次定时对账；闹钟跑完会自己续下一次
        ReconcileScheduler.sync(this)
        // 有相册权限就顺手挂上截图监听（没权限时它在内部直接返回）
        ScreenshotWatcher.ensureStarted(this)
        // 顺手补查一次 ntfy：ntfy App 没弹通知/被系统冻结时，消息还能从这里补回来
        NtfyFetcher.catchUpInBackground(this)
    }

    companion object {
        const val CHANNEL_EVENTS = "ntfy_calendar_events"
    }
}
