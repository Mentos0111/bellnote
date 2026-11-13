package com.bellnote.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页列表按「天」分组。
 *
 * 分组只看**开始那天的日期**，不看具体钟点：
 *  - 今天以前的日期 → 「已过期」
 *  - 今天 / 明天 / 后天 → 同名标题
 *  - 更远的日期 → 「M 月 d 日」
 *
 * 所以今天发生的待办，哪怕时间已经过去（比如凌晨 2:42 那条），也永远留在「今天」，
 * 不会掉进「已过期」——「已过期」只装今天之前的日子。
 */
object DayGroups {

    const val EXPIRED = "已过期"
    const val TODAY = "今天"
    const val TOMORROW = "明天"
    const val DAY_AFTER = "后天"

    /** 打开首页默认展开这两组：今天要先看到，明天要提前知道；其余默认收起。 */
    val DEFAULT_EXPANDED: Set<String> = setOf(TODAY, TOMORROW)

    /** 某条待办属于哪一组。today 可注入，方便单测。 */
    fun label(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return when {
            date.isBefore(today) -> EXPIRED
            date == today -> TODAY
            date == today.plusDays(1) -> TOMORROW
            date == today.plusDays(2) -> DAY_AFTER
            else -> "${date.monthValue} 月 ${date.dayOfMonth} 日"
        }
    }

    /**
     * 这一组收没收起：用户自己点过的优先；没点过的按默认——
     * 「今天」「明天」展开，其它（含已过期、后天、具体日期）收起。
     */
    fun isCollapsed(
        label: String,
        collapsed: Set<String>,
        expanded: Set<String>
    ): Boolean = when {
        collapsed.contains(label) -> true
        expanded.contains(label) -> false
        else -> label !in DEFAULT_EXPANDED
    }
}
