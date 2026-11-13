package com.bellnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    private fun report(
        settings: List<Pair<String, String>> = emptyList(),
        messages: List<Diagnostics.Message> = emptyList(),
        crash: String? = null,
        logcat: String? = null
    ) = Diagnostics.Report(
        generatedAt = 1_760_000_000_000L,
        appName = "Bellnote",
        versionName = "3.18",
        versionCode = 31800,
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
        logcat = logcat
    )

    @Test
    fun `凭据打码只留首尾各四位`() {
        assertEquals("sk-1…cdef（共 12 位）", Diagnostics.maskSecret("sk-12345cdef"))
    }

    @Test
    fun `短凭据一律不露`() {
        assertEquals("••••（共 6 位）", Diagnostics.maskSecret("abcdef"))
    }

    @Test
    fun `空凭据写明未填`() {
        assertEquals("（未填）", Diagnostics.maskSecret("   "))
    }

    @Test
    fun `报告带版本机型和设置快照`() {
        val text = Diagnostics.render(
            report(settings = listOf("配色" to "青绿（默认）", "API Key" to Diagnostics.maskSecret("sk-abcdefghijkl")))
        )
        assertTrue(text.contains("Bellnote 反馈包"))
        assertTrue(text.contains("版本：3.18（versionCode 31800）"))
        assertTrue(text.contains("设备：RMX5080"))
        assertTrue(text.contains("配色：青绿（默认）"))
        // 原文永远不该出现
        assertFalse(text.contains("sk-abcdefghijkl"))
    }

    @Test
    fun `消息记录保留标题正文和结果`() {
        val text = Diagnostics.render(
            report(
                messages = listOf(
                    Diagnostics.Message(
                        at = 1_760_000_000_000L,
                        direction = "收到",
                        source = "ntfy",
                        title = "交实验报告",
                        body = "明天下午三点前交",
                        result = "没写进日历",
                        detail = "没认出时间"
                    )
                )
            )
        )
        assertTrue(text.contains("标题：交实验报告"))
        assertTrue(text.contains("正文：明天下午三点前交"))
        assertTrue(text.contains("没写进日历"))
        assertTrue(text.contains("说明：没认出时间"))
    }

    @Test
    fun `超长正文会截断并标注原长度`() {
        val body = "字".repeat(2_000)
        val text = Diagnostics.render(
            report(
                messages = listOf(
                    Diagnostics.Message(0L, "收到", "ntfy", "标题", body, "已进日历", "")
                )
            )
        )
        assertTrue(text.contains("已截断，原文 2000 字"))
        assertFalse(text.contains(body))
    }

    @Test
    fun `没有崩溃记录时写明没有`() {
        val text = Diagnostics.render(report())
        assertTrue(text.contains("（没有崩溃记录）"))
        assertTrue(text.contains("（系统不允许 App 读 logcat，或没有内容）"))
    }

    @Test
    fun `崩溃记录原样带进包里`() {
        val text = Diagnostics.render(report(crash = "java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
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
        val full = "短文本"
        assertEquals(full, Diagnostics.previewOf(full))
    }
}
