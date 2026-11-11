package com.bellnote.data

import android.content.Context
import com.bellnote.util.TimeFormat

/**
 * 首页原来那张「一切就绪」卡片压缩成一颗灯以后，卡片里的三行细节（对账 / 补查 / 写失败）
 * 挪到「运行体检」页底部展示。这里只负责把它们算出来，两处都不用再各写一遍。
 */
object StatusLines {

    fun recent(context: Context): List<String> {
        val lines = mutableListOf<String>()

        val lastAt = Prefs.lastReconcileAt(context)
        val summary = Prefs.lastReconcileSummary(context)
        lines += if (lastAt > 0) {
            "对账：$summary（${TimeFormat.full(lastAt)}）"
        } else {
            "还没跑过对账，点抽屉里「对账」看清单和日历差在哪"
        }

        lines += if (Prefs.ntfyTopic(context).isBlank()) {
            "补查：没开（设置里填 ntfy 主题后，ntfy 没弹通知也能补回来）"
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
            "有 $ignored 条推送没写进日历（设置页可看原因）"
        } else {
            "最近没有写失败的推送"
        }
        return lines
    }
}
