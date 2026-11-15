package com.bellnote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原因码：固定枚举、可翻译、绝不夹带自由文本。 */
class ReasonCodeTest {

    @Test
    fun `每个枚举都有稳定的码和中文`() {
        ReasonCode.entries.forEach { code ->
            assertTrue(code.code.isNotBlank())
            assertTrue(code.code == code.code.uppercase())
            assertTrue(code.zh.isNotBlank())
            assertEquals("${code.code}（${code.zh}）", code.display())
        }
    }

    @Test
    fun `成功写入没有原因码`() {
        assertNull(ReasonCode.classify("", "写进日历"))
        assertNull(ReasonCode.classify("", "更新"))
    }

    @Test
    fun `常见失败原因归到正确枚举`() {
        assertEquals(ReasonCode.CALENDAR_PERMISSION_DENIED, ReasonCode.classify("缺少日历权限"))
        assertEquals(ReasonCode.MISSING_TIME, ReasonCode.classify("没认出时间"))
        assertEquals(ReasonCode.UNPARSABLE_TIME, ReasonCode.classify("时间格式不对"))
        assertEquals(ReasonCode.DUPLICATE, ReasonCode.classify("已存在，重复"))
        assertEquals(ReasonCode.CALENDAR_WRITE_FAILED, ReasonCode.classify("写入日历失败"))
        assertEquals(ReasonCode.NETWORK_ERROR, ReasonCode.classify("连不上：HTTP 502"))
        assertEquals(ReasonCode.AUTH_ERROR, ReasonCode.classify("401 Key 不对"))
        assertEquals(ReasonCode.PARSE_ERROR, ReasonCode.classify("返回不是 JSON"))
        assertEquals(ReasonCode.IGNORED, ReasonCode.classify("严格模式跳过"))
        assertEquals(ReasonCode.UNKNOWN, ReasonCode.classify("别的什么"))
    }

    @Test
    fun `展示层把码翻成中文 未知码原样返回`() {
        assertEquals("MISSING_TIME（缺少时间）", ReasonCode.display("MISSING_TIME"))
        assertEquals("历史自由文本", ReasonCode.display("历史自由文本"))
        assertEquals("", ReasonCode.display(null))
    }

    @Test
    fun `消息记录页只显示中文 不带枚举码`() {
        assertEquals("缺少时间", ReasonCode.labelOf("MISSING_TIME"))
        assertEquals("未知原因", ReasonCode.labelOf("UNKNOWN"))
        assertEquals("", ReasonCode.labelOf(""))
        assertFalse(ReasonCode.labelOf("MISSING_TIME").contains("MISSING_TIME"))
    }

    @Test
    fun `原因码里不含自由文本`() {
        val freeText = "张三的物理作业 https://example.com/x?q=1"
        val code = ReasonCode.classify(freeText)
        assertEquals(ReasonCode.UNKNOWN, code)
        assertFalse(code!!.code.contains("张三"))
        assertFalse(code.code.contains("http"))
        assertTrue(code.code.matches(Regex("[A-Z_]+")))
    }

    @Test
    fun `fromCode 只认精确码`() {
        assertEquals(ReasonCode.DUPLICATE, ReasonCode.fromCode("DUPLICATE"))
        assertNull(ReasonCode.fromCode("duplicate"))
        assertNull(ReasonCode.fromCode("随便一段话"))
    }
}
