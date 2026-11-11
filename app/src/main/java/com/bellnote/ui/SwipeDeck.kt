package com.bellnote.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 横向拖拽容器：只有「明显是横向划」时才从子 View 手里抢事件，
 * 这样页面里的输入框、开关行照样能正常点、正常滚。
 */
class SwipeDeck @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onDrag: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var intercepting = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                intercepting = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.rawX - downX)
                val dy = abs(ev.rawY - downY)
                if (!intercepting && dx > slop && dx > dy * 1.4f) intercepting = true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> intercepting = false
        }
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val dx = ev.rawX - downX
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDrag?.invoke(dx)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease?.invoke(dx)
        }
        return true
    }
}
