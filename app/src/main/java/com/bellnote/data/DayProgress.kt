package com.bellnote.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页最左侧那根「今日总进度」竖条要画多少。
 *
 * 口径（2026-11-14 老师定）：
 *  - 只看**今天**（按开始日期）的事件，跟分组收展无关、跟已过期组无关
 *  - 已结束 = 现在 ≥ 开始 + 时长；**进行中不算完成**
 *  - 今天一条都没有 → 整条隐藏
 */
data class DayProgress(val total: Int, val done: Int) {

    /** 0f..1f；今天没事件时是 0（界面按 [visible] 隐藏）。 */
    val ratio: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    val visible: Boolean get() = total > 0

    val allDone: Boolean get() = total > 0 && done >= total
}

object DayProgressCalc {

    /** 纯函数，方便单测。 */
    fun compute(
        items: List<TodoItem>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): DayProgress {
        val today = LocalDate.now(zone)
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

    /** 迷你地图里一段的状态。 */
    enum class Segment { DONE, RUNNING, FUTURE }

    /**
     * 整份日程的迷你地图：按列表顺序（开始时间升序）给每条事件一段，等分高度。
     * 返回空表示没有事件（整条隐藏）。
     */
    fun segments(items: List<TodoItem>, now: Long): List<Segment> =
        items.sortedBy { it.startMillis }.map { item ->
            val end = item.startMillis + item.durationMinutes * 60_000L
            when {
                now >= end -> Segment.DONE
                now >= item.startMillis -> Segment.RUNNING
                else -> Segment.FUTURE
            }
        }

    /** 「今天」在迷你地图上的位置（0f..1f，按事件序号等分）；今天没有事件返回 null。 */
    fun todayAnchor(
        items: List<TodoItem>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Float? {
        val sorted = items.sortedBy { it.startMillis }
        if (sorted.isEmpty()) return null
        val today = LocalDate.now(zone)
        val index = sorted.indexOfFirst {
            Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == today
        }
        if (index < 0) return null
        return index.toFloat() / sorted.size
    }
}
