package com.bellnote.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bellnote.data.Prefs

/**
 * ntfy 的后台接收方式，统一在这里落地。
 *
 * 三种模式（[Prefs.ntfyBackgroundMode]）：
 * - off     ：后台完全不做，打开 App 时补查一次
 * - alarm   ：精确闹钟每 10 分钟唤醒拉一次；没有精确闹钟权限时退回 15 分钟的 JobScheduler
 * - service ：前台服务常驻，60 秒一轮（通知栏会有一条低优先级常驻通知）
 *
 * 为什么要这层：国产 ROM（ColorOS / MIUI 等）会把 JobScheduler 的 15 分钟周期任务
 * 无限期延后甚至冻结，表现为「必须打开 App 才补上」。精确闹钟和前台服务是仅有的
 * 两条还能按时跑的路。
 */
object NtfyBackground {

    private const val TAG = "NtfyBackground"

    /** 精确闹钟的周期；10 分钟是「够快」和「别太费电」之间的折中。 */
    const val ALARM_INTERVAL_MS = 10 * 60 * 1000L
    private const val ALARM_REQUEST_CODE = 4301

    /** 没有精确闹钟权限时的退路，沿用原来的 15 分钟 Job。 */
    private const val FALLBACK_JOB_ID = 4201
    private const val FALLBACK_INTERVAL_MS = 15 * 60 * 1000L

    /** App 启动、改设置、开机后都调这里，保证「当前模式」和「实际在跑的东西」一致。 */
    fun sync(context: Context) {
        val app = context.applicationContext
        val enabled = Prefs.ntfyEnabled(app) && Prefs.ntfyTopic(app).isNotBlank()

        if (!enabled) {
            stopService(app)
            cancelAlarm(app)
            cancelFallbackJob(app)
            Log.i(TAG, "ntfy 未启用，后台全部停掉")
            return
        }

        when (Prefs.ntfyBackgroundMode(app)) {
            Prefs.BG_SERVICE -> {
                cancelAlarm(app)
                cancelFallbackJob(app)
                startService(app)
            }

            Prefs.BG_ALARM -> {
                stopService(app)
                if (canUseExactAlarm(app)) {
                    cancelFallbackJob(app)
                    scheduleAlarm(app)
                } else {
                    cancelAlarm(app)
                    scheduleFallbackJob(app)
                }
            }

            else -> { // BG_OFF
                stopService(app)
                cancelAlarm(app)
                cancelFallbackJob(app)
            }
        }
    }

    /** 兼容旧调用点（原来叫 reschedule）。 */
    fun reschedule(context: Context) {
        cancelAlarm(context.applicationContext)
        sync(context)
    }

    // ---------------- 前台服务 ----------------

    private fun startService(context: Context) {
        val intent = Intent(context, NtfyReceiveService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Android 12+ 从后台启动前台服务会被拒，那就等下次进 App 再启
            Log.w(TAG, "启动前台服务失败", e)
        }
    }

    private fun stopService(context: Context) {
        try {
            context.stopService(Intent(context, NtfyReceiveService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "停止前台服务失败", e)
        }
    }

    fun isServiceRunning(context: Context): Boolean = NtfyReceiveService.isAlive

    // ---------------- 精确闹钟 ----------------

    fun canUseExactAlarm(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, NtfyAlarmReceiver::class.java).apply {
            action = NtfyAlarmReceiver.ACTION_TICK
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }

    fun scheduleAlarm(context: Context, delayMs: Long = ALARM_INTERVAL_MS) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // 权限被收回：改走 15 分钟 Job，别静默停掉
            scheduleFallbackJob(app)
            return
        }
        val at = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(app))
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, alarmIntent(app))
            }
            Log.i(TAG, "下一次补查：${delayMs / 1000}s 后")
        } catch (e: Exception) {
            Log.w(TAG, "排精确闹钟失败，退回 JobScheduler", e)
            scheduleFallbackJob(app)
        }
    }

    fun cancelAlarm(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app))
    }

    // ---------------- 退路：15 分钟 JobScheduler ----------------

    private fun scheduleFallbackJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(FALLBACK_JOB_ID) != null) return
        val job = JobInfo.Builder(FALLBACK_JOB_ID, ComponentName(context, NtfyPollJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(FALLBACK_INTERVAL_MS)
            .setPersisted(true)
            .build()
        val result = scheduler.schedule(job)
        Log.i(TAG, if (result == JobScheduler.RESULT_SUCCESS) "已用 15 分钟系统任务兜底" else "兜底任务排班失败：$result")
    }

    private fun cancelFallbackJob(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(FALLBACK_JOB_ID)
    }
}
