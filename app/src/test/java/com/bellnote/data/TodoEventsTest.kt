package com.bellnote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首页「写库后自己刷新」的进程内事件。
 * 对应 2026-11-17 真机 bug：停在首页时电脑端新发/改/删待办，列表不跟着变。
 */
class TodoEventsTest {

    @Before
    fun setUp() {
        TodoEvents.resetForTest()
    }

    @Test
    fun `写库后通知已注册的监听方`() {
        val got = mutableListOf<String>()
        TodoEvents.addListener { source -> got.add(source) }
        TodoEvents.notifyChanged("补查")
        assertEquals(listOf("补查"), got)
        assertEquals(1L, TodoEvents.changeCount())
    }

    @Test
    fun `注销后不再收到通知`() {
        var count = 0
        val listener: (String) -> Unit = { count++ }
        TodoEvents.addListener(listener)
        TodoEvents.removeListener(listener)
        TodoEvents.notifyChanged("补查")
        assertEquals(0, count)
        assertFalse(TodoEvents.listenerCount() > 0)
    }

    @Test
    fun `多个监听方都能收到`() {
        var a = 0
        var b = 0
        TodoEvents.addListener { a++ }
        TodoEvents.addListener { b++ }
        TodoEvents.notifyChanged("通知")
        assertEquals(1, a)
        assertEquals(1, b)
        assertEquals(2, TodoEvents.listenerCount())
    }

    @Test
    fun `一个监听方抛异常不影响其它监听方和写库方`() {
        var ok = 0
        TodoEvents.addListener { throw IllegalStateException("界面炸了") }
        TodoEvents.addListener { ok++ }
        // 不该把异常抛回写库线程
        TodoEvents.notifyChanged("补查")
        assertEquals(1, ok)
    }

    @Test
    fun `重复注册同一个监听方只算一次`() {
        var count = 0
        val listener: (String) -> Unit = { count++ }
        TodoEvents.addListener(listener)
        TodoEvents.addListener(listener)
        TodoEvents.notifyChanged("补查")
        assertEquals(1, count)
        assertEquals(1, TodoEvents.listenerCount())
    }

    @Test
    fun `没有任何监听方时也不报错`() {
        TodoEvents.notifyChanged("补查")
        assertTrue(TodoEvents.changeCount() == 1L)
        assertEquals(0, TodoEvents.listenerCount())
    }
}
