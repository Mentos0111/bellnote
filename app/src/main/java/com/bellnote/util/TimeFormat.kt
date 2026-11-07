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
}
