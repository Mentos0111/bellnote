package com.bellnote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两条云请求链路（/models 和 /chat/completions）都必须在「打开连接之前」拦掉公网 http。
 *
 * 这些用例全部是纯 JVM：命中拦截就直接返回，不会真的发网络请求。
 */
class CloudRequestGuardTest {

    @Test
    fun `读模型列表前拦掉公网 http`() {
        val result = CloudModels.list("http://api.example.com/v1", "sk-test-not-real")
        assertFalse(result.ok)
        assertTrue("要提示用 https：${result.error}", result.error!!.contains("https"))
        assertTrue(result.models.isEmpty())
    }

    @Test
    fun `读模型列表前拦掉畸形地址`() {
        val result = CloudModels.list("ftp://api.example.com", "sk-test-not-real")
        assertFalse(result.ok)
        assertNotNull(result.error)
    }

    @Test
    fun `截图识别前拦掉公网 http 且不产生结果`() {
        val outcome = VisionExtractor.extract(
            apiKey = "sk-test-not-real",
            model = "deepseek-chat",
            jpegBase64 = "",
            now = 1_700_000_000_000L,
            baseUrl = "http://api.example.com/v1"
        )
        assertTrue("不应该产出待办", outcome.specs.isEmpty())
        assertTrue("要有错误说明", !outcome.error.isNullOrBlank())
        assertTrue("要提示用 https：${outcome.error}", outcome.error!!.contains("https"))
    }

    @Test
    fun `局域网 http 能通过前置校验`() {
        // 只验校验函数本身：真正发请求会连局域网设备，单测里不能碰网络
        val check = VisionExtractor.precheck("http://192.168.1.9:8080/v1")
        assertTrue(check.ok)
        assertTrue(check.cleartext)
    }

    @Test
    fun `chat 端点会补全默认地址和路径`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            VisionExtractor.chatEndpoint("")
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            VisionExtractor.chatEndpoint("https://api.example.com/v1")
        )
    }

    @Test
    fun `models 端点会补全默认地址和路径`() {
        assertEquals(
            "https://api.deepseek.com/models",
            CloudModels.modelsEndpoint("")
        )
        assertEquals(
            "https://api.example.com/v1/models",
            CloudModels.modelsEndpoint("https://api.example.com/v1")
        )
    }
}
