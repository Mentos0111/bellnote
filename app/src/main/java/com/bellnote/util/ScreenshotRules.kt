package com.bellnote.util

/** 判断一张新图是不是「截图」——纯函数，方便单测。 */
object ScreenshotRules {

    private val NAME_HINTS = listOf("screenshot", "screen shot", "screen_shot", "截屏", "截图")

    /**
     * 相机拍的照片不该弹「要不要识别」，只认名字或目录里带截图字样的。
     * 例：Screenshot_2026-11-10-10-22-56.jpg / Pictures/Screenshots/xxx.png / 截屏_20261110.png
     */
    fun looksLikeScreenshot(displayName: String?, relativePath: String?): Boolean {
        val name = displayName?.lowercase().orEmpty()
        val path = relativePath?.lowercase().orEmpty()
        if (name.isBlank() && path.isBlank()) return false
        if (NAME_HINTS.any { name.contains(it) }) return true
        return path.contains("screenshot") || path.contains("截屏") || path.contains("截图")
    }
}
