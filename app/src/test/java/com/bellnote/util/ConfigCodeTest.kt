package com.bellnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配置码的编解码（纯逻辑，不碰 Android）。 */
class ConfigCodeTest {

    @Test
    fun `编码再解码能拿回主题和 ntfy 主题`() {
        val code = ConfigCode.encode("sakura", "todo-abc-123")
        val decoded = ConfigCode.decode(code)
        assertTrue(decoded is ConfigCode.Decoded.Ok)
        val payload = (decoded as ConfigCode.Decoded.Ok).payload
        assertEquals("sakura", payload.theme)
        assertEquals("todo-abc-123", payload.topic)
    }

    @Test
    fun `码以 BN1 开头且不带换行`() {
        val code = ConfigCode.encode("pink", "x")
        assertTrue(code.startsWith("BN1."))
        assertTrue(!code.contains("\n"))
    }

    @Test
    fun `转发时被加空格或换行也能读`() {
        val code = ConfigCode.encode("teal", "todo-1")
        val messy = "  " + code.chunked(10).joinToString("\n") + "  "
        val decoded = ConfigCode.decode(messy)
        assertTrue(decoded is ConfigCode.Decoded.Ok)
        assertEquals("todo-1", (decoded as ConfigCode.Decoded.Ok).payload.topic)
    }

    @Test
    fun `少一个字符会被校验拦下`() {
        val code = ConfigCode.encode("sakura", "todo-abc")
        val broken = code.dropLast(1)
        val decoded = ConfigCode.decode(broken)
        assertTrue(decoded is ConfigCode.Decoded.Failed)
        assertTrue((decoded as ConfigCode.Decoded.Failed).reason.contains("校验"))
    }

    @Test
    fun `不是配置码的文本直接拒绝`() {
        assertTrue(ConfigCode.decode("你好，这是一段配置") is ConfigCode.Decoded.Failed)
        assertTrue(ConfigCode.decode("BN2.aaaa.bbbb") is ConfigCode.Decoded.Failed)
    }

    @Test
    fun `空输入提示先粘贴`() {
        val decoded = ConfigCode.decode("   ")
        assertTrue(decoded is ConfigCode.Decoded.Failed)
        assertTrue((decoded as ConfigCode.Decoded.Failed).reason.contains("粘贴"))
    }

    @Test
    fun `内容损坏不会崩只是失败`() {
        assertTrue(ConfigCode.decode("BN1.1234.@@@@@") is ConfigCode.Decoded.Failed)
    }

    @Test
    fun `只带主题不带 ntfy 主题也能用`() {
        val decoded = ConfigCode.decode(ConfigCode.encode("sakura", ""))
        assertTrue(decoded is ConfigCode.Decoded.Ok)
        assertEquals("", (decoded as ConfigCode.Decoded.Ok).payload.topic)
    }

    @Test
    fun `没有配色时拒绝导入`() {
        val decoded = ConfigCode.decode(ConfigCode.encode("", "todo-abc"))
        assertTrue(decoded is ConfigCode.Decoded.Failed)
        assertTrue((decoded as ConfigCode.Decoded.Failed).reason.contains("配色"))
    }
}
