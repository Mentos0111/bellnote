package com.bellnote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextExtractorTest {

    private val now = 1_800_000_000_000L

    /** 把模型返回的待办 JSON 包进 OpenAI 兼容响应里。 */
    private fun response(content: String): String {
        val escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }

    @Test
    fun `数组里两条待办都能解析`() {
        val content = """[{"title":"交实验报告","time":"2027-02-01 09:00","duration":15},""" +
            """{"title":"买电池","time":"2027-02-01 18:00","duration":15}]"""
        val outcome = TextExtractor.parseResponse(response(content), now)
        assertEquals(2, outcome.specs.size)
        assertEquals("交实验报告", outcome.specs[0].title)
        assertEquals("买电池", outcome.specs[1].title)
    }

    @Test
    fun `单个对象也能解析`() {
        val content = """{"title":"交作业","time":"2027-02-01 09:00"}"""
        val outcome = TextExtractor.parseResponse(response(content), now)
        assertEquals(1, outcome.specs.size)
        assertEquals("交作业", outcome.specs[0].title)
    }

    @Test
    fun `markdown 代码块也能解析`() {
        val content = "```json\n[{\"title\":\"交作业\",\"time\":\"2027-02-01 09:00\"}]\n```"
        val outcome = TextExtractor.parseResponse(response(content), now)
        assertEquals(1, outcome.specs.size)
    }

    @Test
    fun `没有 JSON 时给出可读错误`() {
        val outcome = TextExtractor.parseResponse(response("今天没有待办"), now)
        assertTrue(outcome.specs.isEmpty())
        assertTrue(outcome.error?.contains("没有返回待办 JSON") == true)
    }

    @Test
    fun `系统提示带当前时间并要求只输出数组`() {
        val prompt = TextExtractor.systemPrompt(now)
        assertTrue(prompt.contains("现在是 "))
        assertTrue(prompt.contains("只输出一个 JSON 数组"))
        assertTrue(prompt.contains("yyyy-MM-dd HH:mm"))
    }
}
