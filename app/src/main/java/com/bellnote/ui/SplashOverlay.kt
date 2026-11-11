package com.bellnote.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bellnote.R

/**
 * 开屏动画：冷启动时盖一层，播完自己淡出。
 *
 * 两种档位：
 *  - 日常（simple）：图标 0.85 → 1 放大 + 淡入，约 320ms。
 *  - 首次 / 升级（fancy）：图标从下方落位 + 轻微回弹，落定后浮现「Bellnote」字标，再整层淡出。
 *
 * 颜色全部取当前主题属性，四套配色 + 深浅色都跟着走。
 * 约束：最多 1.2s 一定撤掉，绝不把人卡在开屏。
 */
object SplashOverlay {

    private const val HARD_TIMEOUT_MS = 2200L

    fun show(activity: Activity, fancy: Boolean, onFinished: () -> Unit) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            onFinished()
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scrim = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(themeColor(activity, R.attr.bnWindowBg))
            isClickable = true // 动画期间挡住误触
        }

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val icon = ImageView(activity).apply {
            setImageResource(iconFor(activity))
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
            // 系统开屏已经把这颗图标摆在这儿了，这里从「已显示」接着往下演，避免同一颗图标闪两次
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }

        val wordmark = TextView(activity).apply {
            text = "Bellnote"
            textSize = 20f
            letterSpacing = 0.02f
            setTextColor(themeColor(activity, R.attr.bnTextPrimary))
            visibility = if (fancy) View.VISIBLE else View.GONE
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }

        column.addView(icon)
        column.addView(wordmark)
        scrim.addView(column)
        root.addView(scrim)

        var finished = false
        val handler = Handler(Looper.getMainLooper())

        fun dismiss() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            scrim.animate().alpha(0f).setDuration(220L).withEndAction {
                (scrim.parent as? ViewGroup)?.removeView(scrim)
                onFinished()
            }.start()
        }

        handler.postDelayed({ dismiss() }, HARD_TIMEOUT_MS)

        if (fancy) {
            // 图标保持不动，字标从下方微微浮上来，然后整层淡出
            wordmark.alpha = 0f
            wordmark.translationY = dp(8).toFloat()
            wordmark.animate().alpha(1f).translationY(0f)
                .setStartDelay(120L).setDuration(260L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            handler.postDelayed({ dismiss() }, 1250L)
        } else {
            handler.postDelayed({ dismiss() }, 800L)
        }
    }

    /** 开屏图标跟着配色走，跟桌面上那个保持一致。 */
    private fun iconFor(activity: Activity): Int = when (com.bellnote.data.Prefs.theme(activity)) {
        "indigo" -> R.drawable.ic_launcher_indigo
        "sand" -> R.drawable.ic_launcher_sand
        "ink" -> R.drawable.ic_launcher_ink
        else -> R.drawable.ic_launcher_teal
    }

    private fun themeColor(activity: Activity, attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(activity, value.resourceId)
        } else {
            value.data
        }
    }
}
