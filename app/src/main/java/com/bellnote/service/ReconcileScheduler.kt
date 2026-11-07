package com.bellnote.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bellnote.data.Prefs

/**
 * 用 AlarmManager 自己续期，不引 WorkManager：
 * 少一个依赖，realme 的后台限制下也比 JobScheduler 更容易活下来。
 */
object ReconcileScheduler {

    private const val TAG = "ReconcileScheduler"
    private const val REQUEST_CODE = 20260

    /** 按当前设置重排下一次。关掉定时对账就取消。 */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!Prefs.reconcileEnabled(app)) {
            cancel(app)
            return
        }
        scheduleNext(app, Prefs.reconcileInterval(app) * 60_000L)
    }

    fun scheduleNext(context: Context, delayMillis: Long) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val delay = delayMillis.coerceAtLeast(60_000L)
        val whenAt = System.currentTimeMillis() + delay
        try {
            // setAndAllowWhileIdle 不需要精确闹钟权限，Doze 下也能叫醒
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, pendingIntent(app))
            Log.i(TAG, "下次对账在 ${delay / 60_000L} 分钟后")
        } catch (e: Exception) {
            Log.w(TAG, "排下次对账失败", e)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            manager.cancel(pendingIntent(app))
            Log.i(TAG, "已取消定时对账")
        } catch (e: Exception) {
            Log.w(TAG, "取消定时对账失败", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReconcileReceiver::class.java)
            .setAction(ReconcileReceiver.ACTION_RECONCILE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
