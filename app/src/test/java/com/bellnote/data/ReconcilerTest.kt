package com.bellnote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对账比对逻辑的单元测试：只跑纯函数，不碰 Android。 */
class ReconcilerTest {

    private val day = 1_767_225_600_000L // 2026-01-01 00:00 +08:00 附近，具体值不重要

    private fun local(
        id: Long = 1L,
        title: String = "数学作业",
        start: Long = day,
        duration: Int = 60,
        repeat: RepeatRule = RepeatRule.NONE,
        eventId: Long = 100L
    ) = LocalTodo(
        id = id,
        title = title,
        startMillis = start,
        durationMinutes = duration,
        note = null,
        repeat = repeat,
        eventId = eventId,
        source = SOURCE_NTFY
    )

    private fun calendar(
        id: Long = 100L,
        title: String = "数学作业",
        start: Long = day,
        duration: Int = 60,
        repeat: RepeatRule = RepeatRule.NONE,
        calendarId: Long = 9L
    ) = TodoItem(
        id = id,
        title = title,
        startMillis = start,
        durationMinutes = duration,
        note = null,
        repeat = repeat,
        calendarId = calendarId
    )

    @Test
    fun `完全一致时没有任何差异`() {
        val diffs = Reconciler.compare(listOf(local()), listOf(calendar()))
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun `清单有日历没有算缺失`() {
        // eventId=-1 表示从来没写成功过（权限没了 / 被日历拒绝）
        val diffs = Reconciler.compare(listOf(local(eventId = -1L)), emptyList())
        assertEquals(1, diffs.size)
        assertEquals(DiffKind.MISSING, diffs[0].kind)
    }

    @Test
    fun `写进过日历、现在没了算已删`() {
        val diffs = Reconciler.compare(listOf(local(eventId = 100L)), emptyList())
        assertEquals(1, diffs.size)
        assertEquals(DiffKind.REMOVED, diffs[0].kind)
        assertTrue(diffs[0].detail.contains("之前写进过"))
    }

    @Test
    fun `日历有清单没有算多余`() {
        val diffs = Reconciler.compare(emptyList(), listOf(calendar()))
        assertEquals(1, diffs.size)
        assertEquals(DiffKind.EXTRA, diffs[0].kind)
    }

    @Test
    fun `时长被改算不一致`() {
        val diffs = Reconciler.compare(listOf(local(duration = 60)), listOf(calendar(duration = 30)))
        assertEquals(1, diffs.size)
        assertEquals(DiffKind.CHANGED, diffs[0].kind)
        assertTrue(diffs[0].detail.contains("30"))
    }

    @Test
    fun `重复规则被改算不一致`() {
        val diffs = Reconciler.compare(
            listOf(local(repeat = RepeatRule.WEEKLY)),
            listOf(calendar(repeat = RepeatRule.NONE))
        )
        assertEquals(1, diffs.size)
        assertEquals(DiffKind.CHANGED, diffs[0].kind)
    }

    @Test
    fun `同标题时间被改归为不一致而不是一缺一多`() {
        val diffs = Reconciler.compare(
            listOf(local(eventId = -1L)),
            listOf(calendar(id = 500L, start = day + 3_600_000L))
        )
        assertEquals(1, diffs.size)
        assertEquals(DiffKind.CHANGED, diffs[0].kind)
    }

    @Test
    fun `标题大小写和多余空格不影响配对`() {
        val diffs = Reconciler.compare(
            listOf(local(title = "  Math   Homework ", eventId = -1L)),
            listOf(calendar(title = "math homework"))
        )
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun `eventId 对不上时按标题加时间重新配对`() {
        val diffs = Reconciler.compare(
            listOf(local(eventId = 999L)),
            listOf(calendar(id = 100L))
        )
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun `多条混合差异一次报全`() {
        val diffs = Reconciler.compare(
            listOf(
                local(id = 1, title = "A", start = day, eventId = 100L),
                local(id = 2, title = "B", start = day + 60_000L, eventId = -1L)
            ),
            listOf(
                calendar(id = 100L, title = "A", start = day, duration = 30),
                calendar(id = 200L, title = "C", start = day + 120_000L)
            )
        )
        assertEquals(3, diffs.size)
        assertEquals(1, diffs.count { it.kind == DiffKind.CHANGED })
        assertEquals(1, diffs.count { it.kind == DiffKind.MISSING })
        assertEquals(1, diffs.count { it.kind == DiffKind.EXTRA })
    }
}
