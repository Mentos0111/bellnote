package com.bellnote.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.bellnote.data.CalendarRepository
import com.bellnote.data.HealthCheck
import com.bellnote.data.Prefs
import com.bellnote.util.DeviceVendor
import com.bellnote.util.Permissions

/**
 * 后台体检：跑一遍所有「会影响待办进日历」的开关，只把结论留给界面。
 *
 * 顺序按「普通模式」排：日历 → 通知 → 截屏 → 云端 / 本地模型；
 * ntfy 模式开着才在后面接上补查 / 后台 / 自启动三项（关着就完全不出现，
 * 连缓存里的旧结果也会被 [HealthCheck.visible] 滤掉）。
 *
 * 原则：
 * - 能在代码里判定的，给准确结论（日历权限、通知权限、电池白名单、无障碍、补查配置）；
 * - **判不了的诚实标注**：各家 ROM 的自启动开关没有公开 API，只能按「电池白名单 + 厂商」推断，
 *   详情里会写明「系统不开放读取」，不假装知道；
 * - 结果缓存在 Prefs 里，首页直接读缓存，不用等检测跑完。
 */
object HealthInspector {

    private const val TAG = "HealthInspector"
    private const val SEP = "\u0001"
    private const val FIELD = "\u0002"

    fun inspect(context: Context): List<HealthCheck.Item> {
        val app = context.applicationContext
        val items = mutableListOf<HealthCheck.Item>()
        val vendor = DeviceVendor.current()

        // 1) 日历权限
        val hasCalendar = Permissions.hasCalendar(app)
        items.add(
            if (hasCalendar) {
                HealthCheck.Item(
                    "calendar_permission", "日历权限", HealthCheck.Status.OK,
                    "已授权读写日历", HealthCheck.Action.CALENDAR
                )
            } else {
                HealthCheck.Item(
                    "calendar_permission", "日历权限", HealthCheck.Status.BAD,
                    "没有日历权限：待办写不进去",
                    HealthCheck.Action.CALENDAR
                )
            }
        )

        // 2) 目标日历（没权限就不用查了，查了也读不到）
        if (hasCalendar) {
            val calendarId = try {
                CalendarRepository(app).defaultCalendarId()
            } catch (e: Exception) {
                Log.w(TAG, "读目标日历失败", e)
                -1L
            }
            items.add(
                if (calendarId > 0) {
                    HealthCheck.Item(
                        "calendar_target", "目标日历", HealthCheck.Status.OK,
                        "能写入日历（id=$calendarId）", HealthCheck.Action.CALENDAR
                    )
                } else {
                    HealthCheck.Item(
                        "calendar_target", "目标日历", HealthCheck.Status.WARN,
                        "还没有可写的日历：在设置里点「创建本地日历」",
                        HealthCheck.Action.CALENDAR
                    )
                }
            )
        }

        // 3) 通知权限（只影响 App 自己的提醒；补查不再依赖它）
        items.add(
            if (Permissions.hasPostNotifications(app)) {
                HealthCheck.Item(
                    "notify", "通知权限", HealthCheck.Status.OK,
                    "能发提醒通知", HealthCheck.Action.SETTINGS
                )
            } else {
                HealthCheck.Item(
                    "notify", "通知权限", HealthCheck.Status.WARN,
                    "没允许通知：App 自己的提醒发不出来（日历事件仍由日历 App 提醒）",
                    HealthCheck.Action.SETTINGS
                )
            }
        )

        // 4) 无障碍截屏（侧边栏入口用；不开也能用别的方式建待办）
        val a11y = ScreenCaptureService.isEnabledInSettings(app) || ScreenCaptureService.isConnected()
        items.add(
            if (a11y) {
                HealthCheck.Item(
                    "a11y", "截屏服务", HealthCheck.Status.OK,
                    "截屏服务已开，侧边栏点一下就能截屏识别",
                    HealthCheck.Action.ACCESSIBILITY
                )
            } else {
                HealthCheck.Item(
                    "a11y", "截屏服务", HealthCheck.Status.WARN,
                    "没开截屏服务：侧边栏入口点了没反应（只影响截屏，不影响推送）",
                    HealthCheck.Action.ACCESSIBILITY
                )
            }
        )

        // 5) 云端模型（关着 = 正常，别用 UNKNOWN 去凑「可优化」）
        val cloudOn = Prefs.cloudModelEnabled(app)
        val hasKey = Prefs.apiKey(app).isNotBlank()
        val cloudModel = Prefs.model(app)
        val cloudVision = com.bellnote.ai.CloudModels.inspect(cloudModel).vision
        items.add(
            when {
                !cloudOn -> HealthCheck.Item(
                    "cloud_model", "云端模型", HealthCheck.Status.OK,
                    "关着：识别只用本地规则或本地模型", HealthCheck.Action.SETTINGS
                )
                !hasKey -> HealthCheck.Item(
                    "cloud_model", "云端模型", HealthCheck.Status.WARN,
                    "开着但没填 API Key，现在不会调用云端", HealthCheck.Action.SETTINGS
                )
                !cloudVision -> HealthCheck.Item(
                    "cloud_model", "云端模型", HealthCheck.Status.WARN,
                    "当前模型 $cloudModel 看着像纯文本模型，看不清截图", HealthCheck.Action.SETTINGS
                )
                else -> HealthCheck.Item(
                    "cloud_model", "云端模型", HealthCheck.Status.OK,
                    "已开：$cloudModel", HealthCheck.Action.SETTINGS
                )
            }
        )

        // 6) 本地模型
        val localOn = Prefs.localModelEnabled(app)
        val localModel = com.bellnote.ai.LocalModels.selected(app)
        val localReady = com.bellnote.ai.LocalModels.isReady(app, localModel)
        items.add(
            when {
                !localOn -> HealthCheck.Item(
                    "local_model", "本地模型", HealthCheck.Status.OK,
                    "关着：不占空间；需要离线理解口语时再开", HealthCheck.Action.SETTINGS
                )
                !localReady -> HealthCheck.Item(
                    "local_model", "本地模型", HealthCheck.Status.WARN,
                    "开着但 ${localModel.name} 没下载完，这一路会被跳过", HealthCheck.Action.SETTINGS
                )
                else -> HealthCheck.Item(
                    "local_model", "本地模型", HealthCheck.Status.OK,
                    "已就绪：${localModel.name}（${localModel.sizeMb} MB）", HealthCheck.Action.SETTINGS
                )
            }
        )

        // 7–9) 只有 ntfy 模式开着才检查：补查、后台、自启动
        val modeOn = Prefs.ntfyEnabled(app)
        if (modeOn) {
            val topic = Prefs.ntfyTopic(app)
            val lastAt = Prefs.lastCatchUpAt(app)
            val hours = if (lastAt > 0) (System.currentTimeMillis() - lastAt) / 3_600_000L else -1
            items.add(
                if (topic.isBlank()) {
                    HealthCheck.Item(
                        "ntfy_poll", "自动补查", HealthCheck.Status.WARN,
                        "ntfy 模式开着但没填主题：进「第三方通道 → ntfy 模式」填一个主题才会开始拉取",
                        HealthCheck.Action.SETTINGS
                    )
                } else {
                    when {
                        lastAt <= 0 -> HealthCheck.Item(
                            "ntfy_poll", "自动补查", HealthCheck.Status.WARN,
                            "配了主题但还没成功拉过：检查网络，或在推送页点「立即补查」",
                            HealthCheck.Action.SETTINGS
                        )

                        hours > 6 -> HealthCheck.Item(
                            "ntfy_poll", "自动补查", HealthCheck.Status.WARN,
                            "上次补查是 ${hours} 小时前：可能被系统限制了后台，把电池白名单打开",
                            HealthCheck.Action.BATTERY
                        )

                        else -> HealthCheck.Item(
                            "ntfy_poll", "自动补查", HealthCheck.Status.OK,
                            "每 15 分钟主动拉一次，不依赖任何人的通知权限",
                            HealthCheck.Action.SETTINGS
                        )
                    }
                }
            )

            val ignoringBattery = isIgnoringBatteryOptimizations(app)
            items.add(
                if (ignoringBattery) {
                    HealthCheck.Item(
                        "battery", "后台", HealthCheck.Status.OK,
                        "已加入电池优化白名单", HealthCheck.Action.BATTERY
                    )
                } else {
                    HealthCheck.Item(
                        "battery", "后台", HealthCheck.Status.BAD,
                        "没加入电池白名单：系统会冻结后台，定时补查会停",
                        HealthCheck.Action.BATTERY
                    )
                }
            )

            // 自启动：系统不开放读取，只能按厂商 + 电池策略推断
            items.add(
                when {
                    !vendor.known -> HealthCheck.Item(
                        "autostart", "自启动", HealthCheck.Status.UNKNOWN,
                        "原生 Android 一般不需要单独开自启动", HealthCheck.Action.AUTOSTART
                    )
                    ignoringBattery -> HealthCheck.Item(
                        "autostart", "自启动", HealthCheck.Status.OK,
                        "电池策略已放行；若仍收不到，去「${vendor.label}」的自启动管理里把 Bellnote 打开",
                        HealthCheck.Action.AUTOSTART
                    )
                    else -> HealthCheck.Item(
                        "autostart", "自启动", HealthCheck.Status.BAD,
                        "${vendor.label} 的自启动开关系统不开放读取，按电池策略推断可能没开：" +
                            "去自启动管理里允许 Bellnote 自启动 / 后台运行",
                        HealthCheck.Action.AUTOSTART
                    )
                }
            )
        }

        Log.i(TAG, "体检完成：" + items.joinToString { "${it.label}=${it.status}" })
        return items
    }

    /** 后台跑一遍并写缓存，界面回调时直接读缓存。 */
    fun refreshInBackground(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        Thread {
            try {
                val items = inspect(app)
                Prefs.setHealthCache(app, serialize(items), System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "体检失败", e)
            }
            onDone?.let { callback ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback() }
            }
        }.start()
    }

    /** 读缓存；从来没跑过就返回空列表（界面显示「检测中」）。按当前模式滤掉不该出现的项。 */
    fun cached(context: Context): List<HealthCheck.Item> =
        HealthCheck.visible(deserialize(Prefs.healthCache(context)), Prefs.ntfyEnabled(context))

    fun cachedAt(context: Context): Long = Prefs.healthCachedAt(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return try {
            manager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- 序列化（存 Prefs） ----------------

    fun serialize(items: List<HealthCheck.Item>): String = items.joinToString("\n") { item ->
        listOf(item.id, item.label, item.status.name, item.action.name, item.detail.replace("\n", " "))
            .joinToString(FIELD)
    }

    fun deserialize(raw: String): List<HealthCheck.Item> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split(FIELD)
            if (parts.size < 5) return@mapNotNull null
            HealthCheck.Item(
                id = parts[0],
                label = parts[1],
                status = runCatching { HealthCheck.Status.valueOf(parts[2]) }.getOrDefault(HealthCheck.Status.UNKNOWN),
                action = runCatching { HealthCheck.Action.valueOf(parts[3]) }.getOrDefault(HealthCheck.Action.NONE),
                detail = parts[4]
            )
        }
    }
}
