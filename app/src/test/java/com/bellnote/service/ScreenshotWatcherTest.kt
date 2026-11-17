package com.bellnote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图提醒的两个「判据」：什么时候该弹、往回翻多深。
 * 对应 2026-11-17 真机 bug：冻结 10 分钟/隔 8 张图后截图不提醒。
 */
class ScreenshotWatcherTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `新截图且窗口内要提醒`() {
        assertTrue(ScreenshotWatcher.shouldNotify(id = 120, lastShotId = 100, addedAt = now - 60_000L, now = now))
    }

    @Test
    fun `比上次提醒过的不新就不提醒`() {
        assertFalse(ScreenshotWatcher.shouldNotify(id = 100, lastShotId = 100, addedAt = now - 60_000L, now = now))
        assertFalse(ScreenshotWatcher.shouldNotify(id = 99, lastShotId = 100, addedAt = now - 60_000L, now = now))
    }

    @Test
    fun `冻结几小时后再回前台仍要提醒`() {
        // 15 分钟窗口时这条会被漏掉，放宽到 4 小时后应该提醒
        assertTrue(ScreenshotWatcher.shouldNotify(120, 100, addedAt = now - 3 * 60 * 60_000L, now = now))
    }

    @Test
    fun `超出新鲜窗口的旧截图不提醒`() {
        val justOver = now - ScreenshotWatcher.FRESH_WINDOW_MS - 1
        assertFalse(ScreenshotWatcher.shouldNotify(120, 100, addedAt = justOver, now = now))
    }

    @Test
    fun `新鲜窗口放宽到 2 到 6 小时之间`() {
        assertTrue(ScreenshotWatcher.FRESH_WINDOW_MS >= 2 * 60 * 60_000L)
        assertTrue(ScreenshotWatcher.FRESH_WINDOW_MS <= 6 * 60 * 60_000L)
    }

    @Test
    fun `扫描深度至少 30 张`() {
        // 原来是 8，隔几张照片就把截图挡在窗口外
        assertEquals(30, ScreenshotWatcher.SCAN_DEPTH)
        assertTrue(ScreenshotWatcher.SCAN_DEPTH > 8)
    }

    @Test
    fun `时间戳异常时不提醒`() {
        assertFalse(ScreenshotWatcher.shouldNotify(120, 100, addedAt = 0L, now = now))
        assertFalse(ScreenshotWatcher.shouldNotify(120, 100, addedAt = now + 60_000L, now = now))
    }
}
