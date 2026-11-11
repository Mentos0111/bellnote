package com.bellnote.parser

import com.bellnote.data.MANUAL_DEFAULT_DURATION_MINUTES
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * 「一句话记一条」：本地把「明天下午三点交作业」这种话拆成标题 + 时间 + 时长。
 *
 * 刻意不调大模型：这句话几乎都是固定套路，本地规则又快又不要钱，也不怕断网。
 * 认不出时间就返回 null，调用方把原文当标题、让用户在编辑页点时间。
 */
object QuickParse {

    data class Result(
        val title: String,
        val startMillis: Long,
        val durationMinutes: Int
    )

    private val DAY_WORDS = linkedMapOf(
        "大后天" to 3,
        "后天" to 2,
        "明天" to 1,
        "明日" to 1,
        "明早" to 1,
        "明晚" to 1,
        "今天" to 0,
        "今日" to 0,
        "今晚" to 0,
        "今早" to 0
    )

    private val WEEKDAY = Regex("(这|本|下)?(?:周|星期|礼拜)([一二三四五六日天])")
    private val MONTH_DAY = Regex("(?:(\\d{1,2})\\s*月\\s*)?(\\d{1,2})\\s*[日号]")
    private val DURATION = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:个)?\\s*(小时|钟头)|(\\d{1,3})\\s*分钟")
    private val RELATIVE = Regex("(\\d{1,2}|半)\\s*(?:个)?\\s*(小时|钟头|分钟)\\s*(?:之?后|以后)")
    private val TIME_COLON = Regex("(上午|早上|早晨|凌晨|中午|下午|傍晚|晚上|夜里|晚)?\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})")
    private val TIME_CN = Regex("(上午|早上|早晨|凌晨|中午|下午|傍晚|晚上|夜里|晚)?\\s*(\\d{1,2})\\s*[点时](?:\\s*(半|\\d{1,2}))?\\s*分?")
    private val CN_NUMBER = Regex("([一二三四五六七八九十]{1,3})\\s*(?=[点时])")
    private val CN_DIGITS = mapOf(
        "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
        "十一" to 11, "十二" to 12
    )
    private val WEEKDAY_MAP = mapOf(
        "一" to DayOfWeek.MONDAY, "二" to DayOfWeek.TUESDAY, "三" to DayOfWeek.WEDNESDAY,
        "四" to DayOfWeek.THURSDAY, "五" to DayOfWeek.FRIDAY, "六" to DayOfWeek.SATURDAY,
        "日" to DayOfWeek.SUNDAY, "天" to DayOfWeek.SUNDAY
    )

    /** 标题开头这些废话剪掉更好看。 */
    private val TITLE_NOISE = listOf("提醒我", "记得", "帮我", "给我", "我要", "需要", "安排")

    fun parse(
        input: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Result? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        var rest = normalizeNumbers(raw)

        // 「2 小时后」这种相对说法：不需要日期。
        // 必须先于时长判断 —— 「2小时后」里的「2小时」不是时长。
        var duration = MANUAL_DEFAULT_DURATION_MINUTES
        RELATIVE.find(rest)?.let { match ->
            val value = if (match.groupValues[1] == "半") 0.5 else match.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = match.groupValues[2]
            val minutes = if (unit.startsWith("小时") || unit == "钟头") (value * 60).toInt() else value.toInt()
            val title = cleanTitle(rest.removeRange(match.range), raw)
            return if (title == null) null else Result(title, now + minutes * 60_000L, duration)
        }

        DURATION.find(rest)?.let { match ->
            val hours = match.groupValues[1].toDoubleOrNull()
            val minutes = match.groupValues[3].toIntOrNull()
            duration = if (hours != null) (hours * 60).toInt() else (minutes ?: duration)
            duration = duration.coerceIn(5, 24 * 60)
            rest = rest.removeRange(match.range)
        }

        val nowDate = Instant.ofEpochMilli(now).atZone(zone)

        // 日期
        var date: LocalDate? = null
        var dateExplicit = false
        WEEKDAY.find(rest)?.let { match ->
            val prefix = match.groupValues[1]
            val target = WEEKDAY_MAP[match.groupValues[2]] ?: DayOfWeek.MONDAY
            var candidate = nowDate.toLocalDate()
            while (candidate.dayOfWeek != target) candidate = candidate.plusDays(1)
            if (prefix == "下") candidate = candidate.plusWeeks(1)
            date = candidate
            dateExplicit = true
            rest = rest.removeRange(match.range)
        } ?: run {
            MONTH_DAY.find(rest)?.let { match ->
                val month = match.groupValues[1].toIntOrNull()
                val day = match.groupValues[2].toIntOrNull()
                if (day != null && day in 1..31) {
                    val m = (month ?: nowDate.monthValue).coerceIn(1, 12)
                    runCatching { LocalDate.of(nowDate.year, m, day) }.getOrNull()?.let {
                        date = it
                        dateExplicit = true
                        rest = rest.removeRange(match.range)
                    }
                }
            }
            if (date == null) {
                for ((word, plus) in DAY_WORDS) {
                    if (rest.contains(word)) {
                        date = nowDate.toLocalDate().plusDays(plus.toLong())
                        dateExplicit = true
                        rest = rest.replace(word, " ")
                        break
                    }
                }
            }
        }

        // 时间
        var hour: Int? = null
        var minute = 0
        var timeMatch: IntRange? = null
        TIME_COLON.find(rest)?.let { match ->
            hour = to24(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 0)
            minute = match.groupValues[3].toIntOrNull() ?: 0
            timeMatch = match.range
        }
        if (hour == null) {
            TIME_CN.find(rest)?.let { match ->
                val h = match.groupValues[2].toIntOrNull()
                if (h != null && h in 0..24) {
                    hour = to24(match.groupValues[1], h)
                    minute = when {
                        match.groupValues[3] == "半" -> 30
                        match.groupValues[3].isNotEmpty() -> match.groupValues[3].toIntOrNull() ?: 0
                        else -> 0
                    }
                    timeMatch = match.range
                }
            }
        }
        val h = hour ?: return null
        if (h !in 0..23 || minute !in 0..59) return null
        timeMatch?.let { rest = rest.removeRange(it) }

        val baseDate = date ?: nowDate.toLocalDate()
        var start: LocalDateTime
        try {
            start = LocalDateTime.of(baseDate, LocalTime.of(h, minute))
        } catch (e: Exception) {
            return null
        }

        // 只说了「三点」没说哪天：今天已过就顺延到明天
        if (start.isBefore(nowDate.toLocalDateTime())) {
            start = when {
                !dateExplicit -> start.plusDays(1)
                baseDate == nowDate.toLocalDate() -> start.plusDays(1)
                else -> start.plusYears(1)
            }
        }

        val title = cleanTitle(rest, raw) ?: return null
        return Result(
            title = title,
            startMillis = start.atZone(zone).toInstant().toEpochMilli(),
            durationMinutes = duration
        )
    }

    /** 把「三点」「十二点」里的中文数字换成阿拉伯数字，后面正则就只认数字。 */
    private fun normalizeNumbers(text: String): String {
        var out = text
        for ((cn, value) in CN_DIGITS.entries.sortedByDescending { it.key.length }) {
            out = out.replace("${cn}点", "${value}点").replace("${cn}时", "${value}时")
        }
        return out
    }

    private fun to24(prefix: String, hour: Int): Int = when {
        prefix.isBlank() -> hour
        prefix == "中午" -> if (hour in 1..5) hour + 12 else 12
        prefix in setOf("下午", "傍晚", "晚上", "夜里", "晚") -> if (hour in 0..11) hour + 12 else hour
        prefix == "凌晨" -> if (hour == 12) 0 else hour
        else -> hour
    }

    private fun cleanTitle(rest: String, raw: String): String? {
        var text = rest
        for (noise in TITLE_NOISE) {
            text = text.replace(noise, " ")
        }
        text = text
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('，', ',', '。', '.', '、', '；', ';', '：', ':', ' ', '的')
        if (text.isBlank()) return null
        return text.take(40)
    }

    /** 给「分享文本」用：整段里可能有多行，取第一行能解析出时间的。 */
    fun parseFirstLine(
        input: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Result? {
        for (line in input.lines()) {
            val trimmed = line.trim()
            if (trimmed.length < 2) continue
            parse(trimmed, now, zone)?.let { return it }
        }
        return null
    }
}
