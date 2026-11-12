package com.bellnote.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFormat {

    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** 卡片左边那一列只要钟点：20:30。 */
    fun clock(millis: Long): String = LocalDateTime
        .ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(CLOCK)

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

    /** 卡片上那一行：同年只写 11-08 09:00，跨年才带年份，省横向空间。 */
    fun compact(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochMilli(millis).atZone(zone)
        val pattern =
            if (time.year == java.time.LocalDate.now().year) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm"
        return time.format(DateTimeFormatter.ofPattern(pattern))
    }

}
