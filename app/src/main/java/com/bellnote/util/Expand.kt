package com.bellnote.util

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * 折叠/展开一段内容（设置页的分组靠它）。
 *
 * 为什么不用 `TransitionManager.beginDelayedTransition`：设置页在 ScrollView 里，
 * 自动过渡会把整页一起做边界动画，滚动手感会乱。这里只动这一个 View 的高度，
 * 影响面最小。
 *
 * 用法：`Expand.setExpanded(groupView, open)`。连点安全——重入时先取消上一次动画，
 * 再按当前可见高度重算，终态一定是「完全展开」或「完全收起」。
 */
object Expand {

    private const val DURATION = 180L

    /** 用 tag 存正在跑的动画，避免同一个 View 上叠两个。 */
    private val ANIM_TAG = "bellnote_expand_anim".hashCode()

    fun setExpanded(view: View, expanded: Boolean) {
        (view.getTag(ANIM_TAG) as? ValueAnimator)?.let {
            it.cancel()
            view.setTag(ANIM_TAG, null)
        }

        val params = view.layoutParams
        val fullHeight = measureFullHeight(view)

        if (expanded) {
            if (view.visibility == View.VISIBLE && params.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                return // 已经是展开状态
            }
            view.visibility = View.VISIBLE
            val from = if (params.height > 0) params.height else 0
            if (fullHeight <= 0) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = params
                return
            }
            animate(view, from, fullHeight) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = params
            }
        } else {
            if (view.visibility != View.VISIBLE) return
            val from = if (params.height > 0) params.height else view.height
            if (from <= 0) {
                view.visibility = View.GONE
                return
            }
            animate(view, from, 0) {
                view.visibility = View.GONE
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = params
            }
        }
    }

    /** 量出「如果完全展开，本该多高」。要先可见才能量。 */
    private fun measureFullHeight(view: View): Int {
        val parentWidth = (view.parent as? View)?.width?.takeIf { it > 0 }
            ?: view.resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            (parentWidth - view.paddingLeft - view.paddingRight).coerceAtLeast(0),
            View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val wasVisible = view.visibility == View.VISIBLE
        if (!wasVisible) view.visibility = View.VISIBLE
        view.measure(widthSpec, heightSpec)
        val measured = view.measuredHeight
        if (!wasVisible) view.visibility = View.GONE
        return measured
    }

    private fun animate(view: View, from: Int, to: Int, onEnd: () -> Unit) {
        val params = view.layoutParams
        val animator = ValueAnimator.ofInt(from, to).apply {
            duration = DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.height = it.animatedValue as Int
                view.layoutParams = params
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.setTag(ANIM_TAG, null)
                    if (!cancelled) onEnd()
                }
            })
        }
        view.setTag(ANIM_TAG, animator)
        animator.start()
    }
}
