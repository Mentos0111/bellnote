package com.bellnote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 「十分钟」模式的触发点：精确闹钟到点 → 拉一次 ntfy → 排下一次。
 *
 * 用 goAsync 保证进程/广播结束后还能把网络请求做完；结果通过 NtfyFetcher 内部
 * 的发通知路径告诉用户（用户能看到「补查 ntfy：补进 N 条」）。
 */
class NtfyAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TICK) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val result = NtfyFetcher.catchUp(app)
                Log.i(TAG, "闹钟补查：${result.summary()}")
            } catch (e: Exception) {
                Log.w(TAG, "闹钟补查失败", e)
            } finally {
                // 不管成功失败都排下一次，别让链路断掉
                try {
                    NtfyBackground.scheduleAlarm(app)
                } catch (e: Exception) {
                    Log.w(TAG, "排下一次闹钟失败", e)
                }
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "NtfyAlarmReceiver"
        const val ACTION_TICK = "com.bellnote.action.NTFY_ALARM_TICK"
    }
}
