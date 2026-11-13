package com.bellnote.util

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * 列表换数据的统一入口：算出差异后只通知真正变了的那几行。
 *
 * **顺序很关键**：调用方必须先把适配器里的数据换成新列表，再调这里派发。
 * 反过来的话，RecyclerView 在派发期间读到的还是旧数量，会出现
 * 「有的条目跟着动、有的直接跳位」（2026-11-13 踩过）。
 *
 * 用法：
 * ```
 * val old = rows
 * rows = newRows          // 先换数据
 * DiffDispatch.dispatch(this, old, newRows, ::sameItem, ::sameContent)
 * ```
 */
object DiffDispatch {

    fun <T> dispatch(
        adapter: RecyclerView.Adapter<*>,
        old: List<T>,
        new: List<T>,
        sameItem: (T, T) -> Boolean,
        sameContent: (T, T) -> Boolean = { a, b -> a == b }
    ) {
        if (old === new) return
        val callback = Callback(old, new, sameItem, sameContent)
        DiffUtil.calculateDiff(callback, true).dispatchUpdatesTo(adapter)
    }

    /** 独立成类，单测可以直接构造它验证「怎么算同一条、怎么算内容变了」。 */
    class Callback<T>(
        private val old: List<T>,
        private val new: List<T>,
        private val sameItem: (T, T) -> Boolean,
        private val sameContent: (T, T) -> Boolean
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = old.size

        override fun getNewListSize(): Int = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            sameItem(old[oldPos], new[newPos])

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            sameContent(old[oldPos], new[newPos])
    }
}
