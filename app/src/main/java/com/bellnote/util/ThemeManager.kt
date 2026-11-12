package com.bellnote.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
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
        Palette("ink", "墨黑"),
        Palette("pink", "胭脂"),
        Palette("sakura", "樱粉")
    )

    fun labelOf(id: String): String =
        palettes.firstOrNull { it.id == id }?.label ?: palettes.first().label

    data class NightMode(val id: String, val label: String)

    val nightModes = listOf(
        NightMode("system", "跟随系统"),
        NightMode("light", "浅色"),
        NightMode("dark", "深色")
    )

    fun nightModeLabel(id: String): String =
        nightModes.firstOrNull { it.id == id }?.label ?: nightModes.first().label

    private fun nightModeOf(id: String): Int = when (id) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** 进程启动时套一次；用户在设置里切换时再调一次，AppCompat 会自己重建页面。 */
    fun applyNightMode(context: Context) {
        val target = nightModeOf(Prefs.themeMode(context))
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    private val iconAliases = mapOf(
        "teal" to ".ui.IconTeal",
        "indigo" to ".ui.IconIndigo",
        "sand" to ".ui.IconSand",
        "ink" to ".ui.IconInk",
        "pink" to ".ui.IconPink",
        "sakura" to ".ui.IconSakura"
    )

    /**
     * 桌面图标跟着配色走：四套图标各挂在一个 activity-alias 上，只开当前这套。
     * 注意：部分桌面（realme / ColorOS）会缓存图标，切完可能要等桌面刷新一次才看到。
     */
    private var iconPending = false

    /**
     * 改了配色先记账，等 App 退到后台再真正换桌面图标。
     * 原因：禁用正在用的那个 activity-alias 会让系统把当前任务一起收掉，
     * 用户看到的就是「切个主题 App 被关了」。后台切换虽然也会结束进程，但用户看不见。
     */
    fun scheduleLauncherIcon() {
        iconPending = true
    }

    /** App 退到后台（所有页面都 stop）时调用。 */
    fun applyPendingLauncherIcon(context: Context) {
        if (!iconPending) return
        iconPending = false
        applyLauncherIcon(context)
    }

    fun applyLauncherIcon(context: Context) {
        val current = Prefs.theme(context)
        val pm = context.packageManager
        for ((id, alias) in iconAliases) {
            val state = if (id == current) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val name = ComponentName(context.packageName, context.packageName + alias)
            val now = pm.getComponentEnabledSetting(name)
            if (now != state) {
                pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun overlayOf(id: String): Int = when (id) {
        "indigo" -> R.style.ThemeOverlay_Bellnote_Indigo
        "sand" -> R.style.ThemeOverlay_Bellnote_Sand
        "ink" -> R.style.ThemeOverlay_Bellnote_Ink
        "pink" -> R.style.ThemeOverlay_Bellnote_Pink
        "sakura" -> R.style.ThemeOverlay_Bellnote_Sakura
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
