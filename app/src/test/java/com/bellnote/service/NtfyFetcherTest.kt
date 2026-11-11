package com.bellnote.service

import com.bellnote.data.DeleteWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** ntfy 补查的解析层：`/json?poll=1` 返回的是一行一个 JSON 对象（ndjson）。 */
class NtfyFetcherTest {

    private val ndjson = """
        {"id":"Zp4BKlGYMsQo","time":1762861230,"expires":1762947630,"event":"message","topic":"todo-demo-0000","message":"删除：化学课 2026-11-12 20:30","title":"删除：化学课","priority":3,"tags":["wastebasket"]}
        {"id":"7lcdJlftpaxf","time":1762861236,"event":"message_delete","topic":"todo-demo-0000","message":""}
        {"id":"poll","time":1762861237,"event":"poll_request","topic":"todo-demo-0000"}
        {"id":"GuvEj6Nfr9CL","time":1762861238,"event":"message","topic":"todo-demo-0000","message":"标题：物理课\n时间：2026-11-12 20:30\n时长：120\n备注：第 2 次课 2 小时","title":"物理课","priority":3}
        {"id":"keepalive","time":1762861239,"event":"keepalive","topic":"todo-demo-0000"}
    """.trimIndent()

    @Test
    fun `只收 event 为 message 且有正文的条目`() {
        val messages = NtfyFetcher.parseJsonLines(ndjson)
        assertEquals(2, messages.size)
        assertEquals("Zp4BKlGYMsQo", messages[0].id)
        assertEquals("删除：化学课 2026-11-12 20:30", messages[0].text)
        assertEquals("物理课", messages[1].title)
        assertEquals(1762861238L, messages[1].timeSeconds)
        assertTrue(messages[1].text.contains("\n"))
    }

    @Test
    fun `没有 event 字段的老格式也认`() {
        val line = """{"id":"a1","time":1762861240,"message":"标题：数学课","title":"数学课"}"""
        val messages = NtfyFetcher.parseJsonLines(line)
        assertEquals(1, messages.size)
        assertEquals("标题：数学课", messages[0].text)
    }

    @Test
    fun `垃圾行不会炸`() {
        val raw = "\n{坏的 json\nnot json at all\n\n" + """{"id":"b","time":1,"message":"标题：x"}"""
        val messages = NtfyFetcher.parseJsonLines(raw)
        assertEquals(1, messages.size)
    }

    @Test
    fun `空响应返回空列表`() {
        assertTrue(NtfyFetcher.parseJsonLines("").isEmpty())
        assertTrue(NtfyFetcher.parseJsonLines("\n\n").isEmpty())
    }

    @Test
    fun `补查拉回来的正文走同一条解析路径`() {
        val messages = NtfyFetcher.parseJsonLines(ndjson)
        val parsed = com.bellnote.parser.TodoParser.parseAll(
            messages[1].title,
            messages[1].text,
            1_767_000_000_000L
        )
        assertEquals(1, parsed.size)
        assertEquals("物理课", parsed[0].spec.title)
        assertEquals(120, parsed[0].spec.durationMinutes)
        assertTrue(parsed[0].timeParsed)
    }

    // ---------------- 删除指令的时间窗口 ----------------

    private fun millis(text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `删除时间窗口 30 分钟内算命中`() {
        val target = millis("2026-11-12T20:30")
        assertTrue(DeleteWindow.matches(target, target))
        assertTrue(DeleteWindow.matches(target + 29 * 60_000L, target))
        assertTrue(DeleteWindow.matches(target - 29 * 60_000L, target))
    }

    @Test
    fun `同一天但隔了一小时不算命中`() {
        val target = millis("2026-11-12T20:30")
        assertFalse(DeleteWindow.matches(millis("2026-11-12T19:00"), target))
        assertFalse(DeleteWindow.matches(millis("2026-11-12T22:00"), target))
    }

    @Test
    fun `不给时间时只按标题删 (匹配一切)`() {
        assertTrue(DeleteWindow.matches(millis("2026-11-12T20:30"), null))
    }
}
