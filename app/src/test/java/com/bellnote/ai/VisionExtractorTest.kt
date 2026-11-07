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
}
