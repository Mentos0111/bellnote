package com.bellnote.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import java.util.TimeZone

/**
 * 日历读写：写入事件 + 双提醒，并按标记查询本 App 创建的事件。
 * 标记：ORGANIZER = "bellnote@local"，同时在 DESCRIPTION 里带 "[bellnote]"
 *（部分厂商日历会拒绝写 ORGANIZER，所以描述里的标记是兜底，查询时两者都认；
 *  改名前的旧标记 "[ntfy-calendar-app]" / "ntfy-calendar-app@local" 也一起认，保证老事件不丢）。
 */
class CalendarRepository(private val context: Context) {

    data class CalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val isPrimary: Boolean
    )

    fun listCalendars(): List<CalendarInfo> {
        val result = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.IS_PRIMARY,
            Calendars.CALENDAR_ACCESS_LEVEL
        )
        try {
            context.contentResolver.query(Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Calendars._ID)
                val nameIdx = cursor.getColumnIndex(Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = cursor.getColumnIndex(Calendars.ACCOUNT_NAME)
                val primaryIdx = cursor.getColumnIndex(Calendars.IS_PRIMARY)
                val accessIdx = cursor.getColumnIndex(Calendars.CALENDAR_ACCESS_LEVEL)
                while (cursor.moveToNext()) {
                    val access = if (accessIdx >= 0) cursor.getInt(accessIdx) else Calendars.CAL_ACCESS_OWNER
                    if (access < Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    result.add(
                        CalendarInfo(
                            id = id,
                            displayName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else "日历",
                            accountName = if (accountIdx >= 0) cursor.getString(accountIdx).orEmpty() else "",
                            isPrimary = primaryIdx >= 0 && cursor.getInt(primaryIdx) == 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取日历列表失败", e)
        }
        return result
    }

    /** 设置里选过的日历优先；没选过或已失效就退回主日历 / 第一个可写日历。 */
    fun defaultCalendarId(): Long {
        var all = listCalendars()
        if (all.isEmpty()) {
            // 手机上没有任何可写日历（无 GMS / 没建过本地日历）时，自建一本
            val created = createLocalCalendar()
            if (created > 0) all = listCalendars()
        }
        if (all.isEmpty()) return -1L
        val saved = Prefs.calendarId(context)
        if (all.any { it.id == saved }) return saved
        val picked = all.firstOrNull { it.isPrimary } ?: all.first()
        Prefs.setCalendarId(context, picked.id)
        return picked.id
    }

    /**
     * 自建一本「本地日历」：以 sync adapter 身份写 Calendars 表，账户类型用 ACCOUNT_TYPE_LOCAL。
     * 返回日历 id；-1 表示被系统拒绝，-2 表示没日历权限。已存在同名账户时直接返回其 id。
     */
    fun createLocalCalendar(displayName: String = "待办"): Long {
        if (!Prefs.hasCalendarPermission(context)) return -2L
        listCalendars().firstOrNull {
            it.accountName == ACCOUNT_NAME || it.accountName == LEGACY_ACCOUNT_NAME
        }?.let { return it.id }

        val insertUri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()

        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, displayName)
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(Calendars.CALENDAR_COLOR, 0xFF156B5A.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return try {
            context.contentResolver.insert(insertUri, values)?.let { ContentUris.parseId(it) } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "创建本地日历失败", e)
            -1L
        }
    }

    fun insertEvent(spec: TodoSpec, calendarId: Long): Long {
        // 同标题+同开始时间已经存在就不重复写：ntfy 重发、用户连点保存都不该多出一条
        val duplicate = findOwnEvents(spec.title, spec.startMillis)
            .firstOrNull { it.startMillis == spec.startMillis }
        if (duplicate != null) {
            Log.i(TAG, "同类待办已存在 eventId=${duplicate.id}，跳过重复写入")
            return duplicate.id
        }
        val id = insertWithFallback(spec, calendarId)
        if (id > 0) addReminders(id, Prefs.remindMinutes(context))
        return id
    }

    fun updateEvent(eventId: Long, spec: TodoSpec, calendarId: Long): Boolean {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val withAll = eventValues(spec, calendarId).apply {
            put(Events.ORGANIZER, ORGANIZER)
            if (spec.repeat == RepeatRule.NONE) {
                putNull(Events.RRULE)
                putNull(Events.DURATION)
            } else {
                putNull(Events.DTEND)
            }
        }
        val ok = try {
            context.contentResolver.update(uri, withAll, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "更新（带 ORGANIZER）失败，降级重试", e)
            try {
                context.contentResolver.update(uri, eventValues(spec, calendarId), null, null) > 0
            } catch (e2: Exception) {
                Log.w(TAG, "更新失败", e2)
                false
            }
        }
        if (ok) {
            clearReminders(eventId)
            addReminders(eventId, Prefs.remindMinutes(context))
        }
        return ok
    }

    fun deleteEvent(eventId: Long): Boolean = try {
        context.contentResolver.delete(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null
        ) > 0
    } catch (e: Exception) {
        Log.w(TAG, "删除失败", e)
        false
    }

    fun loadEvent(eventId: Long): TodoItem? {
        val projection = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND,
            Events.DURATION, Events.RRULE, Events.DESCRIPTION, Events.CALENDAR_ID
        )
        return try {
            context.contentResolver.query(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                projection, null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val start = if (cursor.getColumnIndex(Events.DTSTART) >= 0) cursor.getLong(cursor.getColumnIndex(Events.DTSTART)) else 0L
                val end = if (cursor.getColumnIndex(Events.DTEND) >= 0 && !cursor.isNull(cursor.getColumnIndex(Events.DTEND))) {
                    cursor.getLong(cursor.getColumnIndex(Events.DTEND))
                } else 0L
                val durationCol = cursor.getColumnIndex(Events.DURATION)
                val rruleCol = cursor.getColumnIndex(Events.RRULE)
                val descriptionCol = cursor.getColumnIndex(Events.DESCRIPTION)
                val duration = when {
                    rruleCol >= 0 && !cursor.isNull(rruleCol) -> durationMinutes(durationCol.let { if (it >= 0) cursor.getString(it) else null })
                    end > start -> ((end - start) / 60_000L).toInt()
                    else -> DEFAULT_DURATION_MINUTES
                }
                val rrule = if (rruleCol >= 0) cursor.getString(rruleCol) else null
                TodoItem(
                    id = eventId,
                    title = cursor.getString(cursor.getColumnIndex(Events.TITLE)).orEmpty(),
                    startMillis = start,
                    durationMinutes = duration,
                    note = stripDescription(descriptionCol.let { if (it >= 0) cursor.getString(it) else null }),
                    repeat = RepeatRule.fromRaw(rrule),
                    calendarId = if (cursor.getColumnIndex(Events.CALENDAR_ID) >= 0) cursor.getLong(cursor.getColumnIndex(Events.CALENDAR_ID)) else -1L
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取单条事件失败", e)
            null
        }
    }

    /** 按标题（可再按日期）找本 App 写过的事件，供「发 ntfy 删除」用。 */
    fun findOwnEvents(title: String?, timeMillis: Long?): List<TodoItem> {
        val all = queryOwnEvents()
        if (title.isNullOrBlank() && timeMillis == null) return emptyList()
        val wanted = title?.trim().orEmpty()
        return all.filter { item ->
            val titleOk = wanted.isEmpty() || TitleKey.same(item.title, wanted)
            val timeOk = timeMillis == null || sameDay(item.startMillis, timeMillis)
            titleOk && timeOk
        }
    }

    private fun sameDay(first: Long, second: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        val a = java.time.Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
        val b = java.time.Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
        return a == b
    }

    /** 查询本 App 创建的事件；calendarId 传 null 表示所有日历。 */
    fun queryOwnEvents(calendarId: Long? = null): List<TodoItem> {
        val projection = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND,
            Events.DURATION, Events.RRULE, Events.DESCRIPTION, Events.CALENDAR_ID
        )
        val items = mutableListOf<TodoItem>()
        try {
            val args = mutableListOf<String>()
            val selection = StringBuilder(
                "(${Events.DESCRIPTION} LIKE ? OR ${Events.ORGANIZER} = ?" +
                    " OR ${Events.DESCRIPTION} LIKE ? OR ${Events.ORGANIZER} = ?)"
            )
            args.add("%$MARKER%")
            args.add(ORGANIZER)
            args.add("%$LEGACY_MARKER%")
            args.add(LEGACY_ORGANIZER)
            if (calendarId != null && calendarId > 0) {
                selection.append(" AND ${Events.CALENDAR_ID} = ?")
                args.add(calendarId.toString())
            }
            context.contentResolver.query(
                Events.CONTENT_URI, projection, selection.toString(), args.toTypedArray(),
                "${Events.DTSTART} ASC"
            )?.use { cursor ->
                collect(cursor, items)
            }
        } catch (e: Exception) {
            Log.w(TAG, "带 ORGANIZER 条件查询失败，仅按描述标记重试", e)
            items.clear()
            try {
                val selection = "${Events.DESCRIPTION} LIKE ? OR ${Events.DESCRIPTION} LIKE ?"
                context.contentResolver.query(
                    Events.CONTENT_URI, projection, selection,
                    arrayOf("%$MARKER%", "%$LEGACY_MARKER%"),
                    "${Events.DTSTART} ASC"
                )?.use { cursor -> collect(cursor, items) }
            } catch (e2: Exception) {
                Log.w(TAG, "查询待办列表失败", e2)
            }
        }
        return items
    }

    // ---------------- 全部日程（不只本 App 写的） ----------------

    data class EventInfo(
        val id: Long,
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val calendarId: Long,
        val calendarName: String,
        /** 是不是 Bellnote 写过的事件 */
        val isOwn: Boolean,
        val allDay: Boolean,
        val note: String?
    )

    /**
     * 读出手机日历里这段时间的全部事件（含别的 App / 手动在日历里建的），
     * 供「全部日程」页浏览和管理。只覆盖可写日历（订阅类只读日历不进来）。
     */
    fun listAllEvents(fromMillis: Long, toMillis: Long): List<EventInfo> {
        val calendars = listCalendars().associateBy { it.id }
        if (calendars.isEmpty()) return emptyList()
        val projection = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION,
            Events.DESCRIPTION, Events.ORGANIZER, Events.CALENDAR_ID, Events.ALL_DAY
        )
        val items = mutableListOf<EventInfo>()
        try {
            val selection = "${Events.DTSTART} >= ? AND ${Events.DTSTART} <= ? AND ${Events.DELETED} = 0"
            context.contentResolver.query(
                Events.CONTENT_URI, projection, selection,
                arrayOf(fromMillis.toString(), toMillis.toString()),
                "${Events.DTSTART} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Events._ID)
                val titleIdx = cursor.getColumnIndex(Events.TITLE)
                val startIdx = cursor.getColumnIndex(Events.DTSTART)
                val endIdx = cursor.getColumnIndex(Events.DTEND)
                val descIdx = cursor.getColumnIndex(Events.DESCRIPTION)
                val organizerIdx = cursor.getColumnIndex(Events.ORGANIZER)
                val calIdx = cursor.getColumnIndex(Events.CALENDAR_ID)
                val allDayIdx = cursor.getColumnIndex(Events.ALL_DAY)
                while (cursor.moveToNext()) {
                    if (idIdx < 0 || startIdx < 0) continue
                    val calendarId = if (calIdx >= 0) cursor.getLong(calIdx) else -1L
                    val calendar = calendars[calendarId] ?: continue
                    val start = cursor.getLong(startIdx)
                    val end = if (endIdx >= 0 && !cursor.isNull(endIdx)) cursor.getLong(endIdx) else start
                    val description = if (descIdx >= 0) cursor.getString(descIdx) else null
                    val organizer = if (organizerIdx >= 0) cursor.getString(organizerIdx) else null
                    items.add(
                        EventInfo(
                            id = cursor.getLong(idIdx),
                            title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else "",
                            startMillis = start,
                            endMillis = if (end > start) end else start,
                            calendarId = calendarId,
                            calendarName = calendar.displayName,
                            isOwn = isOwnMarker(description, organizer),
                            allDay = allDayIdx >= 0 && cursor.getInt(allDayIdx) == 1,
                            note = stripDescription(description)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取全部日程失败", e)
        }
        return items
    }

    private fun isOwnMarker(description: String?, organizer: String?): Boolean {
        if (organizer == ORGANIZER || organizer == LEGACY_ORGANIZER) return true
        val d = description ?: return false
        return d.contains(MARKER) || d.contains(LEGACY_MARKER)
    }

    // ---------------- 内部实现 ----------------

    private fun collect(cursor: android.database.Cursor, items: MutableList<TodoItem>) {
        val idIdx = cursor.getColumnIndex(Events._ID)
        val titleIdx = cursor.getColumnIndex(Events.TITLE)
        val startIdx = cursor.getColumnIndex(Events.DTSTART)
        val endIdx = cursor.getColumnIndex(Events.DTEND)
        val durationIdx = cursor.getColumnIndex(Events.DURATION)
        val rruleIdx = cursor.getColumnIndex(Events.RRULE)
        val descIdx = cursor.getColumnIndex(Events.DESCRIPTION)
        val calIdx = cursor.getColumnIndex(Events.CALENDAR_ID)
        while (cursor.moveToNext()) {
            if (idIdx < 0 || startIdx < 0) continue
            val start = cursor.getLong(startIdx)
            val end = if (endIdx >= 0 && !cursor.isNull(endIdx)) cursor.getLong(endIdx) else 0L
            val rrule = if (rruleIdx >= 0) cursor.getString(rruleIdx) else null
            val duration = when {
                !rrule.isNullOrBlank() -> durationMinutes(if (durationIdx >= 0) cursor.getString(durationIdx) else null)
                end > start -> ((end - start) / 60_000L).toInt()
                else -> DEFAULT_DURATION_MINUTES
            }
            items.add(
                TodoItem(
                    id = cursor.getLong(idIdx),
                    title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else "",
                    startMillis = start,
                    durationMinutes = duration,
                    note = stripDescription(if (descIdx >= 0) cursor.getString(descIdx) else null),
                    repeat = RepeatRule.fromRaw(rrule),
                    calendarId = if (calIdx >= 0) cursor.getLong(calIdx) else -1L
                )
            )
        }
    }

    private fun insertWithFallback(spec: TodoSpec, calendarId: Long): Long {
        if (calendarId <= 0) return -1L
        // 1) 完整字段（含 ORGANIZER 标记）
        val full = eventValues(spec, calendarId).apply { put(Events.ORGANIZER, ORGANIZER) }
        try {
            context.contentResolver.insert(Events.CONTENT_URI, full)?.let { return ContentUris.parseId(it) }
        } catch (e: Exception) {
            Log.w(TAG, "插入（含 ORGANIZER）失败，降级重试", e)
        }
        // 2) 不含 ORGANIZER
        try {
            context.contentResolver.insert(Events.CONTENT_URI, eventValues(spec, calendarId))?.let {
                return ContentUris.parseId(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "插入（不含 ORGANIZER）失败，用最小字段重试", e)
        }
        // 3) 最小字段
        val minimal = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, spec.title)
            put(Events.DESCRIPTION, buildDescription(spec.note))
            put(Events.DTSTART, spec.startMillis)
            put(Events.DTEND, spec.endMillis)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return try {
            context.contentResolver.insert(Events.CONTENT_URI, minimal)?.let { ContentUris.parseId(it) } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "插入失败", e)
            -1L
        }
    }

    private fun eventValues(spec: TodoSpec, calendarId: Long): ContentValues = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, spec.title)
        put(Events.DESCRIPTION, buildDescription(spec.note))
        put(Events.DTSTART, spec.startMillis)
        put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        put(Events.HAS_ALARM, 1)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
        if (spec.repeat == RepeatRule.NONE) {
            put(Events.DTEND, spec.endMillis)
        } else {
            put(Events.DURATION, rfcDuration(spec.durationMinutes))
            put(Events.RRULE, spec.repeat.rrule)
        }
    }

    private fun addReminders(eventId: Long, minutes: List<Int>) {
        for (minute in minutes) {
            val values = ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, minute)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }
            try {
                context.contentResolver.insert(Reminders.CONTENT_URI, values)
            } catch (e: Exception) {
                Log.w(TAG, "写提醒失败：$minute 分钟", e)
            }
        }
    }

    private fun clearReminders(eventId: Long) {
        try {
            context.contentResolver.delete(
                Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "清旧提醒失败", e)
        }
    }

    private fun buildDescription(note: String?): String {
        val text = StringBuilder()
        if (!note.isNullOrBlank()) text.append(note.trim()).append("\n\n")
        text.append("— 由 Bellnote 创建 $MARKER")
        return text.toString()
    }

    private fun stripDescription(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val cleaned = description
            .replace("— 由 Bellnote 创建 $MARKER", "")
            .replace("— 由 ntfy 待办日历创建 $LEGACY_MARKER", "")
            .replace(MARKER, "")
            .replace(LEGACY_MARKER, "")
            .trim()
        return cleaned.ifBlank { null }
    }

    private fun rfcDuration(minutes: Int): String {
        val safe = if (minutes <= 0) 60 else minutes
        val hours = safe / 60
        val mins = safe % 60
        val sb = StringBuilder("PT")
        if (hours > 0) sb.append(hours).append("H")
        if (mins > 0 || hours == 0) sb.append(mins).append("M")
        return sb.toString()
    }

    /** 解析 "PT90M" / "PT1H30M" / "P1D" 这类时长。 */
    private fun durationMinutes(rfc: String?): Int {
        if (rfc.isNullOrBlank()) return DEFAULT_DURATION_MINUTES
        val days = Regex("(\\d+)D").find(rfc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val hours = Regex("(\\d+)H").find(rfc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("(\\d+)M").find(rfc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = days * 24 * 60 + hours * 60 + mins
        return if (total <= 0) DEFAULT_DURATION_MINUTES else total
    }

    companion object {
        private const val TAG = "CalendarRepository"
        const val MARKER = "[bellnote]"
        const val ORGANIZER = "bellnote@local"
        const val ACCOUNT_NAME = "bellnote@local"

        /** 旧版本（改名 ntfy 待办日历 时期）写下的标记，查询时要一起认，否则老事件会消失。 */
        const val LEGACY_MARKER = "[ntfy-calendar-app]"
        const val LEGACY_ORGANIZER = "ntfy-calendar-app@local"
        const val LEGACY_ACCOUNT_NAME = "ntfy-calendar-app@local"

        const val NTFY_PACKAGE = "io.heckel.ntfy"
    }
}
