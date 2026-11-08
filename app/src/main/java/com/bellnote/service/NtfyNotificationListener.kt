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
import com.bellnote.data.SOURCE_NTFY
import com.bellnote.data.TodoStore
import com.bellnote.parser.TodoParser
import com.bellnote.ui.MainActivity
import com.bellnote.util.Permissions
import com.bellnote.util.TimeFormat

/**
 * 监听 ntfy 的通知，把标题 / 正文解析成待办写进系统日历。
 * 认的通知包名：io.heckel.ntfy（官方 ntfy App）。
 */
class NtfyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected: 通知使用权已连接，包名=${packageName}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "onListenerDisconnected: 通知使用权断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBarNotification = sbn ?: return
        Log.i(TAG, "收到通知 pkg=${statusBarNotification.packageName} id=${statusBarNotification.id}")
        if (statusBarNotification.packageName != CalendarRepository.NTFY_PACKAGE) return
        if (!Prefs.autoImport(this)) {
            Log.i(TAG, "自动导入已关闭，忽略")
            return
        }

        val notification = statusBarNotification.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()

        if (title.isEmpty() && text.isEmpty()) return
        Log.i(TAG, "ntfy 通知正文: title=$title text=${text.take(100)}")
        if (!Prefs.shouldHandle(this, "$title|$text")) {
            Log.i(TAG, "60 秒内重复内容，跳过")
            return
        }

        if (!Permissions.hasCalendar(this)) {
            Log.i(TAG, "缺少日历权限，跳过")
            notifyResult("没能写入日历", "缺少日历权限：打开 App 授权后再发一次通知")
            return
        }

        val appContext = applicationContext
        Thread {
            try {
                val repository = CalendarRepository(appContext)
                val store = TodoStore(appContext)
                val now = System.currentTimeMillis()

                // 先看是不是「删除」指令
                val command = TodoParser.parseCommand(text, now)
                if (command != null) {
                    handleCommand(repository, command)
                    return@Thread
                }

                val parsed = TodoParser.parseAll(title, text, now)
                val strict = Prefs.strictParse(appContext)
                val results = if (strict) parsed.filter { it.timeParsed } else parsed
                val dropped = parsed.size - results.size
                Log.i(TAG, "解析出 ${parsed.size} 条；严格模式=$strict，采纳 ${results.size} 条，丢弃 $dropped 条")
                if (results.isEmpty()) {
                    if (dropped > 0) {
                        Log.i(TAG, "这条通知里没有带明确时间的待办，按设置不写日历")
                    }
                    return@Thread
                }
                val calendarId = repository.defaultCalendarId()
                Log.i(TAG, "目标日历 id=$calendarId")
                if (calendarId <= 0) {
                    notifyResult("没能写入日历", "还没有可写的日历：请在设置里选一个目标日历")
                    return@Thread
                }

                var succeeded = 0
                val lines = mutableListOf<String>()
                for (result in results) {
                    val eventId = repository.insertEvent(result.spec, calendarId)
                    Log.i(
                        TAG,
                        "写入 ${result.spec.title} -> eventId=$eventId fallback=${result.fallback}"
                    )
                    // 写成功记 eventId；写失败也记一笔（eventId=-1），对账时才能报「缺」
                    store.record(result.spec, eventId, SOURCE_NTFY)
                    if (eventId > 0) {
                        succeeded++
                        val base = "· ${result.spec.title}　${TimeFormat.full(result.spec.startMillis)}"
                        lines.add(
                            if (result.fallback && !result.reason.isNullOrBlank()) "$base（${result.reason}）" else base
                        )
                    }
                }

                if (succeeded > 0) {
                    val head = if (results.size == 1) {
                        "ntfy 待办已进日历"
                    } else {
                        "ntfy 待办已进日历（$succeeded/${results.size} 条）"
                    }
                    notifyResult(head, lines.joinToString("\n"))
                } else {
                    notifyResult("写日历失败", "系统日历拒绝了写入：换一个目标日历再试")
                }
            } catch (e: Exception) {
                Log.w(TAG, "处理 ntfy 通知失败", e)
                notifyResult("写日历出错了", e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    /** 处理「发 ntfy 删日历待办」指令：只删本 App 写入的事件，标题严格匹配，可按日期再收窄。 */
    private fun handleCommand(repository: CalendarRepository, command: TodoParser.Command) {
        val store = TodoStore(this)
        if (command.title.isNullOrBlank() && command.timeMillis == null) {
            notifyResult("删除指令缺内容", "至少给标题，例如：删除：数学作业")
            return
        }
        val matches = repository.findOwnEvents(command.title, command.timeMillis)
        Log.i(TAG, "删除指令 title=${command.title} time=${command.timeMillis} 匹配 ${matches.size} 条")
        if (matches.isEmpty()) {
            // 日历里本来就没有（可能早就手动删了），本地账也要跟着清，不然对账会一直报「多」
            if (!command.title.isNullOrBlank()) {
                store.markDeletedByTitle(command.title, command.timeMillis)
            }
            val what = buildString {
                if (!command.title.isNullOrBlank()) append(command.title)
                if (command.timeMillis != null) {
                    if (isNotEmpty()) append(" ")
                    append(TimeFormat.full(command.timeMillis))
                }
            }
            notifyResult("没找到要删的待办", "没有匹配「$what」的 Bellnote 待办，什么都没动")
            return
        }

        val deleted = mutableListOf<String>()
        val deletedIds = mutableListOf<Long>()
        for (item in matches) {
            if (repository.deleteEvent(item.id)) {
                deleted.add("· ${item.title}　${TimeFormat.full(item.startMillis)}")
                deletedIds.add(item.id)
            }
        }
        if (deletedIds.isNotEmpty()) store.markDeletedByEventIds(deletedIds)
        if (!command.title.isNullOrBlank()) store.markDeletedByTitle(command.title, command.timeMillis)
        if (deleted.isEmpty()) {
            notifyResult("删除失败", "系统日历拒绝了删除：${matches.size} 条都没删掉")
        } else {
            notifyResult("已删除 ${deleted.size} 条待办", deleted.joinToString("\n"))
        }
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
