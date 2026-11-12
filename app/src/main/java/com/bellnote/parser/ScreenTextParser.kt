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
        val result = mutableListOf<Line>()
        val seen = mutableSetOf<String>()

        for (raw in text.lines()) {
            val line = clean(raw)
            if (line.length < 4) continue
            if (isNoise(line)) continue

            val parsed = QuickParse.parse(line, now) ?: continue
            val title = parsed.title.trim()
            if (title.length < 2) continue
            // 只靠时间戳撑起来的行（"23:47"）没意义，标题得是字
            if (title.all { it.isDigit() || it in ":：-/. " }) continue

            val key = title.lowercase(Locale.CHINA)
            if (!seen.add(key)) continue

            result.add(
                Line(
                    raw = raw.trim(),
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
     * 一行都解析不出时间时，给一个「兜底待办」：把最像内容的那行当标题，
     * 时间放到明天 9:00，让用户进编辑页改——总比什么都不给强。
     */
    fun fallbackSpec(
        text: String,
        now: Long = System.currentTimeMillis()
    ): TodoSpec? {
        val candidate = text.lines()
            .map { clean(it) }
            .filter { it.length in 4..40 && !isNoise(it) && !PURE_TIME.matches(it) }
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
