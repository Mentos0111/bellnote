package com.bellnote.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 认手机是哪个厂家的，然后按厂家给「自启动 / 后台运行 / 智能侧边栏」的正确入口。
 *
 * 以前只写了 realme（ColorOS）的组件名，换一台小米或荣耀就全部跳回应用详情页、
 * 用户还得自己找。这里把主流 ROM 都列上，找不到就退回通用页面。
 */
enum class Vendor(val label: String) {
    REALME("realme"),
    OPPO("OPPO"),
    ONEPLUS("一加"),
    XIAOMI("小米 / 红米"),
    REDMI("红米"),
    VIVO("vivo / iQOO"),
    HUAWEI("华为"),
    HONOR("荣耀"),
    SAMSUNG("三星"),
    MEIZU("魅族"),
    ASUS("华硕"),
    NUBIA("努比亚 / 红魔"),
    GOOGLE("原生 Android"),
    OTHER("Android");

    val known: Boolean get() = this != OTHER
}

object DeviceVendor {

    private const val TAG = "DeviceVendor"

    fun current(): Vendor {
        val brand = (Build.BRAND ?: "").lowercase()
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val both = "$brand $manufacturer"
        return when {
            both.contains("realme") -> Vendor.REALME
            both.contains("oneplus") -> Vendor.ONEPLUS
            both.contains("oppo") || both.contains("oplus") -> Vendor.OPPO
            both.contains("redmi") -> Vendor.REDMI
            both.contains("xiaomi") || both.contains("mi ") || both.contains("poco") -> Vendor.XIAOMI
            both.contains("vivo") || both.contains("iqoo") -> Vendor.VIVO
            both.contains("huawei") -> Vendor.HUAWEI
            both.contains("honor") -> Vendor.HONOR
            both.contains("samsung") -> Vendor.SAMSUNG
            both.contains("meizu") -> Vendor.MEIZU
            both.contains("asus") -> Vendor.ASUS
            both.contains("nubia") || both.contains("redmagic") -> Vendor.NUBIA
            both.contains("google") -> Vendor.GOOGLE
            else -> Vendor.OTHER
        }
    }

    /** 自启动管理页：多试几个组件名，成功一个就停。 */
    fun openAutostart(context: Context): Boolean {
        for (component in autostartCandidates(current())) {
            if (SystemPages.start(context, Intent().setComponent(component))) return true
        }
        return SystemPages.openAppDetails(context)
    }

    /** 省电 / 后台耗电白名单页。 */
    fun openBattery(context: Context): Boolean {
        for (component in batteryCandidates(current())) {
            if (SystemPages.start(context, Intent().setComponent(component))) return true
        }
        if (SystemPages.start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
        return SystemPages.openAppDetails(context)
    }

    /** 无障碍设置页（截屏识别要用）。 */
    fun openAccessibility(context: Context): Boolean =
        SystemPages.start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /**
     * 「怎么把 Bellnote 加进系统侧边栏」——系统侧边栏没有公开接口，只能告诉用户在哪儿点。
     * 路径按厂家写；不确定的给通用说法。
     */
    fun sidebarGuide(vendor: Vendor = current()): String = when (vendor) {
        Vendor.REALME, Vendor.OPPO, Vendor.ONEPLUS ->
            "设置 → 特色功能（或便捷工具）→ 智能侧边栏 → 编辑 → 添加「Bellnote 截屏记」"
        Vendor.XIAOMI, Vendor.REDMI ->
            "设置 → 特色功能 → 全局侧边栏（或侧边栏）→ 编辑 → 添加「Bellnote 截屏记」"
        Vendor.VIVO ->
            "设置 → 快捷与辅助 → 智能侧边栏 → 编辑 → 添加「Bellnote 截屏记」"
        Vendor.HUAWEI, Vendor.HONOR ->
            "设置 → 辅助功能 → 智慧多窗 / 侧边栏 → 编辑 → 添加「Bellnote 截屏记」"
        Vendor.SAMSUNG ->
            "设置 → 显示 → 侧屏幕面板 → 面板 → 添加「Bellnote 截屏记」"
        else ->
            "在系统的「侧边栏 / 智能侧边栏 / 快捷侧边栏」设置里，把「Bellnote 截屏记」加进去"
    }

    /** 后台限制的通用建议，按厂家微调措辞。 */
    fun backgroundGuide(vendor: Vendor = current()): String = when (vendor) {
        Vendor.REALME, Vendor.OPPO, Vendor.ONEPLUS ->
            "允许自启动 + 电池里选「不优化」/「允许后台运行」，并把 Bellnote 从「最近任务」里锁定"
        Vendor.XIAOMI, Vendor.REDMI ->
            "开启「自启动」+ 省电策略选「无限制」+ 最近任务里给 Bellnote 上锁"
        Vendor.VIVO ->
            "开启「自启动」+ 后台耗电管理选「允许后台高耗电」"
        Vendor.HUAWEI, Vendor.HONOR ->
            "应用启动管理里关掉「自动管理」，手动允许自启动 / 关联启动 / 后台活动"
        else ->
            "把 Bellnote 加入电池优化白名单，并允许后台运行"
    }

    private fun autostartCandidates(vendor: Vendor): List<ComponentName> = when (vendor) {
        Vendor.REALME, Vendor.OPPO, Vendor.ONEPLUS -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        )
        Vendor.XIAOMI, Vendor.REDMI -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
        )
        Vendor.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        )
        Vendor.HUAWEI, Vendor.HONOR -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        )
        Vendor.SAMSUNG -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        Vendor.MEIZU -> listOf(
            ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
        )
        else -> emptyList()
    }

    private fun batteryCandidates(vendor: Vendor): List<ComponentName> = when (vendor) {
        Vendor.REALME, Vendor.OPPO, Vendor.ONEPLUS -> listOf(
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")
        )
        Vendor.XIAOMI, Vendor.REDMI -> listOf(
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        )
        Vendor.VIVO -> listOf(
            ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        )
        Vendor.HUAWEI, Vendor.HONOR -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")
        )
        else -> emptyList()
    }

    fun log(context: Context) {
        Log.i(TAG, "当前厂商=${current().label} 品牌=${Build.BRAND} 型号=${Build.MODEL}")
    }
}
