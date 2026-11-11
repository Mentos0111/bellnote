package com.bellnote.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面上的「Bellnote 截屏记」入口（供系统侧边栏添加用）。
 *
 * 它是个默认 disabled 的 activity-alias：平时桌面不出现，需要往侧边栏里加时
 * 在设置里一键打开，加完再关掉。侧边栏里已经加过的入口不受影响
 * （侧边栏保存的是 targetActivity 的 ComponentName，alias 关掉也能启动）。
 */
object SidebarEntry {

    private const val ALIAS = "com.bellnote.ui.SidebarEntry"

    private fun component(context: Context) = ComponentName(context.packageName, ALIAS)

    fun isEnabled(context: Context): Boolean {
        val flags = context.packageManager.getComponentEnabledSetting(component(context))
        return flags == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    /** 返回设置后的状态（true=已显示）。 */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        return try {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            enabled
        } catch (e: Exception) {
            // 某些 ROM 上 alias 开关会失败，读回真实状态
            isEnabled(context)
        }
    }
}
