package com.bellnote.parser

import com.bellnote.data.RepeatRule
import com.bellnote.data.TodoSpec
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ntfy 通知正文解析器。
 * 支持：键值文本（标题：xxx / 时间：2026-11-08 19:00）、JSON、以及解析失败时的容错。
 */
object TodoParser {

    data class Result(
        val spec: TodoSpec,
        val fallback: Boolean,
        val reason: String? = null,
        /**
         * 时间是不是真的从消息里解析出来的。
         * false 表示用了「当前时间 +1 小时」的兜底 —— 严格模式下这类结果不会写进日历。
         */
        val timeParsed: Boolean = false
    )

    private const val DEFAULT_DURATION = 60
    private const val FALLBACK_LEAD_MS = 60L * 60_000L

    private val KEY_TITLE = setOf("标题", "主题", "题目", "事件", "待办", "事项", "title", "name", "subject")
    private val KEY_TIME = setOf("时间", "开始", "开始时间", "日期", "time", "date", "start", "datetime", "when")
    private val KEY_DURATION = setOf("时长", "持续", "持续时长", "分钟", "duration", "minutes", "min", "length")
    private val KEY_NOTE = setOf("备注", "内容", "说明", "详情", "note", "notes", "desc", "description", "detail")
    private val KEY_REPEAT = setOf("重复", "周期", "频率", "repeat", "recurrence", "rule", "frequency")

    private val ALL_KEYS = KEY_TITLE + KEY_TIME + KEY_DURATION + KEY_NOTE + KEY_REPEAT

    private val KV_LINE = Regex("^\\s*([^:：]{1,12})\\s*[:：]\\s*(.*)$")

    private val FULL_DATE_TIME = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd HH",
        "yyyy-M-d H:mm:ss",
        "yyyy-M-d H:mm",
        "yyyy-M-d H",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/M/d H:mm:ss",
        "yyyy/M/d H:mm",
        "yyyy.MM.dd HH:mm:ss",
        "yyyy.MM.dd HH:mm",
        "yyyy.M.d H:mm",
        "yyyy年M月d日 HH:mm",
        "yyyy年M月d日H:mm",
        "yyyy年M月d日 HH时mm分"
    )

    private val FULL_DATE = listOf(
        "yyyy-MM-dd",
        "yyyy-M-d",
        "yyyy/MM/dd",
        "yyyy/M/d",
        "yyyy.MM.dd",
        "yyyy.M.d",
        "yyyy年M月d日"
    )

    private val MONTH_DAY = Regex("^(\\d{1,2})[-/月](\\d{1,2})日?(?:\\s*(\\d{1,2})[:：](\\d{2}))?$")
    private val TIME_ONLY = Regex("^(\\d{1,2})[:：](\\d{2})(?::(\\d{2}))?$")
    private val EPOCH = Regex("^\\d{10,13}$")

    fun parse(
        notifTitle: String?,
        notifText: String?,
        now: Long = System.currentTimeMillis()
    ): Result {
        val title = notifTitle?.trim().orEmpty()
        val text = notifText?.trim().orEmpty()

        if (text.isEmpty() && title.isEmpty()) {
            return fallback(title, text, now, "通知没有内容")
        }

        // JSON（允许模型把 JSON 包在说明文字里）
        if (text.contains('{')) {
            val fields = jsonFields(text)
            if (fields.isNotEmpty() && fields.keys.any { it in KEY_TITLE || it in KEY_TIME }) {
                return build(fields, title, text, now, "JSON")
            }
        }

        val kv = keyValueFields(text)
        if (kv.isNotEmpty()) {
            return build(kv, title, text, now, "键值")
        }

        return fallback(title, text, now, "没认出「键名：值」或 JSON 格式")
    }

    /**
     * 从一个已拆好的字段表构造一条待办。
     * 给「一张截图多条待办」用：模型返回 JSON 数组时逐条走这里。
     */
    fun fromFields(
        fields: Map<String, String>,
        now: Long = System.currentTimeMillis(),
        kind: String = "JSON"
    ): Result = build(
        fields.mapKeys { it.key.trim().lowercase(Locale.CHINA) },
        "",
        "",
        now,
        kind
    )

    /**
     * 识别「删除指令」。支持：
     * 1) JSON  {"action":"delete","title":"数学作业","time":"2026-11-08 19:00"}
     * 2) 文本  删除：数学作业 [2026-11-08 19:00] / delete: xxx
     * 不是删除指令时返回 null。
     */
    fun parseCommand(notifText: String?, now: Long = System.currentTimeMillis()): Command? {
        val text = notifText?.trim().orEmpty()
        if (text.isEmpty()) return null

        if (text.contains('{')) {
            val fields = jsonFields(text).mapKeys { it.key.trim().lowercase(Locale.CHINA) }
            val action = fields["action"]?.trim()?.lowercase(Locale.CHINA)
            if (action in DELETE_ACTIONS) {
                val title = fields["title"]?.trim()?.ifBlank { null }
                val time = parseTime(fields["time"], now)
                return Command(CommandType.DELETE, title, time)
            }
        }

        val prefix = DELETE_PREFIX.find(text) ?: return null
        val rest = text.substring(prefix.range.last + 1).trim()
        if (rest.isEmpty()) return Command(CommandType.DELETE, null, null)

        // 尾部的日期时间可选：「标题 2026-11-08 19:00」
        val timeMatch = Regex("(\\d{4}-\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)$").find(rest)
        return if (timeMatch != null) {
            val title = rest.substring(0, timeMatch.range.first).trim().ifBlank { null }
            Command(CommandType.DELETE, title, parseTime(timeMatch.value, now))
        } else {
            Command(CommandType.DELETE, rest.ifBlank { null }, null)
        }
    }

    enum class CommandType { DELETE }

    data class Command(
        val type: CommandType,
        val title: String?,
        val timeMillis: Long?
    )

    private val DELETE_PREFIX = Regex("^(删除|删掉|取消|delete|cancel)\\s*[:：]?\\s*", RegexOption.IGNORE_CASE)
    private val DELETE_ACTIONS = setOf("delete", "remove", "cancel", "删除", "取消")

    /**
     * 一条通知里可能有多条待办：
     * 1) JSON 数组 `[{...},{...}]`
     * 2) 包在对象里 `{"todos":[{...}]}`
     * 3) 空行分隔的多段键值文本
     * 都不成立时退回单条解析。
     */
    fun parseAll(
        notifTitle: String?,
        notifText: String?,
        now: Long = System.currentTimeMillis()
    ): List<Result> {
        val text = notifText?.trim().orEmpty()
        if (text.isEmpty()) return listOf(parse(notifTitle, text, now))

        if (text.startsWith("[")) {
            val items = (MiniJson.parse(text) as? MiniJson.Items)?.items.orEmpty()
            val results = items.mapNotNull { buildFromValue(it, notifTitle, text, now) }
            if (results.isNotEmpty()) return results
        }

        if (text.contains('{')) {
            val root = MiniJson.parse(text) as? MiniJson.Fields
            val listValue = root?.fields?.let { it["todos"] ?: it["items"] ?: it["list"] }
            val items = (listValue as? MiniJson.Items)?.items.orEmpty()
            val results = items.mapNotNull { buildFromValue(it, notifTitle, text, now) }
            if (results.isNotEmpty()) return results
        }

        val blocks = splitBlocks(text)
        if (blocks.size > 1) {
            val results = blocks.mapNotNull { block ->
                val fields = keyValueFields(block)
                val hasKey = fields.keys.any { it in KEY_TITLE || it in KEY_TIME }
                if (!hasKey) null else build(fields, "", block, now, "键值")
            }
            if (results.size > 1) return results
        }

        return listOf(parse(notifTitle, text, now))
    }

    private fun buildFromValue(
        value: MiniJson.Value,
        notifTitle: String?,
        rawText: String,
        now: Long
    ): Result? {
        val fields = MiniJson.flatScalars(value).mapKeys { it.key.trim().lowercase(Locale.CHINA) }
        if (fields.isEmpty()) return null
        if (!fields.keys.any { it in KEY_TITLE || it in KEY_TIME }) return null
        return build(fields, notifTitle.orEmpty(), rawText, now, "JSON")
    }

    /** 空行或 --- 分隔成多段。 */
    private fun splitBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            val isSeparator = trimmed.isEmpty() || trimmed == "---" || trimmed == "----" || trimmed == "==="
            if (isSeparator) {
                if (current.isNotBlank()) {
                    blocks.add(current.toString().trim())
                    current.setLength(0)
                }
            } else {
                current.append(line).append('\n')
            }
        }
        if (current.isNotBlank()) blocks.add(current.toString().trim())
        return blocks
    }

    // ---------------- 内部实现 ----------------

    private fun build(
        fields: Map<String, String>,
        notifTitle: String,
        rawText: String,
        now: Long,
        kind: String
    ): Result {
        val titleRaw = pick(fields, KEY_TITLE)?.trim()
        val timeRaw = pick(fields, KEY_TIME)
        val durationRaw = pick(fields, KEY_DURATION)
        val noteRaw = pick(fields, KEY_NOTE)
        val repeatRaw = pick(fields, KEY_REPEAT)

        val finalTitle = when {
            !titleRaw.isNullOrBlank() -> titleRaw
            notifTitle.isNotBlank() -> notifTitle
            else -> firstLine(rawText).take(30)
        }.ifBlank { "ntfy 待办" }

        val parsedTime = parseTime(timeRaw, now)
        val timeMissing = parsedTime == null
        val start = parsedTime ?: (now + FALLBACK_LEAD_MS)

        val duration = parseDuration(durationRaw) ?: DEFAULT_DURATION
        val repeat = RepeatRule.fromRaw(repeatRaw)
        val note = noteRaw?.trim()?.ifBlank { null }

        val reason = when {
            timeMissing && !timeRaw.isNullOrBlank() ->
                "$kind 解析到了标题，但时间「${timeRaw.take(20)}」认不出来，已按当前时间 +1 小时"
            timeMissing ->
                "$kind 解析到了标题，但没有时间字段，已按当前时间 +1 小时"
            titleRaw.isNullOrBlank() ->
                "没解析到标题，用了通知标题"
            else -> null
        }

        return Result(
            spec = TodoSpec(finalTitle, start, duration, note, repeat),
            fallback = reason != null,
            reason = reason,
            timeParsed = parsedTime != null
        )
    }

    private fun fallback(notifTitle: String, rawText: String, now: Long, reason: String): Result {
        val title = notifTitle
            .ifBlank { firstLine(rawText) }
            .take(40)
            .ifBlank { "ntfy 待办" }
        return Result(
            spec = TodoSpec(
                title = title,
                startMillis = now + FALLBACK_LEAD_MS,
                durationMinutes = DEFAULT_DURATION,
                note = rawText.ifBlank { null },
                repeat = RepeatRule.NONE
            ),
            fallback = true,
            reason = reason
        )
    }

    private fun pick(fields: Map<String, String>, keys: Set<String>): String? =
        fields.entries.firstOrNull { it.key in keys && it.value.isNotBlank() }?.value

    private fun jsonFields(text: String): Map<String, String> {
        val json = extractJsonObject(text) ?: return emptyMap()
        val raw = MiniJson.flatScalars(MiniJson.parse(json))
        val out = LinkedHashMap<String, String>()
        for ((key, value) in raw) {
            out[key.trim().lowercase(Locale.CHINA)] = value
        }
        return out
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun keyValueFields(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var lastKey: String? = null
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = KV_LINE.find(line)
            val key = match?.groupValues?.get(1)?.trim()?.lowercase(Locale.CHINA)
            if (match != null && key != null && key in ALL_KEYS) {
                out[key] = match.groupValues[2].trim()
                lastKey = key
            } else if (lastKey != null) {
                val previousKey = lastKey
                val prev = out[previousKey]
                out[previousKey] = if (prev.isNullOrBlank()) line else "$prev\n$line"
            }
        }
        return out
    }

    /** 支持 2026-11-08 19:00 / 2026/11/8 19:00 / 11-08 19:00 / 19:00 / 毫秒时间戳 等写法。 */
    fun parseTime(raw: String?, now: Long): Long? {
        val original = raw?.trim().orEmpty()
        if (original.isEmpty()) return null

        val s = original
            .replace('：', ':')
            .replace('／', '/')
            .replace('．', '.')
            .replace('T', ' ')
            .replace("  ", " ")
            .trim()

        if (EPOCH.matches(s)) {
            val value = s.toLongOrNull() ?: return null
            return if (s.length <= 10) value * 1000 else value
        }

        val zone = ZoneId.systemDefault()
        val nowDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)

        for (pattern in FULL_DATE_TIME) {
            try {
                val dateTime = LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.CHINA))
                return dateTime.atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // 试下一个
            }
        }

        for (pattern in FULL_DATE) {
            try {
                val date = LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.CHINA))
                return date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // 试下一个
            }
        }

        MONTH_DAY.find(s)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            if (month != null && day != null) {
                val hour = match.groupValues[3].toIntOrNull() ?: 9
                val minute = match.groupValues[4].toIntOrNull() ?: 0
                try {
                    val dateTime = LocalDateTime.of(nowDateTime.year, month, day, hour, minute)
                    return dateTime.atZone(zone).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    // 落到时间解析
                }
            }
        }

        TIME_ONLY.find(s)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull()
            val minute = match.groupValues[2].toIntOrNull()
            if (hour != null && minute != null && hour < 24 && minute < 60) {
                var dateTime = LocalDateTime.of(nowDateTime.toLocalDate(), LocalTime.of(hour, minute))
                if (dateTime.isBefore(nowDateTime)) dateTime = dateTime.plusDays(1)
                return dateTime.atZone(zone).toInstant().toEpochMilli()
            }
        }

        return null
    }

    private fun parseDuration(raw: String?): Int? {
        val s = raw?.trim()?.lowercase(Locale.CHINA) ?: return null
        if (s.isEmpty()) return null
        val number = Regex("(\\d+(?:\\.\\d+)?)").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val minutes = if (s.contains("小时") || s.contains("hour") || s.contains('h')) {
            (number * 60).toInt()
        } else {
            number.toInt()
        }
        return minutes.coerceIn(5, 24 * 60)
    }

    private fun firstLine(text: String): String =
        text.split('\n').firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
