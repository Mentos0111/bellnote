package com.bellnote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bellnote.data.Prefs

/** 定时对账的闹钟到了、或者开机了，都在这里接。 */
class ReconcileReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "开机/升级后重排定时对账，并补查一次 ntfy")
                ReconcileScheduler.sync(app)
                // 关机/升级期间 ntfy 的通知可能压根没弹过，开机先补一次
                NtfyFetcher.catchUpInBackground(app)
            }

            ACTION_RECONCILE -> {
                Log.i(TAG, "定时对账闹钟到点")
                val pending = goAsync()
                Thread {
                    try {
                        ReconcileRunner.check(app, notify = true)
                    } catch (e: Exception) {
                        Log.w(TAG, "定时对账失败", e)
                    } finally {
                        // 自己续下一次，避免依赖 setRepeating 在 Doze 下的奇怪行为
                        ReconcileScheduler.scheduleNext(
                            app, Prefs.reconcileInterval(app) * 60_000L
                        )
                        // 对账顺带补查一次 ntfy（自己开线程，不占着这次闹钟的时间窗）
                        NtfyFetcher.catchUpInBackground(app)
                        pending.finish()
                    }
                }.start()
            }

            else -> Unit
        }
    }

    companion object {
        private const val TAG = "ReconcileReceiver"
        const val ACTION_RECONCILE = "com.bellnote.action.RECONCILE"
    }
}
