package com.bellnote

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.bellnote.service.NtfyFetcher
import com.bellnote.service.NtfyPollScheduler
import com.bellnote.service.ReconcileScheduler
import com.bellnote.service.ScreenshotWatcher
import com.bellnote.util.CrashLog
import com.bellnote.util.ThemeManager

class App : Application() {

    private var startedActivities = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val applyIconRunnable = Runnable { ThemeManager.applyPendingLauncherIcon(this) }

    override fun onCreate() {
        super.onCreate()
        // 深浅色：跟随系统 / 浅色 / 深色，设置里可改
        ThemeManager.applyNightMode(this)
        ThemeManager.applyLauncherIcon(this)
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
        // 主动拉取排班：不再依赖「通知使用权」，每 15 分钟问一次 ntfy 服务器
        NtfyPollScheduler.sync(this)

        // 换桌面图标会禁用旧的 activity-alias，正在用的任务会被系统收掉；
        // 所以等所有页面都退到后台再换，用户看不到这一下。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                // 页面切回来就说明没真的退到后台（recreate 也会 stop 一下），撤回待执行的换图标
                mainHandler.removeCallbacks(applyIconRunnable)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    // 等 800ms：如果是 recreate / 页面切换，新页面会在这之前 start，换图标就被撤回
                    mainHandler.removeCallbacks(applyIconRunnable)
                    mainHandler.postDelayed(applyIconRunnable, 800L)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        const val CHANNEL_EVENTS = "ntfy_calendar_events"

        /** 冷启动开屏只播一次；Activity recreate（换主题）不会再盖一层。 */
        @Volatile
        var splashPending = true
    }
}
