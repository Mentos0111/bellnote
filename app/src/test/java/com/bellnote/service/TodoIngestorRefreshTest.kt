package com.bellnote.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 哪些处理结果要通知首页刷新。
 * 只丢消息（没认出时间、缺权限）不该白刷；真的写了/改了/删了才刷。
 */
class TodoIngestorRefreshTest {

    @Test
    fun `新建待办要刷新`() {
        assertTrue(TodoIngestor.shouldRefreshUi(TodoIngestor.Outcome(wrote = 1)))
    }

    @Test
    fun `更新或改期要刷新`() {
        assertTrue(TodoIngestor.shouldRefreshUi(TodoIngestor.Outcome(updated = 2)))
    }

    @Test
    fun `删除成功要刷新`() {
        // 删除成功走 wrote=删除条数 这条路径
        assertTrue(TodoIngestor.shouldRefreshUi(TodoIngestor.Outcome(wrote = 1, isDelete = true)))
    }

    @Test
    fun `只是丢弃消息不刷新`() {
        assertFalse(TodoIngestor.shouldRefreshUi(TodoIngestor.Outcome(dropped = 1)))
        assertFalse(TodoIngestor.shouldRefreshUi(TodoIngestor.Outcome()))
    }

    @Test
    fun `删除没找到不刷新`() {
        assertFalse(
            TodoIngestor.shouldRefreshUi(
                TodoIngestor.Outcome(noticeTitle = "没找到要删的待办", isDelete = true)
            )
        )
    }
}
