package com.bellnote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DayGroupsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)

    private fun millisOf(y: Int, m: Int, d: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(y, m, d, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ---- 哪一天属于哪一组 ----

    @Test
    fun 今天的凌晨也算今天() {
        // 02:42 早就过去了，但仍然属于「今天」，不能掉进「已过期」
        val label = DayGroups.label(millisOf(2026, 11, 13, 2, 42), zone, today)
        assertEquals(DayGroups.TODAY, label)
    }

    @Test
    fun 今天任何时刻都不算过期() {
        val labels = listOf(0, 1, 12, 23).map { hour ->
            DayGroups.label(millisOf(2026, 11, 13, hour, 59), zone, today)
        }
        assertEquals(listOf(DayGroups.TODAY, DayGroups.TODAY, DayGroups.TODAY, DayGroups.TODAY), labels)
    }

    @Test
    fun 昨天才是已过期() {
        assertEquals(
            DayGroups.EXPIRED,
            DayGroups.label(millisOf(2026, 11, 12, 23, 59), zone, today)
        )
    }

    @Test
    fun 明天后天和更远的日子() {
        assertEquals(DayGroups.TOMORROW, DayGroups.label(millisOf(2026, 11, 14, 20, 30), zone, today))
        assertEquals(DayGroups.DAY_AFTER, DayGroups.label(millisOf(2026, 11, 15, 9, 0), zone, today))
        assertEquals("11 月 17 日", DayGroups.label(millisOf(2026, 11, 17, 20, 30), zone, today))
        assertEquals("12 月 1 日", DayGroups.label(millisOf(2026, 12, 1, 8, 0), zone, today))
    }

    @Test
    fun 跨年也对() {
        assertEquals(
            "1 月 5 日",
            DayGroups.label(millisOf(2027, 1, 5, 8, 0), zone, LocalDate.of(2026, 12, 31))
        )
        // 12 月 31 日往后两天是 1 月 2 日，正好是「后天」
        assertEquals(
            DayGroups.DAY_AFTER,
            DayGroups.label(millisOf(2027, 1, 2, 8, 0), zone, LocalDate.of(2026, 12, 31))
        )
    }

    // ---- 默认展开 / 收起 ----

    @Test
    fun 默认全展开() {
        assertFalse(DayGroups.isCollapsed(DayGroups.TODAY, emptySet(), emptySet()))
        assertFalse(DayGroups.isCollapsed(DayGroups.TOMORROW, emptySet(), emptySet()))
        assertFalse(DayGroups.isCollapsed(DayGroups.EXPIRED, emptySet(), emptySet()))
        assertFalse(DayGroups.isCollapsed(DayGroups.DAY_AFTER, emptySet(), emptySet()))
        assertFalse(DayGroups.isCollapsed("11 月 20 日", emptySet(), emptySet()))
    }

    @Test
    fun 用户自己收起了今天也照办() {
        assertTrue(DayGroups.isCollapsed(DayGroups.TODAY, setOf(DayGroups.TODAY), emptySet()))
    }

    @Test
    fun 用户展开过已过期就记住() {
        assertFalse(DayGroups.isCollapsed(DayGroups.EXPIRED, emptySet(), setOf(DayGroups.EXPIRED)))
    }

    @Test
    fun 两边都记着时以收起为准() {
        assertTrue(
            DayGroups.isCollapsed(
                DayGroups.TODAY,
                setOf(DayGroups.TODAY),
                setOf(DayGroups.TODAY)
            )
        )
    }
}
