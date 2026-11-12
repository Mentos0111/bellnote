package com.bellnote.service

import android.content.Context
import android.util.Log
import com.bellnote.data.CalendarRepository
import com.bellnote.data.MessageLog
import com.bellnote.data.Prefs
import com.bellnote.data.SOURCE_NTFY
import com.bellnote.data.SpecDiff
import com.bellnote.data.TodoItem
import com.bellnote.data.TodoSpec
import com.bellnote.data.TodoStore
import com.bellnote.parser.TodoParser
import com.bellnote.util.Permissions
import com.bellnote.util.TimeFormat

/**
 * 「一条 ntfy 消息 → 日历」的唯一入口，通知监听和 ntfy 补查都走这里。
 *
 * 本对象不弹任何通知，只返回 [Outcome] 让调用方决定要不要提示用户。
 */
object TodoIngestor {

    private const val TAG = "TodoIngestor"

    /** 补查重放时的保护阈值：消息比现在早这么多，就按「重放」对待，不给删后来新建的事件。 */
    private const val REPLAY_GUARD_GAP_MS = 5L * 60_000L

    data class Outcome(
        /** 要提示用户的标题；null 表示没什么可说的 */
        val noticeTitle: String? = null,
        val noticeBody: String? = null,
        val dropped: Int = 0,
        val wrote: Int = 0,
        val updated: Int = 0,
        val isDelete: Boolean = false
    ) {
        /** 这一条消息有没有真的改变日历（补查用它决定要不要发通知） */
        val changed: Boolean get() = wrote > 0 || updated > 0 || dropped > 0
    }

    /**
     * @param messageTime 这条消息本身的时间（通知到达时间，或补查拿到的消息时间）。
     *        补查会重放旧消息，靠它挡住「用三天前的删除指令删掉昨天新建的事件」。
     * @param source 消息从哪来（通知 / 补查 / 重试），只写进消息记录，不参与解析。
     */
    fun handle(
        context: Context,
        title: String,
        text: String,
        messageTime: Long = System.currentTimeMillis(),
        now: Long = System.currentTimeMillis(),
        source: String = "ntfy"
    ): Outcome {
        val app = context.applicationContext
        val outcome = handleInternal(app, title, text, messageTime, now)
        // 每条消息都留一笔：界面上「这条为什么没进日历」全靠它
        MessageLog.log(
            app,
            MessageLog.DIR_IN,
            source,
            title.ifBlank { "（无标题）" },
            text,
            resultOf(outcome),
            detailOf(outcome)
        )
        return outcome
    }

    private fun handleInternal(
        context: Context,
        title: String,
        text: String,
        messageTime: Long,
        now: Long
    ): Outcome {
        val app = context.applicationContext
        if (!Permissions.hasCalendar(app)) {
            Prefs.addIgnoredNotice(app, "缺少日历权限", text)
            Log.i(TAG, "缺少日历权限，跳过")
            return Outcome("没能写入日历", "缺少日历权限：打开 App 授权后再发一次通知", dropped = 1)
        }

        val repository = CalendarRepository(app)
        val store = TodoStore(app)

        // 先看是不是「删除 / 改期」指令
        val command = TodoParser.parseCommand(text, now)
        if (command != null) {
            return when (command.type) {
                TodoParser.CommandType.DELETE ->
                    handleDelete(app, repository, store, command, messageTime)

                TodoParser.CommandType.MOVE ->
                    handleMove(app, repository, store, command)
            }
        }

        val parsed = TodoParser.parseAll(title, text, now)
        val strict = Prefs.strictParse(app)
        val results = if (strict) parsed.filter { it.timeParsed } else parsed
        val dropped = parsed.size - results.size
        Log.i(TAG, "解析出 ${parsed.size} 条；严格模式=$strict，采纳 ${results.size} 条，丢弃 $dropped 条")

        if (results.isEmpty()) {
            if (dropped > 0) {
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

        var inserted = 0
        var updated = 0
        val lines = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (result in results) {
            val spec = result.spec
            // 同标题同开始时间已经存在：内容没变就跳过，变了就更新（不再一律当重复丢弃）
            val existing = repository.findOwnEvents(spec.title, spec.startMillis)
                .firstOrNull { it.startMillis == spec.startMillis }
            if (existing != null) {
                if (SpecDiff.differs(existing, spec)) {
                    val ok = repository.updateEvent(existing.id, spec, existing.calendarId)
                    Log.i(TAG, "同一条待办内容有变，更新 eventId=${existing.id} ok=$ok")
                    if (ok) {
                        store.updateByEventId(existing.id, spec)
                        updated++
                        lines.add("· ${spec.title}　${TimeFormat.full(spec.startMillis)}（已更新）")
                    } else {
                        failed.add("· ${spec.title}（更新失败）")
                    }
                } else {
                    Log.i(TAG, "同一条待办内容没变，跳过 eventId=${existing.id}")
                    updated++
                    lines.add("· ${spec.title}　${TimeFormat.full(spec.startMillis)}（无变化）")
                }
                continue
            }

            val eventId = repository.insertEvent(spec, calendarId)
            Log.i(TAG, "写入 ${spec.title} -> eventId=$eventId fallback=${result.fallback}")
            // 写成功记 eventId；写失败也记一笔（eventId=-1），对账时才能报「缺」
            store.record(spec, eventId, SOURCE_NTFY)
            if (eventId > 0) {
                inserted++
                val base = "· ${spec.title}　${TimeFormat.full(spec.startMillis)}"
                lines.add(
                    if (result.fallback && !result.reason.isNullOrBlank()) "$base（${result.reason}）" else base
                )
            } else {
                failed.add("· ${spec.title}　${TimeFormat.full(spec.startMillis)}")
            }
        }

        if (failed.isNotEmpty()) {
            Prefs.addIgnoredNotice(app, "写日历失败", failed.joinToString("；") + " | " + text)
        }

        val okCount = inserted + updated
        return when {
            okCount == 0 -> Outcome(
                noticeTitle = "写日历失败",
                noticeBody = "系统日历拒绝了写入：换一个目标日历再试",
                dropped = results.size
            )

            failed.isNotEmpty() -> Outcome(
                noticeTitle = "部分待办没写进日历（$okCount/${results.size}）",
                noticeBody = lines.joinToString("\n"),
                wrote = inserted,
                updated = updated
            )

            else -> Outcome(
                noticeTitle = if (results.size == 1) {
                    "ntfy 待办已进日历"
                } else {
                    "ntfy 待办已进日历（$okCount/${results.size} 条）"
                },
                noticeBody = lines.joinToString("\n"),
                wrote = inserted,
                updated = updated
            )
        }
    }

    /**
     * 删除：只删本 App 写入的事件，标题严格匹配，可按时间窗口收窄。
     * 补查重放时（messageTime 明显早于现在）额外要求账本创建时间不晚于消息时间，防止误删后来新建的。
     */
    private fun handleDelete(
        context: Context,
        repository: CalendarRepository,
        store: TodoStore,
        command: TodoParser.Command,
        messageTime: Long
    ): Outcome {
        if (command.title.isNullOrBlank() && command.timeMillis == null) {
            return Outcome("删除指令缺内容", "至少给标题，例如：删除：数学作业", isDelete = true)
        }
        val matched = repository.findOwnEvents(command.title, command.timeMillis)
        val now = System.currentTimeMillis()
        val isReplay = messageTime < now - REPLAY_GUARD_GAP_MS
        val matches = if (!isReplay) {
            matched
        } else {
            matched.filter { item ->
                val createdAt = store.createdAtOfEvent(item.id)
                // 账本里查不到（比如手工「纳入管理」的事件）也放行，但只认消息之前的
                createdAt == null || createdAt <= messageTime
            }
        }
        Log.i(
            TAG,
            "删除指令 title=${command.title} time=${command.timeMillis} 匹配 ${matched.size} 条，" +
                "重放=$isReplay 采纳 ${matches.size} 条"
        )

        if (matched.isNotEmpty() && matches.isEmpty()) {
            return Outcome(
                noticeTitle = "跳过一条过期的删除指令",
                noticeBody = "「${command.title}」的匹配事件是这条指令发出之后才建的，没删",
                isDelete = true
            )
        }

        if (matches.isEmpty()) {
            // 日历里本来就没有（可能早就手动删了），本地账也要跟着清，不然对账会一直报「多」
            if (!command.title.isNullOrBlank()) {
                store.markDeletedByTitle(
                    command.title,
                    command.timeMillis,
                    if (isReplay) messageTime else null
                )
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
        if (!command.title.isNullOrBlank()) {
            store.markDeletedByTitle(
                command.title,
                command.timeMillis,
                if (isReplay) messageTime else null
            )
        }

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

    /**
     * 改期：按「标题 + 原时间」找到那一条，把开始时间换掉，其余字段（时长/备注/重复）原样保留。
     * 「周四化学挪到周六」这种一次搞定，不用先删再建（以前删成功、建失败就半残）。
     */
    private fun handleMove(
        context: Context,
        repository: CalendarRepository,
        store: TodoStore,
        command: TodoParser.Command
    ): Outcome {
        if (command.title.isNullOrBlank()) {
            return Outcome("改期指令缺标题", "至少给标题，例如：改期：化学课 2026-11-12 20:30 到 2026-11-14 20:30")
        }
        if (command.newTimeMillis == null) {
            return Outcome("改期指令缺新时间", "要写清楚改到什么时候，格式 yyyy-MM-dd HH:mm")
        }
        val matches = repository.findOwnEvents(command.title, command.timeMillis)
        Log.i(TAG, "改期指令 title=${command.title} from=${command.timeMillis} 匹配 ${matches.size} 条")
        if (matches.isEmpty()) {
            val from = command.timeMillis?.let { " ${TimeFormat.full(it)}" }.orEmpty()
            return Outcome(
                noticeTitle = "没找到要改期的待办",
                noticeBody = "没有匹配「${command.title}$from」的 Bellnote 待办，什么都没动",
                isDelete = true
            )
        }

        val moved = mutableListOf<String>()
        var failedCount = 0
        for (item in matches) {
            val spec = TodoSpec(
                title = item.title,
                startMillis = command.newTimeMillis,
                durationMinutes = item.durationMinutes,
                note = item.note,
                repeat = item.repeat
            )
            if (repository.updateEvent(item.id, spec, item.calendarId)) {
                store.updateByEventId(item.id, spec)
                moved.add(
                    "· ${item.title}　${TimeFormat.full(item.startMillis)} → ${TimeFormat.full(command.newTimeMillis)}"
                )
            } else {
                failedCount++
            }
        }

        return if (moved.isEmpty()) {
            Prefs.addIgnoredNotice(context, "改期失败", command.title)
            Outcome(
                noticeTitle = "改期失败",
                noticeBody = "系统日历拒绝了更新：${matches.size} 条都没改",
                isDelete = true
            )
        } else {
            Outcome(
                noticeTitle = "已改期 ${moved.size} 条待办",
                noticeBody = moved.joinToString("\n") +
                    if (failedCount > 0) "\n（另有 $failedCount 条改不了）" else "",
                updated = moved.size,
                isDelete = true
            )
        }
    }

    /** 消息记录里的结果标签。写失败要单列出来，不能和「没认出时间」混成一档。 */
    private fun resultOf(outcome: Outcome): String {
        val notice = outcome.noticeTitle.orEmpty()
        val failed = notice.contains("失败") || notice.contains("没能")
        return when {
            failed -> MessageLog.RESULT_FAILED
            outcome.wrote > 0 || outcome.updated > 0 -> MessageLog.RESULT_OK
            outcome.dropped > 0 -> MessageLog.RESULT_DROPPED
            else -> MessageLog.RESULT_INFO
        }
    }

    private fun detailOf(outcome: Outcome): String =
        listOfNotNull(outcome.noticeTitle, outcome.noticeBody).joinToString("\n")

    private fun preview(text: String): String {
        val oneLine = text.replace('\n', ' ').trim()
        return if (oneLine.length <= 40) oneLine else oneLine.take(40) + "…"
    }
}
