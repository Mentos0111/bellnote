package com.bellnote.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class VisionExtractorTest {

    @Test
    fun `从 400 报错里提取平台支持的模型名`() {
        val body = """{"error":{"message":"The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed deepseek-v4-flash-vision. (request_id: 7f934b8b-95e8-4ece-bfc4-e46592cf8d0e)","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}"""
        val names = VisionExtractor.supportedModels(body)
        assertTrue("应包含 deepseek-flash，实际：$names", names.contains("deepseek-flash"))
        assertTrue("应包含 deepseek-v4-pro，实际：$names", names.contains("deepseek-v4-pro"))
    }

    @Test
    fun `没有模型名时返回空列表`() {
        assertTrue(VisionExtractor.supportedModels("""{"error":{"message":"unauthorized"}}""").isEmpty())
    }

    @Test
    fun `stripFences 只留花括号内容`() {
        val raw = "```json\n{\"title\":\"数学作业\"}\n```"
        assertTrue(VisionExtractor.stripFences(raw) == "{\"title\":\"数学作业\"}")
    }

    @Test
    fun `stripFences 保留数组的方括号`() {
        val raw = "```json\n[{\"title\":\"A\"},{\"title\":\"B\"}]\n```"
        val out = VisionExtractor.stripFences(raw)
        assertTrue("应保留数组，实际：$out", out.startsWith("[") && out.endsWith("]"))
    }

    @Test
    fun `数组里的两条待办都能解析出来`() {
        val now = 1_800_000_000_000L
        val raw = "```json\n[{\"title\":\"化学课\",\"time\":\"2026-11-09 14:30\",\"duration\":90}," +
            "{\"title\":\"补物理错题本\",\"time\":\"2026-11-12 20:00\"}]\n```"
        val specs = VisionExtractor.parseSpecs(VisionExtractor.stripFences(raw), now)
        assertTrue("应解析出 2 条，实际 ${specs.size}", specs.size == 2)
        assertTrue(specs[1].title == "补物理错题本")
    }

    @Test
    fun `单个对象也能解析出来`() {
        val now = 1_800_000_000_000L
        val raw = """{"title":"数学作业","time":"2026-11-09 19:00"}"""
        val specs = VisionExtractor.parseSpecs(VisionExtractor.stripFences(raw), now)
        assertTrue(specs.size == 1)
    }

    @Test
    fun `字面反斜杠 n 会被还原成换行`() {
        val out = VisionExtractor.tidy(
            com.bellnote.data.TodoSpec(
                title = "化学课",
                startMillis = 1_800_000_000_000L,
                durationMinutes = 90,
                note = "第一行\\n第二行"
            )
        )
        assertTrue("应还原成真换行，实际：${out.note}", out.note!!.contains("\n"))
    }

    // ---------------- 模型输出兜底 ----------------

    private fun spec(
        title: String,
        duration: Int = 60,
        note: String? = null
    ) = com.bellnote.data.TodoSpec(
        title = title,
        startMillis = 1_800_000_000_000L,
        durationMinutes = duration,
        note = note
    )

    @Test
    fun `短标题原样保留`() {
        val out = VisionExtractor.tidy(spec("检查同学A笔记"))
        assertTrue(out.title == "检查同学A笔记")
        assertTrue(out.note == null)
    }

    @Test
    fun `超长标题截断并把多出来的挪进备注`() {
        val long = "收同学A的课堂笔记照片，看两色标记和错题有没有做"
        val out = VisionExtractor.tidy(spec(long))
        assertTrue("标题应被截断，实际：${out.title}", out.title.length <= 20)
        assertTrue(out.title.endsWith("…"))
        assertTrue("溢出的内容应进备注，实际：${out.note}", !out.note.isNullOrBlank())
        assertTrue("备注应是原标题的后半段，实际：${out.note}", out.note!!.endsWith("有没有做"))
    }

    @Test
    fun `长标题溢出内容排在原有备注前面`() {
        val long = "收同学A的课堂笔记照片，看两色标记和错题有没有做"
        val out = VisionExtractor.tidy(spec(long, note = "截止 20:00"))
        assertTrue(out.note!!.endsWith("截止 20:00"))
    }

    @Test
    fun `离谱时长回落到最小 5 分钟`() {
        assertTrue(VisionExtractor.tidy(spec("A", duration = 0)).durationMinutes == 5)
        assertTrue(VisionExtractor.tidy(spec("A", duration = 5000)).durationMinutes == 5)
        assertTrue(VisionExtractor.tidy(spec("A", duration = 120)).durationMinutes == 120)
    }

    @Test
    fun `没说时长时默认 15 分钟`() {
        val spec = com.bellnote.data.TodoSpec(
            title = "随手记",
            startMillis = 1_800_000_000_000L
        )
        assertTrue(spec.durationMinutes == 15)
        // 模型返回的 note 里没写时长也一样
        val result = com.bellnote.parser.TodoParser.parse(
            null,
            """{"title":"随手记","time":"2026-11-09 10:00"}""",
            1_800_000_000_000L
        )
        assertTrue("没给 duration 应取 15，实际 ${result.spec.durationMinutes}", result.spec.durationMinutes == 15)
    }

    @Test
    fun `空白标题给个兜底名`() {
        assertTrue(VisionExtractor.tidy(spec("   ")).title == "截图待办")
    }

    @Test
    fun `系统提示里必须带上当前日期`() {
        val now = 1_800_000_000_000L
        val prompt = VisionExtractor.systemPrompt(now)
        val expected = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        assertTrue("提示里应出现「现在是 $expected」，实际：${prompt.take(80)}", prompt.contains(expected))
        assertTrue("应禁止写过去的时间", prompt.contains("必须晚于"))
    }

    // ---------------- v2.8：识别链路加固 ----------------

    @Test
    fun `提示词不再把没说时长的默认成 60 分钟`() {
        val prompt = VisionExtractor.systemPrompt(1_800_000_000_000L)
        assertTrue("应明确「没有线索时用 15 分钟」，实际：$prompt", prompt.contains("才用 15"))
        assertTrue("不能再把 60 当默认兜底", !prompt.contains("才用 60"))
    }

    @Test
    fun `输出上限放宽到 2000，避免多条被截断`() {
        // 单测跑在 JVM 上，org.json 只有空壳，不能直接调 buildPayload；
        // 这里守住常量本身，请求体是否用上它由真机识别验证。
        assertTrue("MAX_TOKENS 太小会把多条 JSON 截断，实际：${VisionExtractor.MAX_TOKENS}",
            VisionExtractor.MAX_TOKENS >= 2000)
    }

    @Test
    fun `用户提示要求可能有多条，不能只要一条`() {
        val prompt = VisionExtractor.USER_PROMPT
        assertTrue("应说明每件不同的事各一条", prompt.contains("每件不同的事"))
        assertTrue("不应再要求只整理最该做的那一条", !prompt.contains("最该做的那件事"))
    }

    @Test
    fun `标题里的日期前缀会被清掉`() {
        assertTrue(VisionExtractor.cleanTitle("11-12 20:30 物理课") == "物理课")
        assertTrue(VisionExtractor.cleanTitle("标题：交作业") == "交作业")
        assertTrue(VisionExtractor.cleanTitle("明天 交作业。") == "交作业")
    }

    @Test
    fun `算成去年的日期会补一年`() {
        val now = 1_800_000_000_000L
        val spec = com.bellnote.data.TodoSpec("化学课", now - 300L * 24 * 3600 * 1000, 90)
        val fixed = VisionExtractor.repair(spec, now)
        assertTrue("应修正到将来，实际：" + fixed.startMillis, fixed.startMillis > now)
    }

    @Test
    fun `过去的时间会被推到明天`() {
        val now = 1_800_000_000_000L
        val spec = com.bellnote.data.TodoSpec("交作业", now - 5L * 24 * 3600 * 1000, 60)
        val fixed = VisionExtractor.repair(spec, now)
        assertTrue("应推到将来", fixed.startMillis > now)
        assertTrue("应标注不确定", fixed.note?.contains("明天") == true)
    }
}
