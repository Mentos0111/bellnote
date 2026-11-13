package com.bellnote.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * 首页列表的条目动画：所有变化**同一时刻开始、同一时刻结束**。
 *
 * 为什么不用 DefaultItemAnimator：它把一组动画串起来跑 —— 插入的卡片要等下面卡片的位移
 * 跑完才开始淡入（real 结束时间 = 位移 + 淡入），所以「展开」看着总慢半拍，而且卡片
 * 是分批动的，看着像「有的跟着动、有的不动」。这里全部并行，110ms 一起收工。
 *
 * 另外不收 change 动画：分组标题的交叉淡入会叠出一层老图，就是那种说不清的「不顺」。
 */
class ListAnimator : SimpleItemAnimator() {

    private val running = LinkedHashSet<RecyclerView.ViewHolder>()
    private val ease = PathInterpolator(0.2f, 0.9f, 0.15f, 1f)

    init {
        supportsChangeAnimations = false
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    override fun isRunning(): Boolean = running.isNotEmpty()

    /** 动画在 animateXxx 里就同时起跑了，这里不需要再排队。 */
    override fun runPendingAnimations() = Unit

    /**
     * 收起时不淡出：半透明的卡片会互相叠着（下面是刚补上来的卡片），在 60Hz 上就是一团
     * 「卡住」的残影。直接收走，让下面的条目滑上来，视线更干净。
     */
    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        reset(holder.itemView)
        return false
    }

    /** 展开：只做位移（从下面 10dp 滑到位），不加淡入——透明度变化在这块屏上最显「粘」。 */
    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        start(holder)
        view.animate().cancel()
        view.translationY = dp(view, 10f)
        view.animate()
            .translationY(0f)
            .setDuration(DURATION_MS)
            .setInterpolator(ease)
            .setListener(finishListener(holder))
            .start()
        return true
    }

    override fun animateMove(
        holder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        val view = holder.itemView
        val dx = toX - fromX
        val dy = toY - fromY
        if (dx == 0 && dy == 0) {
            reset(view)
            dispatchAnimationFinished(holder)
            return false
        }
        start(holder)
        view.animate().cancel()
        // 已经排到新位置了，先「假装」还在老位置，再动画过去
        view.translationX = -dx.toFloat()
        view.translationY = -dy.toFloat()
        view.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(DURATION_MS)
            .setInterpolator(ease)
            .setListener(finishListener(holder))
            .start()
        return true
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        // 不做交叉淡入：直接换内容，最干净
        reset(newHolder.itemView)
        dispatchAnimationFinished(newHolder)
        if (oldHolder !== newHolder) {
            reset(oldHolder.itemView)
            dispatchAnimationFinished(oldHolder)
        }
        return false
    }

    override fun endAnimation(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        view.animate().cancel()
        view.animate().setListener(null)
        reset(view)
        if (running.remove(holder)) dispatchAnimationFinished(holder)
    }

    override fun endAnimations() {
        running.toList().forEach { endAnimation(it) }
    }

    private fun start(holder: RecyclerView.ViewHolder) {
        running.add(holder)
    }

    private fun finishListener(holder: RecyclerView.ViewHolder) =
        object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                val view = holder.itemView
                view.animate().setListener(null)
                reset(view)
                running.remove(holder)
                dispatchAnimationFinished(holder)
            }
        }

    private fun reset(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private companion object {
        /**
         * 唯一的节奏旋钮：展开时插入与位移同时跑这么久。
         * 100ms 上下会像「啪一下切过去」，没有回馈感；200ms 以上开始觉得拖。
         * 改这里就够了，别再分别调几个时长。
         */
        const val DURATION_MS = 170L
    }
}
