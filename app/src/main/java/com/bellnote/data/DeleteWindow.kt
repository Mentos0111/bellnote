package com.bellnote.data

/**
 * 删除指令的「时间」怎么算命中。
 *
 * 文档承诺的是「标题一致 + 时间一致」，所以不能像早先那样按「同一天」比 ——
 * 那一天同名但不同时段的课会被一起删掉。这里给一个 30 分钟窗口：
 * 正常情况下 App 写进去的时间就是消息里那个时间，窗口只是容错（时区/进位/手改几分钟）。
 * 不给时间时仍然只按标题删（同名全删），这是文档里写明的行为。
 */
object DeleteWindow {

    val WINDOW_MS: Long = 30L * 60_000L

    fun matches(eventStartMillis: Long, targetMillis: Long?): Boolean {
        if (targetMillis == null) return true
        return kotlin.math.abs(eventStartMillis - targetMillis) <= WINDOW_MS
    }

    fun rangeOf(targetMillis: Long): LongRange =
        (targetMillis - WINDOW_MS)..(targetMillis + WINDOW_MS)
}
