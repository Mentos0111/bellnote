package com.bellnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 详细日志的 24 小时生命周期（纯函数部分）。 */
class VerboseLogTest {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private fun line(at: Long, text: String = "msg"): String = "${stamp.format(Date(at))} [收] $text"

    @Test
    fun `开启后未到 24 小时不算过期`() {
        val now = 1_800_000_000_000L
        assertFalse(VerboseLog.isExpired(now - 23 * 3600_000L, now))
        assertFalse(VerboseLog.isExpired(now - 1000L, now))
    }

    @Test
    fun `超过 24 小时算过期 关闭状态也算关`() {
        val now = 1_800_000_000_000L
        assertTrue(VerboseLog.isExpired(now - 25 * 3600_000L, now))
        assertFalse(VerboseLog.isExpired(0L, now))
    }

    @Test
    fun `裁剪只保留 24 小时内的行`() {
        val now = 1_800_000_000_000L
        val fresh = line(now - 60_000L, "新消息")
        val old = line(now - 25 * 3600_000L, "旧消息")
        val kept = VerboseLog.keepRecent(listOf(old, fresh), now)
        assertEquals(listOf(fresh), kept)
    }

    @Test
    fun `没有时间戳的垃圾行会被丢掉`() {
        val now = 1_800_000_000_000L
        val kept = VerboseLog.keepRecent(listOf("没有时间戳的一行", ""), now)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `保留窗口常量是 24 小时`() {
        assertEquals(24L * 60 * 60 * 1000, VerboseLog.TTL_MS)
    }
}
