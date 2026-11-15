package com.bellnote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云接口地址规则：公网必须 https，只有本机/局域网才允许 http 明文。
 *
 * 这些用例盯着的是「安全边界」，不是文案细节 —— 改规则先改这里。
 */
class CloudEndpointTest {

    @Test
    fun `空地址回到默认 https 地址`() {
        assertEquals("https://api.deepseek.com", CloudEndpoint.normalize(""))
        val check = CloudEndpoint.check("")
        assertTrue(check.ok)
        assertEquals("https://api.deepseek.com", check.normalized)
        assertFalse(check.cleartext)
    }

    @Test
    fun `合法 https 放行且不改写`() {
        val check = CloudEndpoint.check("https://api.openai.com/v1/")
        assertTrue(check.ok)
        assertEquals("https://api.openai.com/v1", check.normalized)
        assertFalse(check.cleartext)
    }

    @Test
    fun `公网 http 一律拒绝`() {
        for (raw in listOf(
            "http://api.example.com/v1",
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1"   // 172.32 已经出了私网段
        )) {
            val check = CloudEndpoint.check(raw)
            assertFalse("应该拒绝：$raw", check.ok)
            assertTrue("提示要提到 https：${check.reason}", check.reason!!.contains("https"))
        }
    }

    @Test
    fun `本机和局域网 http 放行但要标记明文`() {
        for (raw in listOf(
            "http://127.0.0.1:8000/v1",
            "http://localhost:1234",
            "http://192.168.1.9:8080/v1",
            "http://10.0.0.5/v1",
            "http://172.20.3.4/v1"
        )) {
            val check = CloudEndpoint.check(raw)
            assertTrue("应该放行：$raw", check.ok)
            assertTrue("要标成明文：$raw", check.cleartext)
        }
    }

    @Test
    fun `畸形地址和不支持的协议明确报错`() {
        for (raw in listOf("http://", "ftp://example.com", "://nohost", "https://")) {
            val check = CloudEndpoint.check(raw)
            assertFalse("应该拒绝：$raw", check.ok)
            assertTrue("要给原因：$raw", !check.reason.isNullOrBlank())
        }
    }

    @Test
    fun `只写主机名时按私网与否补协议`() {
        assertEquals("https://api.deepseek.com", CloudEndpoint.normalize("api.deepseek.com"))
        assertEquals("http://192.168.1.10:8080", CloudEndpoint.normalize("192.168.1.10:8080"))
        assertEquals("http://localhost:8000/v1", CloudEndpoint.normalize("localhost:8000/v1"))
    }

    @Test
    fun `报错文案里不会出现 Key 之类的东西`() {
        val reason = CloudEndpoint.check("http://api.example.com/v1").reason.orEmpty()
        assertFalse(reason.contains("sk-"))
        assertFalse(reason.contains("Bearer"))
    }
}
