package com.bellnote.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 「一句话记一条」的本地解析。基准时间固定成 2026-11-11（周三）23:00（北京时间）。 */
class QuickParseTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Long = LocalDateTime.of(2026, 11, 11, 23, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private fun parsed(text: String): QuickParse.Result? = QuickParse.parse(text, now, zone)

    private fun timeOf(text: String): String {
        val result = parsed(text)
        assertNotNull("应该能解析：$text", result)
        return LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(result!!.startMillis), zone
        ).toString()
    }

    @Test
    fun `明天下午三点交作业`() {
        val result = parsed("明天下午三点交作业")
        assertNotNull(result)
        assertEquals("交作业", result!!.title)
        assertEquals("2026-11-12T15:00", timeOf("明天下午三点交作业"))
        assertEquals(60, result.durationMinutes)
    }

    @Test
    fun `后天晚8点开会`() {
        assertEquals("2026-11-13T20:00", timeOf("后天晚8点开会"))
    }

    @Test
    fun `明早八点半跑步`() {
        assertEquals("2026-11-12T08:30", timeOf("明早八点半跑步"))
    }

    @Test
    fun `周五下午两点化学课`() {
        assertEquals("2026-11-13T14:00", timeOf("周五下午两点化学课"))
    }

    @Test
    fun `带具体日期和冒号时间`() {
        val result = parsed("11月14日 20:30 化学课")
        assertNotNull(result)
        assertEquals("化学课", result!!.title)
        assertEquals("2026-11-14T20:30", timeOf("11月14日 20:30 化学课"))
    }

    @Test
    fun `两小时后提醒我交作业`() {
        val result = parsed("2小时后提醒我交作业")
        assertNotNull(result)
        assertEquals("交作业", result!!.title)
        assertEquals(now + 2 * 60 * 60_000L, result!!.startMillis)
    }

    @Test
    fun `带时长的课`() {
        val result = parsed("周四 20:30 物理课 2小时")
        assertNotNull(result)
        assertEquals("物理课", result!!.title)
        assertEquals(120, result!!.durationMinutes)
        assertEquals("2026-11-12T20:30", timeOf("周四 20:30 物理课 2小时"))
    }

    @Test
    fun `只说了时间且已过 顺延到明天`() {
        assertEquals("2026-11-12T20:00", timeOf("晚上八点吃饭"))
    }

    @Test
    fun `今天上午九点已过 顺延到明天`() {
        assertEquals("2026-11-12T09:00", timeOf("今天上午九点开会"))
    }

    @Test
    fun `中午十二点`() {
        assertEquals("2026-11-12T12:00", timeOf("明天中午12点吃饭"))
    }

    @Test
    fun `没有时间就返回 null`() {
        assertNull(parsed("交作业"))
        assertNull(parsed("把第三章习题做完"))
        assertNull(parsed(""))
    }

    @Test
    fun `多行文本取第一行能解析的`() {
        val text = "老师说：\n明天下午三点交作业\n别忘了"
        val result = QuickParse.parseFirstLine(text, now, zone)
        assertNotNull(result)
        assertEquals("交作业", result!!.title)
    }

    @Test
    fun `标题不会把时间词带进去`() {
        val result = parsed("明天下午三点 开家长会")
        assertNotNull(result)
        assertEquals("开家长会", result!!.title)
    }

    @Test
    fun `中文分钟加区间`() {
        val result = parsed("数学物理方法24号测试，九点五十到十一点二十五")
        assertNotNull(result)
        assertEquals("数学物理方法测试", result!!.title)
        assertEquals("2026-11-24T09:50", timeOf("数学物理方法24号测试，九点五十到十一点二十五"))
        assertEquals(95, result.durationMinutes)
    }

    @Test
    fun `阿拉伯数字的点区间`() {
        val result = parsed("数学物理方法24号测试 9点50到11点25")
        assertNotNull(result)
        assertEquals("数学物理方法测试", result!!.title)
        assertEquals("2026-11-24T09:50", timeOf("数学物理方法24号测试 9点50到11点25"))
        assertEquals(95, result.durationMinutes)
    }

    @Test
    fun `冒号加短横的区间`() {
        val result = parsed("数学物理方法24号测试 9:50-11:25")
        assertNotNull(result)
        assertEquals("数学物理方法测试", result!!.title)
        assertEquals("2026-11-24T09:50", timeOf("数学物理方法24号测试 9:50-11:25"))
        assertEquals(95, result.durationMinutes)
    }

    @Test
    fun `带分字的区间`() {
        val result = parsed("24号 九点五十分到十一点二十五分 考试")
        assertNotNull(result)
        assertEquals("考试", result!!.title)
        assertEquals("2026-11-24T09:50", timeOf("24号 九点五十分到十一点二十五分 考试"))
        assertEquals(95, result.durationMinutes)
    }

    @Test
    fun `下午两点到四点半 终点继承下午`() {
        val result = parsed("明天下午两点到四点半教研")
        assertNotNull(result)
        assertEquals("教研", result!!.title)
        assertEquals("2026-11-12T14:00", timeOf("明天下午两点到四点半教研"))
        assertEquals(150, result.durationMinutes)
    }

    @Test
    fun `跨零点的区间按次日算`() {
        assertEquals(180, parsed("明天22:00到01:00值班")!!.durationMinutes)
    }
}
