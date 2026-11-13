package com.bellnote.parser

import com.bellnote.data.TodoSpec
import java.util.Locale

/**
 * 「一整段屏幕文字 → 若干条待办」。
 *
 * 和 [QuickParse] 的分工：QuickParse 处理「用户自己说的一句话」，干净、只需一次；
 * 这里处理 OCR / 读屏拿到的一坨文本——里面有标题栏、时间戳、聊天昵称、按钮文字，
 * 得先按行筛一遍，只留下「像一件事、且能认出时间」的行。
 *
 * 刻意不用大模型：这一步是纯规则，快、不要钱、断网也能跑，结果也更容易解释。
 */
object ScreenTextParser {

    /** 这些行基本是界面噪音，直接丢。 */
    private val NOISE = listOf(
        "微信", "通讯录", "发现", "我", "返回", "取消", "确定", "发送", "搜索",
        "更多", "设置", "关闭", "全部", "加载中", "正在输入", "按住 说话"
    )

    private val PURE_TIME = Regex("^\\d{1,2}[:：]\\d{2}$")
    private val PURE_DATE = Regex("^\\d{1,2}[-/月]\\d{1,2}日?$")
    private val BADGE = Regex("^\\[\\d+条\\]|^\\d+条$")

    /**
     * 只有「时间 / 日期」、没有内容的行。
     *
     * 截图里这两类信息经常不在同一行：聊天列表是「11月14日 周六」占一行、「20:30」占一行、
     * 「化学课」再占一行。先把时间片剔出来，等遇到内容行再拼回去判。
     */
    private val TIME_TOKENS = listOf(
        Regex("\\d{4}\\s*[-/年]\\s*\\d{1,2}\\s*[-/月]\\s*\\d{1,2}\\s*[日号]?"),
        Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]"),
        Regex("\\d{1,2}[-/]\\d{1,2}"),
        Regex("\\d{1,2}[:：]\\d{2}"),
        Regex("(这|本|下)?(周|星期|礼拜)[一二三四五六日天]"),
        Regex("(今|明|后|大后)(天|日|晚|早)")
    )
    private val LEFT_OVER = Regex("[\\s,，.。:：/\\-—~、|·()（）\\[\\]【】]+")

    data class Line(val raw: String, val spec: TodoSpec)

    /**
     * @param maxItems 最多返回几条，防止一张密集截图解析出十几条噪音
     */
    fun parse(
        text: String,
        now: Long = System.currentTimeMillis(),
        maxItems: Int = 5
    ): List<TodoSpec> = scan(text, now, maxItems).map { it.spec }

    /** 同 [parse]，但把命中的原始行一起带出来，方便在界面上说清「这条是从哪句话来的」。 */
    fun scan(
        text: String,
        now: Long = System.currentTimeMillis(),
        maxItems: Int = 5
    ): List<Line> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines().map { clean(it) }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        val result = mutableListOf<Line>()
        val seen = mutableSetOf<String>()

        for (i in lines.indices) {
            val line = lines[i]
            // 内容行可能只有两三个字（「化学课」），时间在旁边的行里，别按长度先筛掉
            if (line.length < 2) continue
            if (isNoise(line)) continue
            // 时间、日期单独成行：留给旁边的内容行拼，自己不是待办
            if (isTimeOnly(line)) continue

            val parsed = QuickParse.parse(joinNearbyTimes(lines, i), now)
                ?: QuickParse.parse(line, now)
                ?: continue
            val title = parsed.title.trim()
            if (title.length < 2) continue
            // 只靠时间戳撑起来的行（"23:47"）没意义，标题得是字
            if (title.all { it.isDigit() || it in ":：-/. " }) continue
            if (isTimeOnly(title)) continue

            val key = title.lowercase(Locale.CHINA)
            if (!seen.add(key)) continue

            result.add(
                Line(
                    raw = line,
                    spec = TodoSpec(
                        title = title.take(20),
                        startMillis = parsed.startMillis,
                        durationMinutes = parsed.durationMinutes,
                        note = if (line.length > title.length) line else null
                    )
                )
            )
            if (result.size >= maxItems) break
        }
        return result
    }

    /**
     * 把紧挨着的「纯时间」行拼到内容行上（前后各最多两行）。
     *
     * 只拼纯时间片，不拼别的正文：一张密集截图里，两行正文永远不该合成一条待办。
     */
    private fun joinNearbyTimes(lines: List<String>, index: Int): String {
        val before = mutableListOf<String>()
        var i = index - 1
        while (i >= 0 && before.size < 2 && isTimeOnly(lines[i])) {
            before.add(0, lines[i])
            i--
        }
        val after = mutableListOf<String>()
        var j = index + 1
        while (j < lines.size && after.size < 2 && isTimeOnly(lines[j])) {
            after.add(lines[j])
            j++
        }
        if (before.isEmpty() && after.isEmpty()) return lines[index]
        return (before + lines[index] + after).joinToString(" ")
    }

    /** 这一行除了时间/日期什么都没有？ */
    private fun isTimeOnly(line: String): Boolean {
        var rest = line
        for (token in TIME_TOKENS) {
            rest = rest.replace(token, " ")
        }
        rest = rest.replace(LEFT_OVER, "")
        return rest.isEmpty()
    }

    /**
     * 一行都解析不出时间时，给一个「兜底待办」：把最像内容的那行当标题，
     * 时间放到明天 9:00，让用户进编辑页改——总比什么都不给强。
     */
    fun fallbackSpec(
        text: String,
        now: Long = System.currentTimeMillis()
    ): TodoSpec? {
        val candidate = text.lines()
            .map { clean(it) }
            .filter { it.length in 4..40 && !isNoise(it) && !isTimeOnly(it) }
            .maxByOrNull { it.length }
            ?: return null
        val tomorrow9 = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(1)
            .atTime(9, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return TodoSpec(
            title = candidate.take(20),
            startMillis = tomorrow9,
            note = candidate.takeIf { it.length > 20 }
        )
    }

    private fun clean(raw: String): String = raw.trim()
        .replace(Regex("^[\\[|【（(]"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private fun isNoise(line: String): Boolean {
        if (line in NOISE) return true
        if (PURE_TIME.matches(line) || PURE_DATE.matches(line)) return true
        if (BADGE.containsMatchIn(line)) return true
        // 全是标点/符号，没有汉字或字母
        if (line.none { it.isLetter() }) return true
        return false
    }
}
