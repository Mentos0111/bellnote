package com.bellnote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多主题配置的纯文本编码：多主题靠它存盘，写坏了会丢主题，所以单测守住。 */
class NtfyTopicCodecTest {

    @Test
    fun `encode decode 往返保留备注名和游标`() {
        val list = listOf(
            NtfyTopic("todo-aaa", "家里", 1_700_000_000L),
            NtfyTopic("todo-bbb", "工作", 0L)
        )
        val decoded = NtfyTopicCodec.decode(NtfyTopicCodec.encode(list))
        assertEquals(2, decoded.size)
        assertEquals("todo-aaa", decoded[0].topic)
        assertEquals("家里", decoded[0].label)
        assertEquals(1_700_000_000L, decoded[0].since)
        assertEquals("工作", decoded[1].label)
        assertEquals(0L, decoded[1].since)
    }

    @Test
    fun `没有任何配置时解出空列表`() {
        assertTrue(NtfyTopicCodec.decode(null).isEmpty())
        assertTrue(NtfyTopicCodec.decode("").isEmpty())
        assertTrue(NtfyTopicCodec.decode("   ").isEmpty())
    }

    @Test
    fun `坏行被跳过而不是整份丢`() {
        val raw = "家里\ttodo-ok\t123\n这一行没有分隔符\ntodo-only-second-column"
        val decoded = NtfyTopicCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("todo-ok", decoded[0].topic)
        assertEquals(123L, decoded[0].since)
    }

    @Test
    fun `备注名里的制表符和换行被压平`() {
        val decoded = NtfyTopicCodec.decode(NtfyTopicCodec.encode(listOf(NtfyTopic("t1", "家\t里\n"))))
        assertEquals(1, decoded.size)
        assertEquals("家 里", decoded[0].label)
    }

    @Test
    fun `upsert 新主题是追加，重复主题只更新备注`() {
        var list = listOf(NtfyTopic("a", "家里"))
        list = NtfyTopicCodec.upsert(list, NtfyTopic("b", "工作"))
        assertEquals(2, list.size)

        list = NtfyTopicCodec.upsert(list, NtfyTopic("a", "自己"))
        assertEquals(2, list.size)
        assertEquals("自己", list.first { it.topic == "a" }.label)
    }

    @Test
    fun `upsert 尊重上限和空主题`() {
        var list = emptyList<NtfyTopic>()
        repeat(NtfyTopicCodec.MAX_TOPICS + 3) { i ->
            list = NtfyTopicCodec.upsert(list, NtfyTopic("t$i"))
        }
        assertEquals(NtfyTopicCodec.MAX_TOPICS, list.size)

        val unchanged = NtfyTopicCodec.upsert(list, NtfyTopic("   "))
        assertEquals(NtfyTopicCodec.MAX_TOPICS, unchanged.size)
    }

    @Test
    fun `display 没有备注名时只显示主题`() {
        assertEquals("todo-x", NtfyTopic("todo-x").display())
        assertEquals("家里 · todo-x", NtfyTopic("todo-x", "家里").display())
    }
}
