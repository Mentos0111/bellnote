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
        val shotAt = now - 60_000L
        assertFalse(
            ScreenshotWatcher.shouldNotify(
                id = 100, lastShotId = 100, addedAt = shotAt, now = now, lastShotAt = shotAt
            )
        )
        assertFalse(
            ScreenshotWatcher.shouldNotify(
                id = 99, lastShotId = 100, addedAt = shotAt, now = now, lastShotAt = shotAt
            )
        )
    }

    @Test
    fun `没有上次时间记录时宁可多弹一次`() {
        // 3.21.3 之前没存 lastShotAt，升级后第一次补查允许重弹一张，不能因此永远不弹
        assertTrue(
            ScreenshotWatcher.shouldNotify(
                id = 100, lastShotId = 100, addedAt = now - 60_000L, now = now, lastShotAt = -1L
            )
        )
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

    @Test
    fun `相册 id 被重置后靠入库时间兜底提醒`() {
        // 换机还原 / 相册库重建时 id 会从头来，只比 id 就永远不弹了
        assertTrue(
            ScreenshotWatcher.shouldNotify(
                id = 5, lastShotId = 980, addedAt = now - 30_000L, now = now,
                lastShotAt = now - 10 * 60_000L
            )
        )
    }

    @Test
    fun `同一张截图在补查的每一次都不能重复弹`() {
        // 相册回调后会连查 5 次：已经提醒过的那张，后面几次必须判成不提醒
        val shotAt = now - 30_000L
        assertFalse(
            ScreenshotWatcher.shouldNotify(
                id = 990, lastShotId = 990, addedAt = shotAt, now = now, lastShotAt = shotAt
            )
        )
    }

    @Test
    fun `补查要覆盖 5 秒以上`() {
        // 入库延迟偶发到 3–5 秒，只查一次会漏
        assertTrue(ScreenshotWatcher.SETTLE_DELAYS_MS.size >= 3)
        assertTrue(ScreenshotWatcher.SETTLE_DELAYS_MS.last() >= 5_000L)
        assertEquals(0L, ScreenshotWatcher.SETTLE_DELAYS_MS.first())
    }

    @Test
    fun `连截几张时每条通知都要换编号`() {
        // 同一个编号连发第二次，系统只更新内容、不再弹横幅——这就是「短时间连截不弹」
        val first = ScreenshotWatcher.nextNotifyId(0)
        val second = ScreenshotWatcher.nextNotifyId(first)
        val third = ScreenshotWatcher.nextNotifyId(second)
        assertTrue(second != first)
        assertTrue(third != second)
        assertTrue(first >= 4101)
    }

    @Test
    fun `通知编号用久了要绕回起点且不越界`() {
        val nearEnd = 4101 + 999
        val wrapped = ScreenshotWatcher.nextNotifyId(nearEnd)
        assertEquals(4101, wrapped)
        assertTrue(ScreenshotWatcher.nextNotifyId(12345) >= 4101)
    }
}
