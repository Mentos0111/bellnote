package com.bellnote.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bellnote.App
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.ui.MainActivity
import com.bellnote.util.Permissions

/**
 * 监听 ntfy 的通知，把标题 / 正文交给 [TodoIngestor] 写进系统日历。
 * 认的通知包名：io.heckel.ntfy（官方 ntfy App）。
 *
 * 注意：这是「推送进日历」的一条路径而已。ntfy App 不弹通知时这里什么都收不到，
 * 所以还有 [NtfyFetcher] 直接从 ntfy 服务器补查。
 */
class NtfyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected: 通知使用权已连接，包名=$packageName")
        // 服务被系统回收后又连上时，通知栏里可能还挂着没处理过的 ntfy 通知 —— 先补一遍。
        try {
            val active = activeNotifications ?: emptyArray()
            Log.i(TAG, "重连补收：通知栏里现有 ${active.size} 条")
            if (active.isNotEmpty()) {
                Thread {
                    for (sbn in active) {
                        try {
                            process(sbn)
                        } catch (e: Exception) {
                            Log.w(TAG, "补收单条通知失败", e)
                        }
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取通知栏现有通知失败", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "onListenerDisconnected: 通知使用权断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBarNotification = sbn ?: return
        process(statusBarNotification)
    }

    private fun process(sbn: StatusBarNotification) {
        Log.i(TAG, "收到通知 pkg=${sbn.packageName} id=${sbn.id}")
        if (sbn.packageName != CalendarRepository.NTFY_PACKAGE) return
        if (!Prefs.autoImport(this)) {
            Log.i(TAG, "自动导入已关闭，忽略")
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()

        if (title.isEmpty() && text.isEmpty()) return
        // 抓现场：换行数、总长度都记下来 —— 「换行被压平」这类问题一眼能看出来
        Log.i(
            TAG,
            "ntfy 通知正文: title=$title 长度=${text.length} 换行数=${text.count { it == '\n' }} " +
                "正文=${text.replace('\n', '⏎').take(400)}"
        )
        if (!Prefs.shouldHandle(this, "$title|$text")) {
            Log.i(TAG, "60 秒内重复内容，跳过")
            return
        }

        val appContext = applicationContext
        Thread {
            try {
                val outcome = TodoIngestor.handle(appContext, title, text)
                outcome.noticeTitle?.let { notifyResult(it, outcome.noticeBody.orEmpty()) }
            } catch (e: Exception) {
                Log.w(TAG, "处理 ntfy 通知失败", e)
                notifyResult("写日历出错了", e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    private fun notifyResult(title: String, body: String) {
        if (!Permissions.hasPostNotifications(this)) return
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, App.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFY_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发结果通知失败", e)
        }
    }

    companion object {
        private const val TAG = "NtfyListener"
        private const val NOTIFY_ID = 1001
    }
}
