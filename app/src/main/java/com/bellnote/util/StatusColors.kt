package com.bellnote.util

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import com.bellnote.R
import com.bellnote.data.StatusLines

/**
 * 状态的配色只在这里定义一次。
 *
 * 为什么单独抽出来：以前「正常 / 提醒 / 未配置」直接借品牌色（bnPrimary / bnAccent / bnCardStroke），
 * 换一套配色主题，同一颗灯的意思就变了（Sand 主题里绿色代表"提醒"、Indigo 主题里蓝色代表"正常"）。
 * 现在状态只用语义色 bnOk / bnWarn / bnDanger / bnMuted，品牌色只负责按钮、图标、选中态。
 */
object StatusColors {

    fun attr(context: Context, attr: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    fun status(context: Context, level: StatusLines.Level): Int = when (level) {
        StatusLines.Level.OK -> attr(context, R.attr.bnOk)
        StatusLines.Level.WARN -> attr(context, R.attr.bnWarn)
        StatusLines.Level.IDLE -> attr(context, R.attr.bnMuted)
    }

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 光晕用色：深色背景「掺白」才像发光；浅色背景反过来「掺黑」，做成投影感，
     * 否则浅色页面上这团光基本看不见。
     */
    fun glow(context: Context, base: Int): Int =
        if (isNight(context)) {
            androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.WHITE, 0.5f)
        } else {
            androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.BLACK, 0.22f)
        }
}
