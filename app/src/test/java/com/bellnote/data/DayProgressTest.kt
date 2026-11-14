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

    @Test
    fun `今天没事件时整条隐藏`() {
        val p = DayProgressCalc.compute(listOf(item(at(15, 9))), now(10), zone)
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
        val p = DayProgressCalc.compute(items, now(23), zone)
        assertEquals(1, p.total)
        assertEquals(1, p.done)
    }

    @Test
    fun `已结束算完成 进行中不算`() {
        val items = listOf(
            item(at(14, 9), 60),    // 09:00-10:00 已结束
            item(at(14, 22), 120)   // 22:00-24:00 进行中
        )
        val p = DayProgressCalc.compute(items, now(23), zone)
        assertEquals(2, p.total)
        assertEquals(1, p.done)
        assertEquals(0.5f, p.ratio, 0.001f)
    }

    @Test
    fun `全部结束才算走满`() {
        val items = listOf(item(at(14, 9), 30), item(at(14, 12), 30))
        val p = DayProgressCalc.compute(items, now(23), zone)
        assertTrue(p.allDone)
        assertEquals(1f, p.ratio, 0.001f)
    }

    @Test
    fun `还没开始的事件计入分母但不计入完成`() {
        val items = listOf(item(at(14, 9), 30), item(at(14, 23), 30))
        val p = DayProgressCalc.compute(items, now(20), zone)
        assertEquals(2, p.total)
        assertEquals(1, p.done)
        assertFalse(p.allDone)
    }

    @Test
    fun `迷你地图按时间升序给每段状态`() {
        val items = listOf(
            item(at(15, 9)),   // 明天
            item(at(14, 9), 30),   // 今天早上 已结束
            item(at(14, 22), 120)  // 今天 进行中
        )
        val segs = DayProgressCalc.segments(items, now(23))
        assertEquals(3, segs.size)
        assertEquals(DayProgressCalc.Segment.DONE, segs[0])
        assertEquals(DayProgressCalc.Segment.RUNNING, segs[1])
        assertEquals(DayProgressCalc.Segment.FUTURE, segs[2])
    }

    @Test
    fun `今天刻度的位置按事件序号算`() {
        val items = listOf(item(at(13, 9)), item(at(14, 9)), item(at(15, 9)), item(at(16, 9)))
        // 今天那条是第 2 条（索引 1），共 4 条 → 0.25
        assertEquals(0.25f, DayProgressCalc.todayAnchor(items, now(12), zone)!!, 0.001f)
    }

    @Test
    fun `今天没事件时没有刻度`() {
        val items = listOf(item(at(15, 9)), item(at(16, 9)))
        assertEquals(null, DayProgressCalc.todayAnchor(items, now(12), zone))
    }
}
