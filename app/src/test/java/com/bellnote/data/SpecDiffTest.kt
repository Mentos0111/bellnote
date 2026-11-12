package com.bellnote.data

import com.bellnote.service.NtfySender
import com.bellnote.util.IcsExport
import com.bellnote.util.LedgerBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.1 新增的几段纯逻辑：同条待办的内容比对、导出、App 内测试推送。 */
class SpecDiffTest {

    private fun item(note: String? = "旧备注", duration: Int = 120, repeat: RepeatRule = RepeatRule.NONE) =
        TodoItem(
            id = 1L,
            title = "化学课",
            startMillis = 1_794_486_600_000L,
            durationMinutes = duration,
            note = note,
            repeat = repeat,
            calendarId = 9L
        )

    private fun spec(note: String? = "旧备注", duration: Int = 120, repeat: RepeatRule = RepeatRule.NONE) =
        TodoSpec(
            title = "化学课",
            startMillis = 1_794_486_600_000L,
            durationMinutes = duration,
            note = note,
            repeat = repeat
        )

    @Test
    fun `内容没变 不该重写`() {
        assertFalse(SpecDiff.differs(item(), spec()))
    }

    @Test
    fun `改了备注 要更新`() {
        assertTrue(SpecDiff.differs(item(note = "旧备注"), spec(note = "新备注")))
    }

    @Test
    fun `改了时长或重复 要更新`() {
        assertTrue(SpecDiff.differs(item(duration = 120), spec(duration = 90)))
        assertTrue(SpecDiff.differs(item(repeat = RepeatRule.NONE), spec(repeat = RepeatRule.WEEKLY)))
    }

    @Test
    fun `空备注和 null 备注算一样`() {
        assertFalse(SpecDiff.differs(item(note = null), spec(note = "")))
        assertFalse(SpecDiff.differs(item(note = "  "), spec(note = null)))
    }

    @Test
    fun `备注换行差异不算改`() {
        assertFalse(SpecDiff.differs(item(note = "第一行\r\n第二行"), spec(note = "第一行\n第二行")))
    }

    // ---------------- 导出 ----------------

    @Test
    fun `ics 导出 事件数量与结构正确`() {
        val items = listOf(
            LocalTodo(1, "化学课", 1_794_486_600_000L, 120, "备注;带分号,逗号", RepeatRule.NONE, 126, "ntfy"),
            LocalTodo(2, "物理课", 1_794_314_580_000L, 60, null, RepeatRule.NONE, 127, "ntfy")
        )
        val ics = IcsExport.build(items, now = 1_794_400_000_000L)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("END:VCALENDAR"))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertTrue(ics.contains("SUMMARY:化学课"))
        assertTrue(ics.contains("DESCRIPTION:备注\\;带分号\\,逗号"))
        assertTrue("时间要写成 UTC", ics.contains("DTSTART:20261112T123000Z"))
    }

    @Test
    fun `ics 空账本也能导出`() {
        val ics = IcsExport.build(emptyList())
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `账本 json 备份 能转义引号和换行`() {
        val items = listOf(
            LocalTodo(1, "带\"引号\"的标题", 1_794_486_600_000L, 120, "第一行\n第二行", RepeatRule.NONE, 126, "ntfy")
        )
        val json = LedgerBackup.toJson(items, now = 1_794_400_000_000L)
        assertTrue(json.contains("\"count\": 1"))
        assertTrue(json.contains("带\\\"引号\\\"的标题"))
        assertTrue(json.contains("第一行\\n第二行"))
    }

    // ---------------- App 内测试推送 ----------------

    @Test
    fun `测试推送的正文 能被自己的解析器读懂`() {
        val now = 1_794_400_000_000L
        val message = NtfySender.buildTestMessage(now)
        val result = com.bellnote.parser.TodoParser.parse("Bellnote 自测", message, now)
        assertEquals("Bellnote 自测", result.spec.title)
        assertEquals(15, result.spec.durationMinutes)
        assertTrue("自测消息必须带时间，否则会被严格模式丢掉", result.timeParsed)
        // 正文只精确到分钟，所以允许 1 分钟以内的误差
        val expected = now + 10 * 60_000L
        assertTrue(
            "应约等于 10 分钟后，实际 ${result.spec.startMillis}",
            kotlin.math.abs(result.spec.startMillis - expected) < 60_000L
        )
    }

    @Test
    fun `测试推送的 JSON 体 字段齐全且转义正确`() {
        val body = NtfySender.buildRequestBody("todo-abc", "标题", "第一行\n第二行")
        assertTrue(body.contains("\"topic\":\"todo-abc\""))
        assertTrue(body.contains("\"title\":\"标题\""))
        assertTrue(body.contains("第一行\\n第二行"))
    }
}
