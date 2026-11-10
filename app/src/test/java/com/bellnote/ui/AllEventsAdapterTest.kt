package com.bellnote.ui

import com.bellnote.data.CalendarRepository.EventInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AllEventsAdapterTest {

    private fun event(title: String, startMillis: Long) = EventInfo(
        id = startMillis,
        title = title,
        startMillis = startMillis,
        endMillis = startMillis + 30 * 60_000L,
        calendarId = 1L,
        calendarName = "待办",
        isOwn = false,
        allDay = false,
        note = null
    )

    private fun at(day: Int, hour: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `同一天的事件只在最前面插一个日期标题`() {
        val rows = AllEventsAdapter.buildRows(
            listOf(
                event("A", at(15, 9)),
                event("B", at(15, 14)),
                event("C", at(16, 8))
            )
        )
        // 1 个标题 + 2 条 + 1 个标题 + 1 条
        assertTrue("行数应为 5，实际 ${rows.size}", rows.size == 5)
        assertTrue(rows[0] is AllEventsAdapter.Row.Header)
        assertTrue(rows[3] is AllEventsAdapter.Row.Header)
    }

    @Test
    fun `乱序输入会先按时间排序，标题跟着第一个事件`() {
        val rows = AllEventsAdapter.buildRows(
            listOf(
                event("晚", at(17, 20)),
                event("早", at(15, 8))
            )
        )
        assertTrue(rows.size == 4)
        val first = rows[1] as AllEventsAdapter.Row.Event
        assertTrue("最早的一条应排第一，实际 ${first.info.title}", first.info.title == "早")
    }

    @Test
    fun `没有事件时列表为空`() {
        assertTrue(AllEventsAdapter.buildRows(emptyList()).isEmpty())
    }
}
