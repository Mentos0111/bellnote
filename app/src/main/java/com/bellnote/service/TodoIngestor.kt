package com.bellnote.service

import android.content.Context
import android.util.Log
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.data.SOURCE_NTFY
import com.bellnote.data.TodoStore
import com.bellnote.parser.TodoParser
import com.bellnote.util.Permissions
import com.bellnote.util.TimeFormat

/**
 * 「一条 ntfy 消息 → 日历」的唯一入口，通知监听和 ntfy 补查都走这里。
 *
 * 早先这段逻辑写在 NtfyNotificationListener 里，只服务「ntfy App 弹了通知」这一条路；
 * 现在补查（[NtfyFetcher]）也要用，所以抽出来，避免两套规则走偏。
 * 本对象不弹任何通知，只返回 [Outcome] 让调用方决定要不要提示用户。
 */
object TodoIngestor {

    private const val TAG = "TodoIngestor"

    data class Outcome(
        /** 要提示用户的标题；null 表示没什么可说的（正常写入不打扰） */
        val noticeTitle: String? = null,
        val noticeBody: String? = null,
        val dropped: Int = 0,
        val wrote: Int = 0,
        val isDelete: Boolean = false
    )

    fun handle(
        context: Context,
        title: String,
        text: String,
        now: Long = System.currentTimeMillis()
    ): Outcome {
        val app = context.applicationContext
        if (!Permissions.hasCalendar(app)) {
            Prefs.addIgnoredNotice(app, "缺少日历权限", text)
            Log.i(TAG, "缺少日历权限，跳过")
            return Outcome("没能写入日历", "缺少日历权限：打开 App 授权后再发一次通知", dropped = 1)
        }

        val repository = CalendarRepository(app)
        val store = TodoStore(app)

        // 先看是不是「删除」指令
        val command = TodoParser.parseCommand(text, now)
        if (command != null) {
            return handleDelete(app, repository, store, command)
        }

        val parsed = TodoParser.parseAll(title, text, now)
        val strict = Prefs.strictParse(app)
        val results = if (strict) parsed.filter { it.timeParsed } else parsed
        val dropped = parsed.size - results.size
        Log.i(TAG, "解析出 ${parsed.size} 条；严格模式=$strict，采纳 ${results.size} 条，丢弃 $dropped 条")

        if (results.isEmpty()) {
            if (dropped > 0) {
                // 以前这里只写 logcat：手机上什么都看不到，等于静默失败。现在留痕 + 可提示。
                Prefs.addIgnoredNotice(app, "没认出时间", text)
                Log.i(TAG, "这条通知里没有带明确时间的待办，按设置不写日历")
                return Outcome(
                    noticeTitle = "这条没写进日历",
                    noticeBody = "没认出时间：「${preview(text)}」\n（严格模式只收带 yyyy-MM-dd HH:mm 的待办）",
                    dropped = dropped
                )
            }
            return Outcome()
        }

        val calendarId = repository.defaultCalendarId()
        Log.i(TAG, "目标日历 id=$calendarId")
        if (calendarId <= 0) {
            Prefs.addIgnoredNotice(app, "没有可写日历", text)
            return Outcome(
                noticeTitle = "没能写入日历",
                noticeBody = "还没有可写的日历：请在设置里选一个目标日历",
                dropped = results.size
            )
        }

        var succeeded = 0
        val lines = mutableListOf<String>()
        val failed = mutableListOf<String>()
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
            } else {
                failed.add("· ${result.spec.title}　${TimeFormat.full(result.spec.startMillis)}")
            }
        }

        if (failed.isNotEmpty()) {
            Prefs.addIgnoredNotice(app, "写日历失败", failed.joinToString("；") + " | " + text)
        }

        return when {
            succeeded == 0 -> Outcome(
                noticeTitle = "写日历失败",
                noticeBody = "系统日历拒绝了写入：换一个目标日历再试",
                dropped = results.size,
                wrote = 0
            )

            succeeded < results.size -> Outcome(
                noticeTitle = "部分待办没写进日历（$succeeded/${results.size}）",
                noticeBody = lines.joinToString("\n"),
                wrote = succeeded
            )

            else -> Outcome(
                noticeTitle = if (results.size == 1) {
                    "ntfy 待办已进日历"
                } else {
                    "ntfy 待办已进日历（$succeeded/${results.size} 条）"
                },
                noticeBody = lines.joinToString("\n"),
                wrote = succeeded
            )
        }
    }

    /** 处理「发 ntfy 删日历待办」指令：只删本 App 写入的事件，标题严格匹配，可按时间窗口再收窄。 */
    private fun handleDelete(
        context: Context,
        repository: CalendarRepository,
        store: TodoStore,
        command: TodoParser.Command
    ): Outcome {
        if (command.title.isNullOrBlank() && command.timeMillis == null) {
            return Outcome("删除指令缺内容", "至少给标题，例如：删除：数学作业", isDelete = true)
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
            return Outcome(
                noticeTitle = "没找到要删的待办",
                noticeBody = "没有匹配「$what」的 Bellnote 待办，什么都没动",
                isDelete = true
            )
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

        return if (deleted.isEmpty()) {
            Prefs.addIgnoredNotice(context, "删除被系统拒绝", command.title.orEmpty())
            Outcome(
                noticeTitle = "删除失败",
                noticeBody = "系统日历拒绝了删除：${matches.size} 条都没删掉",
                isDelete = true
            )
        } else {
            Outcome(
                noticeTitle = "已删除 ${deleted.size} 条待办",
                noticeBody = deleted.joinToString("\n"),
                wrote = deleted.size,
                isDelete = true
            )
        }
    }

    private fun preview(text: String): String {
        val oneLine = text.replace('\n', ' ').trim()
        return if (oneLine.length <= 40) oneLine else oneLine.take(40) + "…"
    }
}
