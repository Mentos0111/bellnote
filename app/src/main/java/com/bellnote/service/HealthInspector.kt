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
 * 后台体检：跑一遍所有「会影响推送进日历」的开关，只把结论留给界面。
 *
 * 原则：
 * - 能在代码里判定的，给准确结论（通知使用权、日历权限、电池白名单、无障碍、补查配置）；
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

        // 1) 通知使用权
        val hasNotificationAccess = Permissions.hasNotificationAccess(app)
        if (!hasNotificationAccess) {
            items.add(
                HealthCheck.Item(
                    "notification_access", "通知权限", HealthCheck.Status.BAD,
                    "没开通知使用权：ntfy 的通知读不到，推送进不了日历",
                    HealthCheck.Action.NOTIFICATION_ACCESS
                )
            )
        } else {
            items.add(
                HealthCheck.Item(
                    "notification_access", "通知权限", HealthCheck.Status.OK,
                    "已授权，ntfy 的通知能被读取", HealthCheck.Action.NOTIFICATION_ACCESS
                )
            )
        }

        // 2) 监听器是否被系统回收
        if (hasNotificationAccess) {
            val connectedAt = Prefs.listenerConnectedAt(app)
            val stale = connectedAt > 0 && System.currentTimeMillis() - connectedAt > 24 * 60 * 60 * 1000L
            items.add(
                if (stale) {
                    HealthCheck.Item(
                        "listener", "监听", HealthCheck.Status.WARN,
                        "授权还在，但超过 24 小时没连上，可能被系统回收了：去通知使用权里关一下再打开",
                        HealthCheck.Action.NOTIFICATION_ACCESS
                    )
                } else {
                    HealthCheck.Item(
                        "listener", "监听", HealthCheck.Status.OK,
                        "监听服务正常", HealthCheck.Action.NOTIFICATION_ACCESS
                    )
                }
            )
        }

        // 3) 日历权限与目标日历
        val hasCalendar = Permissions.hasCalendar(app)
        items.add(
            if (hasCalendar) {
                HealthCheck.Item(
                    "calendar_permission", "日历", HealthCheck.Status.OK,
                    "已授权读写日历", HealthCheck.Action.CALENDAR
                )
            } else {
                HealthCheck.Item(
                    "calendar_permission", "日历", HealthCheck.Status.BAD,
                    "没有日历权限：待办写不进去",
                    HealthCheck.Action.CALENDAR
                )
            }
        )
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

        // 4) 后台 / 电池
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
                    "没加入电池白名单：系统会冻结后台，通知可能收不到",
                    HealthCheck.Action.BATTERY
                )
            }
        )

        // 5) 自启动 / 后台运行（系统不开放读取，只能按厂商 + 电池策略推断）
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

        // 6) 无障碍截屏（侧边栏入口用）
        val a11y = ScreenCaptureService.isEnabledInSettings(app) || ScreenCaptureService.isConnected()
        items.add(
            if (a11y) {
                HealthCheck.Item(
                    "a11y", "侧边栏", HealthCheck.Status.OK,
                    "截屏服务已开，侧边栏点一下就能截屏识别",
                    HealthCheck.Action.ACCESSIBILITY
                )
            } else {
                HealthCheck.Item(
                    "a11y", "侧边栏", HealthCheck.Status.WARN,
                    "没开截屏服务：侧边栏入口点了没反应（只影响截屏，不影响推送）",
                    HealthCheck.Action.ACCESSIBILITY
                )
            }
        )

        // 7) ntfy 补查
        val topic = Prefs.ntfyTopic(app)
        if (topic.isBlank()) {
            items.add(
                HealthCheck.Item(
                    "catchup", "补查", HealthCheck.Status.WARN,
                    "没填 ntfy 主题：ntfy App 不弹通知时就收不到消息了",
                    HealthCheck.Action.SETTINGS
                )
            )
        } else {
            val summary = Prefs.lastCatchUpSummary(app)
            val failed = summary.contains("失败")
            items.add(
                if (failed) {
                    HealthCheck.Item(
                        "catchup", "补查", HealthCheck.Status.BAD,
                        "上次补查失败：$summary", HealthCheck.Action.SETTINGS
                    )
                } else {
                    HealthCheck.Item(
                        "catchup", "补查", HealthCheck.Status.OK,
                        if (summary.isBlank()) "已配置主题，还没跑过补查" else summary,
                        HealthCheck.Action.SETTINGS
                    )
                }
            )
        }

        // 8) 最近没写进日历的推送
        val ignored = Prefs.ignoredNotices(app)
        if (ignored.isNotEmpty()) {
            val latest = ignored.first()
            items.add(
                HealthCheck.Item(
                    "ignored", "推送", HealthCheck.Status.WARN,
                    "最近有 ${ignored.size} 条推送没写进日历（最新：${latest.reason}）",
                    HealthCheck.Action.SETTINGS
                )
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

    /** 读缓存；从来没跑过就返回空列表（界面显示「检测中」）。 */
    fun cached(context: Context): List<HealthCheck.Item> = deserialize(Prefs.healthCache(context))

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
