package com.bellnote.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotRulesTest {

    @Test
    fun `系统截图文件名能认出来`() {
        assertTrue(ScreenshotRules.looksLikeScreenshot("Screenshot_2026-11-10-10-22-56.jpg", "Pictures/Screenshots/"))
        assertTrue(ScreenshotRules.looksLikeScreenshot("screen_shot_1.png", "DCIM/"))
    }

    @Test
    fun `中文截图名能认出来`() {
        assertTrue(ScreenshotRules.looksLikeScreenshot("截屏_20261110.png", "Pictures/"))
        assertTrue(ScreenshotRules.looksLikeScreenshot("截图 2026-11-10.png", null))
    }

    @Test
    fun `照片不算截图`() {
        assertFalse(ScreenshotRules.looksLikeScreenshot("IMG_20261109_212907.jpg", "DCIM/Camera/"))
        assertFalse(ScreenshotRules.looksLikeScreenshot("WeChat Image.jpg", "Pictures/WeiXin/"))
    }

    @Test
    fun `目录带 Screenshots 也算`() {
        assertTrue(ScreenshotRules.looksLikeScreenshot("random.png", "Pictures/Screenshots/"))
    }

    @Test
    fun `空名字不算`() {
        assertFalse(ScreenshotRules.looksLikeScreenshot(null, null))
        assertFalse(ScreenshotRules.looksLikeScreenshot("", ""))
    }
}
