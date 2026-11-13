package com.bellnote.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表 Diff 的判定规则：换数据时「什么算同一条」「什么算内容变了」。
 * 判定错了就会出现「整表重刷」或者「明明改了却不动」。
 */
class DiffDispatchTest {

    private data class Row(val id: Long, val text: String)

    private fun callback(
        old: List<Row>,
        new: List<Row>,
        sameItem: (Row, Row) -> Boolean = { a, b -> a.id == b.id }
    ) = DiffDispatch.Callback(old, new, sameItem, { a, b -> a == b })

    @Test
    fun 同一个id算同一条() {
        val cb = callback(listOf(Row(1, "A")), listOf(Row(1, "A")))
        assertTrue(cb.areItemsTheSame(0, 0))
        assertTrue(cb.areContentsTheSame(0, 0))
    }

    @Test
    fun 内容改了算同一条但内容不同() {
        val cb = callback(listOf(Row(1, "A")), listOf(Row(1, "A 改过了")))
        assertTrue(cb.areItemsTheSame(0, 0))
        assertFalse(cb.areContentsTheSame(0, 0))
    }

    @Test
    fun 不同id算换了一条() {
        val cb = callback(listOf(Row(1, "A")), listOf(Row(2, "A")))
        assertFalse(cb.areItemsTheSame(0, 0))
    }

    @Test
    fun 两边数量不同时尺寸各自正确() {
        val cb = callback(listOf(Row(1, "A")), listOf(Row(1, "A"), Row(2, "B"), Row(3, "C")))
        assertTrue(cb.oldListSize == 1)
        assertTrue(cb.newListSize == 3)
    }

    @Test
    fun 顺序变了仍认得出是同一条() {
        val cb = callback(
            listOf(Row(1, "A"), Row(2, "B")),
            listOf(Row(2, "B"), Row(1, "A"))
        )
        // 老的第 0 条（id=1）在新列表里是第 1 条
        assertTrue(cb.areItemsTheSame(0, 1))
        assertTrue(cb.areContentsTheSame(0, 1))
    }

    @Test
    fun 空列表也能算() {
        val cb = callback(emptyList(), emptyList())
        assertTrue(cb.oldListSize == 0 && cb.newListSize == 0)
    }
}
