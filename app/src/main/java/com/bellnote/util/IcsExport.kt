package com.bellnote.util

import com.bellnote.data.LocalTodo
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 把本地账本导成 .ics，换个手机 / 重装别再靠手工抄。
 * 时间一律写成 UTC（`...Z`），免得各个日历 App 对 TZID 处理不一致。
 */
object IcsExport {

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    fun build(items: List<LocalTodo>, now: Long = System.currentTimeMillis()): String {
        val builder = StringBuilder()
        builder.append("BEGIN:VCALENDAR\r\n")
        builder.append("VERSION:2.0\r\n")
        builder.append("PRODID:-//Bellnote//Todo export//CN\r\n")
        builder.append("CALSCALE:GREGORIAN\r\n")
        builder.append("X-WR-CALNAME:Bellnote 待办\r\n")
        for (item in items) {
            val end = item.startMillis + item.durationMinutes * 60_000L
            builder.append("BEGIN:VEVENT\r\n")
            builder.append("UID:bellnote-${item.id}-${item.startMillis}@local\r\n")
            builder.append("DTSTAMP:${STAMP.format(Instant.ofEpochMilli(now))}\r\n")
            builder.append("DTSTART:${STAMP.format(Instant.ofEpochMilli(item.startMillis))}\r\n")
            builder.append("DTEND:${STAMP.format(Instant.ofEpochMilli(end))}\r\n")
            builder.append("SUMMARY:${escape(item.title)}\r\n")
            if (!item.note.isNullOrBlank()) {
                builder.append("DESCRIPTION:${escape(item.note)}\r\n")
            }
            val rrule = item.repeat.rrule
            if (!rrule.isNullOrBlank()) builder.append("RRULE:$rrule\r\n")
            builder.append("END:VEVENT\r\n")
        }
        builder.append("END:VCALENDAR\r\n")
        return builder.toString()
    }

    /** ICS 里这几个字符有语义，必须转义。 */
    private fun escape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
}
