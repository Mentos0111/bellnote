package com.bellnote.util

import android.app.Activity
import com.bellnote.R
import com.bellnote.data.Prefs

/**
 * 配色主题：4 套调色板，浅色/深色各一份（`values/` 与 `values-night/` 里同名 overlay）。
 *
 * 实现方式：所有界面颜色都写成主题属性（`?attr/bnPrimary` 这类），
 * 换主题时只把对应的 `ThemeOverlay` 叠到当前 Activity 的 theme 上，布局不用动。
 */
object ThemeManager {

    data class Palette(val id: String, val label: String)

    val palettes = listOf(
        Palette("teal", "青绿（默认）"),
        Palette("indigo", "靛蓝"),
        Palette("sand", "暖沙"),
        Palette("ink", "墨黑")
    )

    fun labelOf(id: String): String =
        palettes.firstOrNull { it.id == id }?.label ?: palettes.first().label

    private fun overlayOf(id: String): Int = when (id) {
        "indigo" -> R.style.ThemeOverlay_Bellnote_Indigo
        "sand" -> R.style.ThemeOverlay_Bellnote_Sand
        "ink" -> R.style.ThemeOverlay_Bellnote_Ink
        else -> R.style.ThemeOverlay_Bellnote_Teal
    }

    private val applied = java.util.WeakHashMap<Activity, String>()

    /** 在 Activity.setContentView 之前调用一次。 */
    fun apply(activity: Activity) {
        val id = Prefs.theme(activity)
        activity.theme.applyStyle(overlayOf(id), true)
        applied[activity] = id
    }

    /**
     * 用户在别的页面换了主题、又回退到还活着的页面时，这里返回 true，
     * 调用方 recreate() 一下就换成新配色。
     */
    fun refreshIfNeeded(activity: Activity): Boolean {
        val appliedId = applied[activity] ?: return false
        if (appliedId == Prefs.theme(activity)) return false
        applied.remove(activity)
        return true
    }
}
