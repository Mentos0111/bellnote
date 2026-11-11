package com.bellnote.parser

import com.bellnote.data.RepeatRule
import com.bellnote.util.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoParserTest {

    private val now = 1_767_000_000_000L // 2025-12-27 前后，仅作基准

    @Test
    fun `键值文本 完整解析`() {
        val text = """
            标题：数学作业
            时间：2026-11-08 19:00
            时长：90
            备注：第三章习题
            重复：每周
        """.trimIndent()

        val result = TodoParser.parse("ntfy", text, now)
        assertFalse(result.fallback)
        assertEquals("数学作业", result.spec.title)
        assertEquals("2026-11-08 19:00", TimeFormat.full(result.spec.startMillis))
        assertEquals(90, result.spec.durationMinutes)
        assertEquals("第三章习题", result.spec.note)
        assertEquals(RepeatRule.WEEKLY, result.spec.repeat)
    }

    @Test
    fun `JSON 解析`() {
        val json = """{"title":"物理作业","time":"2026-11-09 08:30","duration":45,"note":"卷子","repeat":"daily"}"""
        val result = TodoParser.parse(null, json, now)
        assertFalse(result.fallback)
        assertEquals("物理作业", result.spec.title)
        assertEquals("2026-11-09 08:30", TimeFormat.full(result.spec.startMillis))
        assertEquals(45, result.spec.durationMinutes)
        assertEquals("卷子", result.spec.note)
        assertEquals(RepeatRule.DAILY, result.spec.repeat)
    }

    @Test
    fun `没有时间字段时 容错为当前加一小时`() {
        val result = TodoParser.parse("化学提醒", "标题：化学作业\n备注：第三章", now)
        assertTrue(result.fallback)
        assertEquals("化学作业", result.spec.title)
        assertEquals(now + 60 * 60_000L, result.spec.startMillis)
        assertEquals(5, result.spec.durationMinutes)
    }

    @Test
    fun `完全认不出格式时 用通知标题和正文兜底`() {
        val result = TodoParser.parse("数学作业", "明天把第三章习题做完", now)
        assertTrue(result.fallback)
        assertEquals("数学作业", result.spec.title)
        assertEquals(now + 60 * 60_000L, result.spec.startMillis)
        assertEquals("明天把第三章习题做完", result.spec.note)
    }

    @Test
    fun `时间格式 兼容多种写法`() {
        assertEquals("2026-11-08 19:00", TimeFormat.full(TodoParser.parseTime("2026-11-08 19:00", now)!!))
        assertEquals("2026-11-08 19:00", TimeFormat.full(TodoParser.parseTime("2026/11/8 19:00", now)!!))
        assertEquals("2026-11-08 09:00", TimeFormat.full(TodoParser.parseTime("2026-11-08", now)!!))
        assertEquals(
            "2026-11-08 19:00",
            TimeFormat.full(TodoParser.parseTime("2026-11-08T19:00:00", now)!!)
        )
    }

    @Test
    fun `时长 支持小时写法`() {
        val text = "标题：读英语\n时间：2026-11-08 20:00\n时长：1.5小时"
        val result = TodoParser.parse(null, text, now)
        assertEquals(90, result.spec.durationMinutes)
    }

    @Test
    fun `重复 支持中文和英文`() {
        assertEquals(RepeatRule.NONE, RepeatRule.fromRaw("不重复"))
        assertEquals(RepeatRule.DAILY, RepeatRule.fromRaw("每天"))
        assertEquals(RepeatRule.WEEKLY, RepeatRule.fromRaw("weekly"))
        assertEquals(RepeatRule.MONTHLY, RepeatRule.fromRaw("每月"))
        assertEquals(RepeatRule.YEARLY, RepeatRule.fromRaw("每年"))
        assertEquals(RepeatRule.WEEKLY, RepeatRule.fromRaw("FREQ=WEEKLY"))
    }

    // ---------- 一条通知多条待办 ----------

    @Test
    fun `JSON 数组 解析出多条`() {
        val json = """[
            {"title":"数学作业","time":"2026-11-08 19:00","duration":90},
            {"title":"物理作业","time":"2026-11-09 19:00","duration":45,"repeat":"daily"}
        ]"""
        val results = TodoParser.parseAll(null, json, now)
        assertEquals(2, results.size)
        assertEquals("数学作业", results[0].spec.title)
        assertEquals("物理作业", results[1].spec.title)
        assertEquals(RepeatRule.DAILY, results[1].spec.repeat)
    }

    @Test
    fun `todos 包装的 JSON 解析出多条`() {
        val json = """{"todos":[{"title":"化学作业","time":"2026-11-10 08:00"},{"title":"背单词","time":"2026-11-10 21:00","duration":30}]}"""
        val results = TodoParser.parseAll(null, json, now)
        assertEquals(2, results.size)
        assertEquals("化学作业", results[0].spec.title)
        assertEquals("背单词", results[1].spec.title)
    }

    @Test
    fun `空行分隔的多段键值 解析出多条`() {
        val text = """
            标题：数学作业
            时间：2026-11-08 19:00
            时长：90

            标题：物理作业
            时间：2026-11-09 19:00
            时长：45
        """.trimIndent()
        val results = TodoParser.parseAll(null, text, now)
        assertEquals(2, results.size)
        assertEquals("数学作业", results[0].spec.title)
        assertEquals("物理作业", results[1].spec.title)
        assertEquals(90, results[0].spec.durationMinutes)
    }

    @Test
    fun `单条消息 parseAll 仍只返回一条`() {
        val text = "标题：数学作业\n时间：2026-11-08 19:00\n备注：第三章"
        val results = TodoParser.parseAll(null, text, now)
        assertEquals(1, results.size)
        assertEquals("数学作业", results[0].spec.title)
    }

    @Test
    fun `解析不出时 parseAll 也返回一条兜底`() {
        val results = TodoParser.parseAll("作业提醒", "明天把第三章习题做完", now)
        assertEquals(1, results.size)
        assertTrue(results[0].fallback)
        assertEquals("作业提醒", results[0].spec.title)
    }

    // ---------- 删除指令 ----------

    @Test
    fun `删除指令 JSON 形式`() {
        val cmd = TodoParser.parseCommand("""{"action":"delete","title":"数学作业"}""", now)
        assertEquals(TodoParser.CommandType.DELETE, cmd?.type)
        assertEquals("数学作业", cmd?.title)
        assertEquals(null, cmd?.timeMillis)
    }

    @Test
    fun `删除指令 文本形式`() {
        val cmd = TodoParser.parseCommand("删除：数学作业", now)
        assertEquals(TodoParser.CommandType.DELETE, cmd?.type)
        assertEquals("数学作业", cmd?.title)
    }

    @Test
    fun `删除指令 带日期`() {
        val cmd = TodoParser.parseCommand("删除：数学作业 2026-11-08 19:00", now)
        assertEquals("数学作业", cmd?.title)
        assertEquals("2026-11-08 19:00", TimeFormat.full(cmd!!.timeMillis!!))
    }

    @Test
    fun `普通待办不会被当成删除指令`() {
        assertEquals(null, TodoParser.parseCommand("标题：数学作业\n时间：2026-11-08 19:00", now))
        assertEquals(null, TodoParser.parseCommand("""{"title":"数学作业","time":"2026-11-08 19:00"}""", now))
        assertEquals(null, TodoParser.parseCommand("明天交作业", now))
    }

    // ---------------- 严格模式依赖的 timeParsed 标记 ----------------

    @Test
    fun `带时间的 JSON 标记为时间已解析`() {
        val result = TodoParser.parse(null, """{"title":"数学作业","time":"2026-11-08 19:00"}""", now)
        assertTrue(result.timeParsed)
    }

    @Test
    fun `带时间的键值文本标记为时间已解析`() {
        val result = TodoParser.parse(null, "标题：数学作业\n时间：2026-11-08 19:00", now)
        assertTrue(result.timeParsed)
    }

    @Test
    fun `不认识的纯文本不算时间已解析`() {
        val result = TodoParser.parse("随便一条通知", "今天下午三点开个会", now)
        assertFalse(result.timeParsed)
        assertTrue(result.fallback)
    }

    @Test
    fun `JSON 缺时间字段不算时间已解析`() {
        val result = TodoParser.parse(null, """{"title":"数学作业"}""", now)
        assertFalse(result.timeParsed)
    }

    @Test
    fun `时间字段认不出来也不算时间已解析`() {
        val result = TodoParser.parse(null, """{"title":"数学作业","time":"下周找个时间"}""", now)
        assertFalse(result.timeParsed)
    }

    @Test
    fun `fromFields 能从拆好的字段表构造一条待办`() {
        val result = TodoParser.fromFields(
            mapOf("title" to "数学作业", "time" to "2026-11-08 19:00", "duration" to "90"),
            now
        )
        assertEquals("数学作业", result.spec.title)
        assertEquals(90, result.spec.durationMinutes)
        assertTrue(result.timeParsed)
    }

    // ---------------- 与推送脚本的实际输出对接 ----------------

    @Test
    fun `推送脚本发的键值正文能被完整解析`() {
        val body = "标题：化学课\n时间：2026-11-09 14:30\n时长：90\n备注：基础补漏 · 限时动笔"
        val result = TodoParser.parse("化学课", body, now)
        assertEquals("化学课", result.spec.title)
        assertEquals(90, result.spec.durationMinutes)
        assertEquals("基础补漏 · 限时动笔", result.spec.note)
        assertTrue("键值格式必须能解析出时间", result.timeParsed)
        assertFalse("不该走兜底", result.fallback)
    }

    @Test
    fun `推送脚本发的带重复的正文能被解析`() {
        val body = "标题：追学生：本周末问清期中考试范围\n时间：2026-11-09 20:00\n重复：weekly"
        val result = TodoParser.parse(null, body, now)
        assertEquals("追学生：本周末问清期中考试范围", result.spec.title)
        assertEquals(com.bellnote.data.RepeatRule.WEEKLY, result.spec.repeat)
        assertTrue(result.timeParsed)
    }

    @Test
    fun `推送脚本发的删除正文能被识别`() {
        assertEquals("数学作业", TodoParser.parseCommand("删除：数学作业", now)?.title)
        val withTime = TodoParser.parseCommand("删除：数学作业 2026-11-08 19:00", now)
        assertEquals("数学作业", withTime?.title)
        assertEquals("2026-11-08 19:00", TimeFormat.full(withTime!!.timeMillis!!))
    }

    // ---------------- 通知层把换行压平 / 截断时的容错（v3.0） ----------------

    /** 2026-11-11 22:20 外部电脑端真实发出的两条正文（备注都很长）。 */
    private val physicsBody = "标题：物理课\n时间：2026-11-12 20:30\n时长：120\n备注：" +
        "第 2 次课 2 小时·考前押题串讲（以讲真题为主、电学为主）：① 电势能/电势/电势差/U=Ed/电容" +
        "＋带电粒子在电场中（两道电场计算）② 串并联＋测金属丝电阻率（实验）③ 光：折射/全反射" +
        "＋测玻璃砖折射率＋光学计算"

    private val chemistryBody = "标题：化学课\n时间：2026-11-14 20:30\n时长：120\n" +
        "备注：基础补漏（弱电解质、pH 的 log 换算）＋ 题型③：电离与 pH 计算 ＋ 限时动笔"

    @Test
    fun `真实长备注正文能完整解析`() {
        val result = TodoParser.parse("物理课", physicsBody, now)
        assertEquals("物理课", result.spec.title)
        assertEquals("2026-11-12 20:30", TimeFormat.full(result.spec.startMillis))
        assertEquals(120, result.spec.durationMinutes)
        assertTrue(result.spec.note!!.startsWith("第 2 次课"))
        assertTrue(result.timeParsed)
    }

    @Test
    fun `换行被压成空格时仍能解析出时间`() {
        val flat = physicsBody.replace("\n", " ")
        val result = TodoParser.parse("物理课", flat, now)
        assertEquals("物理课", result.spec.title)
        assertEquals("2026-11-12 20:30", TimeFormat.full(result.spec.startMillis))
        assertEquals(120, result.spec.durationMinutes)
        assertTrue("换行被压平也必须能拿到时间，否则严格模式会静默丢弃", result.timeParsed)
    }

    @Test
    fun `换行被压成间隔号时仍能解析出时间`() {
        val flat = chemistryBody.replace("\n", " · ")
        val result = TodoParser.parse("化学课", flat, now)
        assertEquals("化学课", result.spec.title)
        assertEquals("2026-11-14 20:30", TimeFormat.full(result.spec.startMillis))
        assertTrue(result.timeParsed)
    }

    @Test
    fun `只剩首行时确实拿不到时间（该被严格模式丢弃）`() {
        val result = TodoParser.parse("物理课", "标题：物理课", now)
        assertFalse("只有标题没有时间，不能假装解析成功", result.timeParsed)
        assertEquals("物理课", result.spec.title)
    }

    @Test
    fun `正常长备注不会被键名边界误切`() {
        // 备注里出现「时间」字样但后面没有冒号的，不该被当成分隔点
        val body = "标题：化学课\n时间：2026-11-14 20:30\n备注：这次重点讲反应时间 30 秒的影响"
        val result = TodoParser.parse(null, body, now)
        assertEquals("2026-11-14 20:30", TimeFormat.full(result.spec.startMillis))
        assertEquals("这次重点讲反应时间 30 秒的影响", result.spec.note)
    }

    // ---------------- 改期指令 ----------------

    @Test
    fun `改期指令 文本形式 带两个时间`() {
        val cmd = TodoParser.parseCommand("改期：化学课 2026-11-12 20:30 到 2026-11-14 20:30", now)
        assertEquals(TodoParser.CommandType.MOVE, cmd?.type)
        assertEquals("化学课", cmd?.title)
        assertEquals("2026-11-12 20:30", TimeFormat.full(cmd!!.timeMillis!!))
        assertEquals("2026-11-14 20:30", TimeFormat.full(cmd.newTimeMillis!!))
    }

    @Test
    fun `改期指令 支持箭头和只给新时间`() {
        val arrow = TodoParser.parseCommand("改期：物理课 2026-11-12 20:30 -> 2026-11-13 20:30", now)
        assertEquals(TodoParser.CommandType.MOVE, arrow?.type)
        assertEquals("物理课", arrow?.title)
        assertEquals("2026-11-13 20:30", TimeFormat.full(arrow!!.newTimeMillis!!))

        val onlyNew = TodoParser.parseCommand("改期：物理课 到 2026-11-13 20:30", now)
        assertEquals(TodoParser.CommandType.MOVE, onlyNew?.type)
        assertEquals("物理课", onlyNew?.title)
        assertEquals(null, onlyNew?.timeMillis)
        assertEquals("2026-11-13 20:30", TimeFormat.full(onlyNew!!.newTimeMillis!!))
    }

    @Test
    fun `改期指令 JSON 形式`() {
        val json = """{"action":"move","title":"化学课","from":"2026-11-12 20:30","to":"2026-11-14 20:30"}"""
        val cmd = TodoParser.parseCommand(json, now)
        assertEquals(TodoParser.CommandType.MOVE, cmd?.type)
        assertEquals("化学课", cmd?.title)
        assertEquals("2026-11-12 20:30", TimeFormat.full(cmd!!.timeMillis!!))
        assertEquals("2026-11-14 20:30", TimeFormat.full(cmd.newTimeMillis!!))
    }

    @Test
    fun `普通待办不会被当成改期指令`() {
        assertEquals(null, TodoParser.parseCommand("标题：化学课\n时间：2026-11-14 20:30", now))
        assertEquals(null, TodoParser.parseCommand("化学课改到周六", now))
    }
}
