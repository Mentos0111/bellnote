package com.bellnote.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 屏幕文字 → 待办：这里主要保「时间单独一行」的拼装规则。
 *
 * 聊天截图里日期、时间、标题经常各占一行（「11月14日 周六」/「20:30」/「化学课」），
 * 以前逐行解析会把时间行当噪音丢掉，剩下的标题行又因为没时间解析不出来。
 */
class ScreenTextParserTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /** 2026-11-13 是周五，上午 10:00。 */
    private val now = at(2026, 11, 13, 10, 0)

    @Test
    fun `时间在前一行也能拼起来`() {
        val text = """
            20:30
            化学课
        """.trimIndent()
        val specs = ScreenTextParser.parse(text, now)
        assertEquals(1, specs.size)
        assertEquals("化学课", specs[0].title)
        assertEquals(at(2026, 11, 13, 20, 30), specs[0].startMillis)
    }

    @Test
    fun `日期和时间分别占一行`() {
        val text = """
            11月14日 周六
            20:30
            化学课
        """.trimIndent()
        val specs = ScreenTextParser.parse(text, now)
        assertEquals(1, specs.size)
        assertEquals("化学课", specs[0].title)
        assertEquals(at(2026, 11, 14, 20, 30), specs[0].startMillis)
    }

    @Test
    fun `标题在前时间在后也行`() {
        val text = """
            交实验报告
            明天 9:00
        """.trimIndent()
        val specs = ScreenTextParser.parse(text, now)
        assertEquals(1, specs.size)
        assertEquals("交实验报告", specs[0].title)
        assertEquals(at(2026, 11, 14, 9, 0), specs[0].startMillis)
    }

    @Test
    fun `一行说全的照旧能解析`() {
        val specs = ScreenTextParser.parse("明天下午三点 交实验报告", now)
        assertEquals(1, specs.size)
        assertEquals("交实验报告", specs[0].title)
        assertEquals(at(2026, 11, 14, 15, 0), specs[0].startMillis)
    }

    @Test
    fun `只有时间没有内容不产生待办`() {
        assertTrue(ScreenTextParser.parse("20:30\n11月14日\n今天", now).isEmpty())
    }

    @Test
    fun `界面噪音不会变成待办`() {
        val text = """
            微信
            通讯录
            20:30
            [2条] 张三: 在吗
            明天 9:00 交实验报告
        """.trimIndent()
        val specs = ScreenTextParser.parse(text, now)
        assertTrue(specs.none { it.title.contains("微信") || it.title.contains("通讯录") })
    }

    @Test
    fun `同一标题只留一条`() {
        val text = """
            明天 9:00 交实验报告
            明天 9:00 交实验报告
        """.trimIndent()
        assertEquals(1, ScreenTextParser.parse(text, now).size)
    }

    @Test
    fun `多条按顺序最多取 maxItems`() {
        val text = """
            明天 9:00 交实验报告
            明天 10:00 背单词
            明天 11:00 找老师
        """.trimIndent()
        assertEquals(2, ScreenTextParser.parse(text, now, maxItems = 2).size)
    }

    @Test
    fun `兜底不会拿纯时间行当标题`() {
        val text = """
            20:30
            11月14日 周六
        """.trimIndent()
        assertNull(ScreenTextParser.fallbackSpec(text, now))
    }

    @Test
    fun `兜底会挑最长的那行内容`() {
        val spec = ScreenTextParser.fallbackSpec("20:30\n把物理卷子写完并订正错题", now)
        assertEquals("把物理卷子写完并订正错题", spec?.title)
        assertEquals(at(2026, 11, 14, 9, 0), spec?.startMillis)
    }
}
