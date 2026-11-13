package com.bellnote.data

/**
 * 首页那颗状态灯的判定逻辑。
 *
 * 设计原则：检测跑在后台，用户只看结果 —— 所以这里只负责「一堆检查项 → 一个结论」，
 * 不碰 Android API，纯函数，能单测。
 */
object HealthCheck {

    enum class Status(val weight: Int) {
        OK(0),
        UNKNOWN(1),
        WARN(2),
        BAD(3)
    }

    enum class Action {
        NONE,
        NOTIFICATION_ACCESS,
        ACCESSIBILITY,
        BATTERY,
        AUTOSTART,
        CALENDAR,
        SETTINGS
    }

    data class Item(
        val id: String,
        /** 灯旁边显示的短描述，例如「自启动」 */
        val label: String,
        val status: Status,
        val detail: String,
        val action: Action = Action.NONE
    )

    /**
     * 只在 ntfy 模式开着时才该出现的检查项。
     * 模式关着时它们连「无法判定」都不该算（否则首页灯会显示「可优化」），
     * 所以缓存里的旧结果也要按当前模式再滤一遍。
     */
    val NTFY_ONLY_IDS = setOf("ntfy_poll", "battery", "autostart")

    /** 按当前模式过滤：ntfy 关着就藏掉补查 / 后台 / 自启动三项。 */
    fun visible(items: List<Item>, ntfyOn: Boolean): List<Item> =
        if (ntfyOn) items else items.filterNot { it.id in NTFY_ONLY_IDS }

    /** 取最严重的那一项 —— 首页的灯只显示它。 */
    fun indicator(items: List<Item>): Item? = items.maxByOrNull { it.status.weight }

    /** 需要用户处理的项（WARN 及以上）。 */
    fun pending(items: List<Item>): List<Item> = items.filter { it.status.weight >= Status.WARN.weight }

    fun summary(items: List<Item>): String {
        val pending = pending(items)
        return when {
            pending.isEmpty() -> "一切正常"
            pending.size == 1 -> "1 项可优化"
            else -> "${pending.size} 项可优化"
        }
    }
}
