package com.bellnote.util

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * 全 App 列表的条目动效，只在一处定节奏。
 *
 * 规矩（2026-11-13 踩过坑）：条目动画只交给系统自带的 `DefaultItemAnimator`，
 * **不要自己写 ItemAnimator，也不要对条目手动 `translationY/alpha`** ——
 * 动画被打断时残留的位移会把卡片画在错位的地方、叠成一团。
 *
 * 觉得快/慢就只改 `DURATION_MS` 一个数，别逐项调。
 */
object ListMotion {

    /** 新增 / 位移的时长。删除稍快一点。 */
    const val DURATION_MS = 160L

    private const val REMOVE_FACTOR = 0.75f

    fun apply(recycler: RecyclerView) {
        recycler.itemAnimator = DefaultItemAnimator().apply {
            // 标题类的「变化」不做老图新图的交叉淡入，那一层叠影看着就是不顺
            supportsChangeAnimations = false
            addDuration = DURATION_MS
            moveDuration = DURATION_MS
            changeDuration = 0L
            removeDuration = (DURATION_MS * REMOVE_FACTOR).toLong()
        }
    }
}
