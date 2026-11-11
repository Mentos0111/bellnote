package com.bellnote.util

import com.bellnote.data.LocalTodo

/**
 * 账本备份成 JSON（换机 / 重装前存一份）。
 * 只做导出，不做导入 —— 导入还要处理 eventId 冲突，等真有人用再说。
 */
object LedgerBackup {

    fun toJson(items: List<LocalTodo>, now: Long = System.currentTimeMillis()): String {
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"app\": \"bellnote\",\n")
        builder.append("  \"exported_at\": ").append(now).append(",\n")
        builder.append("  \"count\": ").append(items.size).append(",\n")
        builder.append("  \"items\": [\n")
        items.forEachIndexed { index, item ->
            builder.append("    {")
            builder.append("\"title\": ").append(quote(item.title)).append(", ")
            builder.append("\"start_millis\": ").append(item.startMillis).append(", ")
            builder.append("\"duration_minutes\": ").append(item.durationMinutes).append(", ")
            builder.append("\"repeat\": ").append(quote(item.repeat.name)).append(", ")
            builder.append("\"source\": ").append(quote(item.source)).append(", ")
            builder.append("\"event_id\": ").append(item.eventId).append(", ")
            builder.append("\"note\": ").append(item.note?.let { quote(it) } ?: "null")
            builder.append("}")
            if (index != items.lastIndex) builder.append(",")
            builder.append("\n")
        }
        builder.append("  ]\n")
        builder.append("}\n")
        return builder.toString()
    }

    fun quote(raw: String): String {
        val builder = StringBuilder("\"")
        for (c in raw) {
            when (c) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (c < ' ') builder.append("\\u%04x".format(c.code)) else builder.append(c)
            }
        }
        return builder.append('"').toString()
    }
}
