package com.bellnote.parser

import com.bellnote.data.PARSED_DEFAULT_DURATION_MINUTES
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
    /** 一个时间点：前缀（可选）+ 小时 + 分隔符（: / 点 / 时）+ 分钟（可选，可为「半」）。 */
    private val TIME_TOKEN = Regex(
        "(上午|早上|早晨|凌晨|中午|下午|傍晚|晚上|夜里|晚)?\\s*(\\d{1,2})\\s*[:：点时]\\s*(半|\\d{1,2})?\\s*分?"
    )

    /** 起止之间只允许出现这些连接符：「九点五十到十一点二十五」「9:50-11:25」。 */
    private val RANGE_SEPARATOR = Regex("^(到|至|~|～|-|—|－|–)$")

    /** 中文数字（时间用，最大到 59）。 */
    private const val CN_NUMBER_CHARS = "零〇一二两三四五六七八九十"

    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
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
        var duration = PARSED_DEFAULT_DURATION_MINUTES
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

        // 时间：先把所有时间点找出来，「起点 + 连接符 + 终点」就连成一段区间
        val times = TIME_TOKEN.findAll(rest).mapNotNull { match ->
            val rawHour = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val minute = minuteOf(match.groupValues[3])
            if (minute !in 0..59) return@mapNotNull null
            TimePoint(to24(match.groupValues[1], rawHour), minute, match)
        }.toList()
        if (times.isEmpty()) return null

        var startHour = times[0].hour
        var startMinute = times[0].minute
        var timeMatch: IntRange? = times[0].match.range
        for (i in 0 until times.size - 1) {
            val from = times[i]
            val to = times[i + 1]
            val between = rest.substring(from.match.range.last + 1, to.match.range.first).trim()
            if (!RANGE_SEPARATOR.matches(between)) continue
            // 「下午两点到四点半」：终点没写上午/下午，跟着起点的下午走；
            // 但「22:00 到 01:00」这种跨零点的，加 12 小时反而更早，就不加
            val samePeriod = to.hour < 12 && from.hour >= 12 &&
                (to.hour + 12) * 60 + to.minute > from.hour * 60 + from.minute
            val endHour = if (to.match.groupValues[1].isBlank() && samePeriod) to.hour + 12 else to.hour
            if (endHour !in 0..23) continue
            var span = (endHour * 60 + to.minute) - (from.hour * 60 + from.minute)
            if (span <= 0) span += 24 * 60 // 跨零点，比如 22:00 到 01:00
            duration = span.coerceIn(5, 24 * 60)
            startHour = from.hour
            startMinute = from.minute
            timeMatch = from.match.range.first..to.match.range.last
            break
        }
        val h = startHour
        if (h !in 0..23 || startMinute !in 0..59) return null
        timeMatch?.let { rest = rest.removeRange(it) }

        val baseDate = date ?: nowDate.toLocalDate()
        var start: LocalDateTime
        try {
            start = LocalDateTime.of(baseDate, LocalTime.of(h, startMinute))
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

    /**
     * 把时间里的中文数字换成阿拉伯数字，后面正则就只认数字。
     * 只动「点/时」前后那一段：标题里的「第三章」「一次」不会被改坏。
     */
    private fun normalizeNumbers(text: String): String {
        // 「二十三点」「十一点」「九点」：点/时前面的小时
        var out = Regex("[$CN_NUMBER_CHARS]{1,3}(?=\\s*[点时])").replace(text) { match ->
            cnToInt(match.value)?.toString() ?: match.value
        }
        // 「九点五十」「十一点二十五分」：点/时后面的分钟（后面可能直接跟「到」「-」或句末）
        out = Regex(
            "(?<=[点时])(\\s*)([$CN_NUMBER_CHARS]{1,3})" +
                "(?=\\s*(?:分|到|至|~|～|-|—|－|–|$|[，,。；;、]))"
        ).replace(out) { match ->
            val number = cnToInt(match.groupValues[2])?.toString() ?: match.groupValues[2]
            match.groupValues[1] + number
        }
        return out
    }

    /** 中文读数 → 数字：五、十、十五、二十五、五十、五十九。认不出返回 null。 */
    private fun cnToInt(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.length == 1) {
            return if (text == "十") 10 else CN_DIGITS[text[0]]
        }
        val ten = text.indexOf('十')
        if (ten < 0) {
            // 「一二」这种逐位写法少见，按两位数拼
            var value = 0
            for (c in text) {
                val digit = CN_DIGITS[c] ?: return null
                value = value * 10 + digit
            }
            return value
        }
        val tens = if (ten == 0) 1 else CN_DIGITS[text.substring(0, ten)[0]] ?: return null
        val ones = if (ten == text.lastIndex) 0 else {
            if (text.substring(ten + 1).length != 1) 0 else CN_DIGITS[text.last()] ?: return null
        }
        return tens * 10 + ones
    }

    /** 「半」是 30 分，空着是 0 分。 */
    private fun minuteOf(raw: String): Int = when {
        raw == "半" -> 30
        raw.isNotEmpty() -> raw.toIntOrNull() ?: 0
        else -> 0
    }

    /** 一个识别出来的时间点。 */
    private data class TimePoint(val hour: Int, val minute: Int, val match: MatchResult)

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
