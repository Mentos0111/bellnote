package com.bellnote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DayProgressTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 用例里的「今天」是写死的，所以必须显式传给 compute——不然跨零点就挂（2026-11-15 真踩过）。 */
    private val today: LocalDate = LocalDate.of(2026, 11, 14)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun item(start: Long, minutes: Int = 60, id: Long = start) = TodoItem(
        id = id,
        title = "测试",
        startMillis = start,
        durationMinutes = minutes,
        note = null,
        repeat = RepeatRule.NONE,
        calendarId = 1L
    )

    private fun now(hour: Int, minute: Int = 0): Long = at(14, hour, minute)

    private fun calc(items: List<TodoItem>, now: Long) =
        DayProgressCalc.compute(items, now, zone, today)

    @Test
    fun `今天没事件时整条隐藏`() {
        val p = calc(listOf(item(at(15, 9))), now(10))
        assertFalse(p.visible)
        assertEquals(0f, p.ratio, 0.001f)
    }

    @Test
    fun `只算今天的事件`() {
        val items = listOf(
            item(at(13, 9)),   // 昨天
            item(at(14, 8)),   // 今天
            item(at(15, 9))    // 明天
        )
        val p = calc(items, now(23))
        assertEquals(1, p.total)
        assertEquals(1, p.done)
    }

    @Test
    fun `已结束算完成 进行中不算`() {
        val items = listOf(
            item(at(14, 9), 60),    // 09:00-10:00 已结束
            item(at(14, 22), 120)   // 22:00-24:00 进行中
        )
        val p = calc(items, now(23))
        assertEquals(2, p.total)
        assertEquals(1, p.done)
        assertEquals(0.5f, p.ratio, 0.001f)
    }

    @Test
    fun `全部结束才算走满`() {
        val items = listOf(item(at(14, 9), 30), item(at(14, 12), 30))
        val p = calc(items, now(23))
        assertTrue(p.allDone)
        assertEquals(1f, p.ratio, 0.001f)
    }

    @Test
    fun `还没开始的事件计入分母但不计入完成`() {
        val items = listOf(item(at(14, 9), 30), item(at(14, 23), 30))
        val p = calc(items, now(20))
        assertEquals(2, p.total)
        assertEquals(1, p.done)
        assertFalse(p.allDone)
    }
}
