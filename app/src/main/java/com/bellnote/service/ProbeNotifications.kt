package com.bellnote.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断用：把收到的通知里能读到的文字全部落盘，用来回答一个问题——
 * 「微信的消息内容，通知监听到底能不能拿到」。
 *
 * 只采集、不解析、不写日历。验证完这一版会换成真正的待办提取入口。
 */
class ProbeNotifications : NotificationListenerService() {

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    override fun onListenerConnected() {
        super.onListenerConnected()
        record("=== 通知监听已连接 ===")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val n = sbn.notification ?: return
        val e = n.extras ?: return

        fun text(key: String): String =
            e.getCharSequence(key)?.toString()?.replace("\n", " ⏎ ")?.trim().orEmpty()

        val title = text(Notification.EXTRA_TITLE)
        val body = text(Notification.EXTRA_TEXT)
        val big = text(Notification.EXTRA_BIG_TEXT)
        val sub = text(Notification.EXTRA_SUB_TEXT)
        val conv = text(Notification.EXTRA_CONVERSATION_TITLE)
        val lines = (e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: emptyArray())
            .joinToString(" | ") { it?.toString().orEmpty().replace("\n", " ⏎ ") }
        val messages = (e.getParcelableArray(Notification.EXTRA_MESSAGES))
            ?.joinToString(" | ") { it.toString().replace("\n", " ⏎ ") }
            .orEmpty()

        if (title.isEmpty() && body.isEmpty() && big.isEmpty() && lines.isEmpty()) return

        val line = buildString {
            append(stamp.format(Date()))
            append("  [").append(sbn.packageName).append("]")
            if (title.isNotEmpty()) append(" 标题=").append(title)
            if (body.isNotEmpty()) append(" 正文=").append(body)
            if (big.isNotEmpty() && big != body) append(" 大文本=").append(big)
            if (sub.isNotEmpty()) append(" 副标题=").append(sub)
            if (conv.isNotEmpty()) append(" 会话=").append(conv)
            if (lines.isNotEmpty()) append(" 行=").append(lines)
            if (messages.isNotEmpty() && messages.length < 500) append(" 消息=").append(messages)
        }
        record(line)
    }

    private fun record(line: String) {
        Log.i("ProbeNotif", line)
        try {
            synchronized(LOCK) {
                val f = File(filesDir, "probe_notifications.txt")
                if (f.exists() && f.length() > 512 * 1024) {
                    f.writeText("")
                }
                f.appendText(line + "\n")
            }
        } catch (ex: Exception) {
            Log.w("ProbeNotif", "写文件失败", ex)
        }
    }

    companion object {
        private val LOCK = Any()
        fun isGranted(context: android.content.Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            return flat.contains(context.packageName)
        }
    }
}
