package com.bellnote.util

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import com.bellnote.R

/**
 * 全局按压反馈：按钮按下缩一点、松手回弹；可点的行/卡片补一层涟漪。
 *
 * 不逐页改代码：App 里注册 ActivityLifecycleCallbacks，每个页面创建后挂一个
 * 全局布局监听，布局结束后扫描一遍控件树（用 tag 去重），所以以后新加的页面、
 * RecyclerView 里新滚出来的条目也会自动带上。
 *
 * 刻意不动的东西：输入框、下拉框、开关/复选框（它们自己已有反馈，缩放反而怪）。
 */
object TouchFeedback {

    private const val PRESS_SCALE = 0.975f
    private const val DOWN_MS = 70L
    private const val UP_MS = 200L
    /** 按下不到这么久就松手 = 轻点：直接回位，不做回弹，避免「抖一下」。 */
    private const val TAP_MS = 110L

    private val downInterpolator = DecelerateInterpolator()
    // 稍微过冲一点点再回到 1.0，就是「弹一下」的手感；1.6 太硬，会像被捏了一下
    private val upInterpolator = OvershootInterpolator(1.15f)
    private val tapInterpolator = DecelerateInterpolator()

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val decor = activity.window?.decorView ?: return
                decor.viewTreeObserver.addOnGlobalLayoutListener { scheduleBind(decor) }
                scheduleBind(decor)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 布局每变一次就扫全树太浪费：抽屉滑入、键盘弹出时一帧一次布局，
     * 扫树本身又会拖慢动画。合并成 48ms 最多一次，新控件最晚半帧就带上反馈。
     */
    private val pendingWalks: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun scheduleBind(root: View) {
        if (!pendingWalks.add(root)) return
        root.postDelayed({
            pendingWalks.remove(root)
            bindTree(root)
        }, 48L)
    }

    private fun bindTree(root: View) {
        if (root.visibility != View.VISIBLE) return
        if (root.isClickable && root.isEnabled && !isBound(root)) {
            when {
                isButton(root) -> bindBounce(root)
                // 开关、复选框、下拉、输入框不补涟漪也不缩放
                root is CompoundButton -> Unit
                root is android.widget.EditText -> Unit
                root is android.widget.Spinner -> Unit
                else -> ensureRipple(root)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) bindTree(root.getChildAt(i))
        }
    }

    /** 只认真正的按钮控件：带文字的 Button、纯图标的 ImageButton（含 AppCompat 子类）。 */
    private fun isButton(view: View): Boolean =
        (view is Button && view !is CompoundButton) || view is ImageButton

    private fun isBound(view: View): Boolean = view.getTag(R.id.tag_touch_feedback) != null

    private fun markBound(view: View) {
        view.setTag(R.id.tag_touch_feedback, true)
    }

    // ── 按钮：按下缩一点，松手回弹 ────────────────────────────────────────────

    private fun bindBounce(view: View) {
        markBound(view)
        var downAt = 0L
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = android.os.SystemClock.uptimeMillis()
                    if (v.isEnabled) pressDown(v)
                }

                MotionEvent.ACTION_UP -> {
                    val held = android.os.SystemClock.uptimeMillis() - downAt
                    release(v, bounce = held >= TAP_MS)
                }

                MotionEvent.ACTION_CANCEL -> release(v, bounce = false)
            }
            // 返回 false：点击、长按、涟漪仍然交给控件自己处理
            false
        }
        // 条目光滑滚出屏幕时可能收不到 UP，复位，避免下次出现时是缩小状态
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.animate().cancel()
                v.scaleX = 1f
                v.scaleY = 1f
            }
        })
    }

    private fun pressDown(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(PRESS_SCALE)
            .scaleY(PRESS_SCALE)
            .setDuration(DOWN_MS)
            .setInterpolator(downInterpolator)
            .start()
    }

    private fun release(view: View, bounce: Boolean) {
        view.animate().cancel()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(if (bounce) UP_MS else (UP_MS - 60L))
            .setInterpolator(if (bounce) upInterpolator else tapInterpolator)
            .start()
    }

    // ── 可点行/卡片：只在还没涟漪时补一层 ────────────────────────────────────

    private fun ensureRipple(view: View) {
        markBound(view)
        // 已有前景（多半就是 selectableItemBackground）或背景本身是涟漪，不重复加
        if (view.foreground != null || view.background is RippleDrawable) return
        val ripple = RippleDrawable(
            ColorStateList.valueOf(rippleColor(view)),
            null,
            maskOf(view)
        )
        view.foreground = ripple
    }

    /** 用原背景当遮罩，涟漪就不会溢出圆角卡片。透明背景没有形状，直接方形涟漪。 */
    private fun maskOf(view: View): android.graphics.drawable.Drawable? {
        val bg = view.background ?: return null
        if (bg is ColorDrawable && bg.alpha == 0) return null
        return bg.constantState?.newDrawable()?.mutate()
    }

    private fun rippleColor(view: View): Int {
        val value = TypedValue()
        return if (view.context.theme.resolveAttribute(R.attr.bnRipple, value, true)) {
            value.data
        } else {
            0x1F000000
        }
    }
}
