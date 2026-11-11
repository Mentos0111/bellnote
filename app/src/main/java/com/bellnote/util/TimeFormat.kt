package com.bellnote.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFormat {

    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun full(millis: Long): String = LocalDateTime
        .ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(FULL)

    fun monthDay(millis: Long): String = LocalDateTime
        .ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(MONTH_DAY)

    /** 分组用的日期键：yyyy-MM-dd（按本地时区），跨天判断靠它。 */
    fun dayKey(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    /** 列表里的日期标题：今天 / 明天 / 后天 / 11 月 15 日 周六。 */
    fun dayLabel(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = java.time.LocalDate.now()
        return when (date) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.plusDays(2) -> "后天"
            else -> date.format(DAY)
        }
    }

    private val DAY = DateTimeFormatter.ofPattern("M 月 d 日 E", java.util.Locale.CHINA)
    private val MONTH_DAY_WEEK = DateTimeFormatter.ofPattern("M 月 d 日 EEEE", java.util.Locale.CHINA)

    /** 首页顶栏居中那行：短一点，给左右两边留位置。 */
    fun headerLine(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(HEADER)

    private val HEADER = DateTimeFormatter.ofPattern("M 月 d 日 · E", java.util.Locale.CHINA)

    /** 首页顶部那行日期：11 月 12 日 周四（今天会加「今天 ·」前缀）。 */
    fun todayLine(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val text = date.format(MONTH_DAY_WEEK)
        return if (date == java.time.LocalDate.now()) "今天 · $text" else text
    }
}
