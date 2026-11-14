package com.bellnote.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「今天」这一组的完成度，首页「今天」标题右边那根横向进度条用它。
 *
 * 口径（2026-11-14 老师定）：
 *  - 只看**今天**（按开始日期）的事件，跟分组收展无关、跟已过期组无关
 *  - 已结束 = 现在 ≥ 开始 + 时长；**进行中不算完成**
 *  - 今天一条都没有 → 不显示进度条
 */
data class DayProgress(val total: Int, val done: Int) {

    /** 0f..1f；今天没事件时是 0（界面按 [visible] 隐藏）。 */
    val ratio: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    val visible: Boolean get() = total > 0

    val allDone: Boolean get() = total > 0 && done >= total
}

object DayProgressCalc {

    /**
     * 纯函数，方便单测。
     *
     * [today] 必须能注入：默认取「当前时区的今天」，但**单测一定要显式传**，
     * 否则用例写死了日期，跨零点（或 CI 跑在别的时区）就必挂——2026-11-15 凌晨真踩过。
     */
    fun compute(
        items: List<TodoItem>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): DayProgress {
        var total = 0
        var done = 0
        for (item in items) {
            val day = Instant.ofEpochMilli(item.startMillis).atZone(zone).toLocalDate()
            if (day != today) continue
            total++
            val end = item.startMillis + item.durationMinutes * 60_000L
            if (now >= end) done++
        }
        return DayProgress(total, done)
    }
}
