package com.bellnote.data

import android.content.Context
import com.bellnote.util.TimeFormat

/**
 * 首页原来那张「一切就绪」卡片压缩成一颗灯以后，卡片里的三行细节（对账 / 补查 / 写失败）
 * 挪到「运行体检」页底部展示，另外首页那颗「同步」灯也用它判断状态。
 */
object StatusLines {

    enum class Level { OK, WARN, IDLE }

    /** 首页「同步」灯该亮什么颜色、写什么字。 */
    fun level(context: Context): Level {
        if (Prefs.ignoredNotices(context).isNotEmpty()) return Level.WARN
        val reconciled = Prefs.lastReconcileAt(context)
        if (reconciled > 0 && Prefs.lastReconcileSummary(context) != "一致") return Level.WARN
        if (!Prefs.ntfyEnabled(context) || Prefs.ntfyTopic(context).isBlank()) return Level.IDLE
        return Level.OK
    }

    fun chipLabel(context: Context): String {
        val ignored = Prefs.ignoredNotices(context).size
        if (ignored > 0) return "$ignored 条未写入"
        val reconciled = Prefs.lastReconcileAt(context)
        if (reconciled > 0 && Prefs.lastReconcileSummary(context) != "一致") return "对账有差"
        if (!Prefs.ntfyEnabled(context)) return "推送未开"
        if (Prefs.ntfyTopic(context).isBlank()) return "补查未开"
        return "同步正常"
    }

    fun recent(context: Context): List<String> {
        val lines = mutableListOf<String>()

        val lastAt = Prefs.lastReconcileAt(context)
        val summary = Prefs.lastReconcileSummary(context)
        lines += if (lastAt > 0) {
            "对账：$summary（${TimeFormat.full(lastAt)}）"
        } else {
            "还没跑过对账，点抽屉里「对账」看清单和日历差在哪"
        }

        lines += if (!Prefs.ntfyEnabled(context)) {
            "补查：ntfy 模式关着（打开后才会去服务器拉消息）"
        } else if (Prefs.ntfyTopic(context).isBlank()) {
            "补查：没填主题（设置 → 输入渠道 → 其他入口 → 第三方里填）"
        } else {
            val catchAt = Prefs.lastCatchUpAt(context)
            val catchSummary = Prefs.lastCatchUpSummary(context)
            when {
                catchAt <= 0 -> "补查：开了，还没跑过"
                // summary 本身已经带「补查：」前缀，别再叠一层
                catchSummary.startsWith("补查") -> catchSummary
                else -> "补查：$catchSummary"
            }
        }

        val ignored = Prefs.ignoredNotices(context).size
        lines += if (ignored > 0) {
            "有 $ignored 条推送没写进日历（进「第三方」→ 消息记录里可看原因）"
        } else {
            "最近没有写失败的推送"
        }
        return lines
    }
}
