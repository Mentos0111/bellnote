package com.bellnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈包的脱敏契约（3.21.0 起）：
 * - API Key / ntfy 主题只报「有没有配、多少位」，一个字符都不露；
 * - 待办标题与正文不进包；
 * - 崩溃记录只留结构、异常消息与 URL/密钥/主题全部被剔除；诊断包完全不采集 logcat。
 */
class DiagnosticsTest {

    private fun report(
        settings: List<Pair<String, String>> = emptyList(),
        messages: List<Diagnostics.Message> = emptyList(),
        crash: String? = null,
        secrets: List<String> = emptyList()
    ) = Diagnostics.Report(
        generatedAt = 1_760_000_000_000L,
        appName = "Bellnote",
        versionName = "3.21",
        versionCode = 32100,
        packageName = "com.bellnote",
        device = "RMX5080 · realme · realme · arm64-v8a",
        android = "Android 15（SDK 35）· test",
        locale = "zh-CN",
        timezone = "Asia/Shanghai",
        settings = settings,
        permissions = listOf("日历读写" to "开"),
        stats = listOf("本地待办账本" to "3 条"),
        messages = messages,
        crash = crash,
        secrets = secrets
    )

    @Test
    fun `凭据只报长度不露任何字符`() {
        val masked = Diagnostics.maskSecret("sk-12345cdef")
        assertEquals("（已配置，共 12 位，内容不记录）", masked)
        assertFalse(masked.contains("sk-"))
        assertFalse(masked.contains("cdef"))
    }

    @Test
    fun `短凭据同样不露字符`() {
        val masked = Diagnostics.maskSecret("abcdef")
        assertEquals("（已配置，共 6 位，内容不记录）", masked)
        assertFalse(masked.contains("abc"))
        assertFalse(masked.contains("def"))
    }

    @Test
    fun `空凭据写明未填`() {
        assertEquals("（未填）", Diagnostics.maskSecret("   "))
    }

    @Test
    fun `报告带版本机型和设置快照`() {
        val text = Diagnostics.render(
            report(settings = listOf("配色" to "墨黑（默认）", "API Key" to Diagnostics.maskSecret("sk-abcdefghijkl")))
        )
        assertTrue(text.contains("Bellnote 反馈包"))
        assertTrue(text.contains("版本：3.21（versionCode 32100）"))
        assertTrue(text.contains("设备：RMX5080"))
        assertTrue(text.contains("配色：墨黑（默认）"))
        assertFalse(text.contains("sk-abcdefghijkl"))
    }

    @Test
    fun `消息记录只留结果不含标题正文与自由文本`() {
        val text = Diagnostics.render(
            report(
                messages = listOf(
                    Diagnostics.Message(
                        at = 1_760_000_000_000L,
                        direction = "收到",
                        source = "ntfy",
                        result = "没写进日历"
                    )
                )
            )
        )
        assertFalse(text.contains("标题："))
        assertFalse(text.contains("正文："))
        // 只有包头那句「说明：…」允许出现，消息条目里不能再有自由文本
        assertEquals(1, text.split("说明：").size - 1)
        assertTrue(text.contains("没写进日历"))
    }

    @Test
    fun `消息多时按上限截断且不带自由文本`() {
        val many = (1..(Diagnostics.MAX_MESSAGES + 20)).map {
            Diagnostics.Message(it.toLong(), "收到", "补查", "已进日历")
        }
        val text = Diagnostics.render(report(messages = many))
        assertTrue(text.contains("共 ${many.size} 条"))
        assertFalse(text.contains("标题："))
        assertFalse(text.contains("正文："))
    }

    @Test
    fun `没有崩溃记录时写明没有`() {
        val text = Diagnostics.render(report())
        assertTrue(text.contains("（没有崩溃记录）"))
        assertTrue(text.contains("不收集 logcat"))
    }

    @Test
    fun `崩溃记录只留异常类名和堆栈帧`() {
        val crash = "java.io.IOException: failed https://ntfy.sh/fake-topic/json?poll=1&since=123 伪正文\n" +
            "\tat com.bellnote.service.NtfyFetcher.fetch(NtfyFetcher.kt:123)\n" +
            "Caused by: java.net.UnknownHostException: ntfy.sh 伪正文"
        val text = Diagnostics.render(report(crash = crash))
        assertTrue(text.contains("java.io.IOException"))
        assertTrue(text.contains("at com.bellnote.service.NtfyFetcher.fetch"))
        // 异常消息（可能夹带用户原文/URL）整段丢弃
        assertFalse(text.contains("since=123"))
        assertFalse(text.contains("伪正文"))
        assertFalse(text.contains("failed https"))
    }

    @Test
    fun `redact 会抹掉显式给定的密钥`() {
        val out = Diagnostics.redact("key=abc123def456 继续", secrets = listOf("abc123def456"))
        assertFalse(out.contains("abc123def456"))
        assertTrue(out.contains("（已隐藏）"))
    }

    @Test
    fun `redact 处理常见密钥与主题形态`() {
        val out = Diagnostics.redact("sk-abcdef123456 Bearer abc.def.ghi123 todo-f5300772084c8294")
        assertFalse(out.contains("sk-abcdef123456"))
        assertFalse(out.contains("abc.def.ghi123"))
        assertFalse(out.contains("f5300772084c8294"))
    }

    @Test
    fun `redact 保留非敏感上下文`() {
        val out = Diagnostics.redact("补查失败：HTTP 429 quota，重试 1 次")
        assertEquals("补查失败：HTTP 429 quota，重试 1 次", out)
    }

    @Test
    fun `单独落盘的附件文本同样脱敏`() {
        val raw = "I: GET https://ntfy.sh/secret-topic/json?poll=1&since=42 key=sk-abcdef123456"
        val text = Diagnostics.fileText(report(secrets = listOf("secret-topic")), raw)
        assertFalse(text.contains("secret-topic"))
        assertFalse(text.contains("since=42"))
        assertFalse(text.contains("sk-abcdef123456"))
        assertTrue(text.contains("路径与参数已省略"))
    }

    @Test
    fun `附件脱敏保留可排查的非敏感信息`() {
        val raw = "E NtfyFetcher: 补查失败（第 1 个主题）HTTP 503"
        val text = Diagnostics.fileText(report(), raw)
        assertEquals(raw, text)
    }

    @Test
    fun `预览超长时只留前面并说明`() {
        val full = "a".repeat(Diagnostics.PREVIEW_CHARS + 100)
        val preview = Diagnostics.previewOf(full)
        assertTrue(preview.length < full.length)
        assertTrue(preview.contains("完整内容在 zip 里"))
    }

    @Test
    fun `预览不超长时原样返回`() {
        assertEquals("短文本", Diagnostics.previewOf("短文本"))
    }
}
