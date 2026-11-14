package com.bellnote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.R
import com.bellnote.data.DayGroups
import com.bellnote.data.Prefs
import com.bellnote.data.RepeatRule
import com.bellnote.data.TodoItem
import com.bellnote.util.StatusColors
import com.bellnote.util.TimeFormat

/**
 * 主页待办列表：按「今天 / 明天 / 后天 / 已过期 / 具体日期」分组，条目多了也不糊。
 * 分组标题可以点一下收起／展开，收起时标题上写着这一组有几条；状态记在 Prefs 里。
 * 默认展开「今天」「明天」，其余默认收起（见 DayGroups）。
 */
class TodoAdapter(
    items: List<TodoItem> = emptyList(),
    collapsedGroups: Set<String> = emptySet(),
    expandedGroups: Set<String> = emptySet(),
    private val onClick: (TodoItem) -> Unit,
    private val onLongClick: (TodoItem) -> Unit,
    private val onGroupStateChanged: (collapsed: Set<String>, expanded: Set<String>) -> Unit =
        { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val label: String, val count: Int, val collapsed: Boolean) : Row
        data class Entry(val todo: TodoItem) : Row
    }

    private var source: List<TodoItem> = items
    private var collapsed: MutableSet<String> = collapsedGroups.toMutableSet()
    private var expanded: MutableSet<String> = expandedGroups.toMutableSet()
    private var rows: List<Row> = buildRows(items)

    var items: List<TodoItem>
        get() = source
        set(value) {
            source = value
            rows = buildRows(value)
        }

    /** 这一组现在收没收起：用户点过的优先，没点过按默认（今天/明天展开）。 */
    private fun isCollapsed(label: String): Boolean =
        DayGroups.isCollapsed(label, collapsed, expanded)

    /**
     * 整体换数据：走 DiffUtil，只通知真正变了的那几行。
     * 以前这里是 notifyDataSetChanged()，整表重绑 → 卡片闪一下、新增的没有入场、删掉的直接消失。
     */
    fun submit(newItems: List<TodoItem>) {
        source = newItems
        val next = buildRows(newItems)
        // 先换 rows 再派发：RecyclerView 在 dispatch 期间就会回读 getItemCount()，
        // 顺序反了会出现「有的条目跟着动、有的直接跳位」。
        val old = rows
        rows = next
        dispatch(old, next)
    }

    /**
     * 每分钟的时间进度刷新：只重绑进度条那一小块，不动标题/时间，也不触发条目动画。
     * 用它替掉整表 notifyDataSetChanged()，首页才不会每分钟抖一次。
     */
    fun tickProgress() {
        if (rows.isEmpty()) return
        notifyItemRangeChanged(0, rows.size, PAYLOAD_PROGRESS)
    }

    private fun dispatch(old: List<Row>, next: List<Row>) {
        DiffUtil.calculateDiff(Differ(old, next), true).dispatchUpdatesTo(this)
    }

    /** 行的身份：分组按标签认，条目按日历事件 id 认。 */
    private class Differ(val old: List<Row>, val next: List<Row>) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = next.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val a = old[oldPos]
            val b = next[newPos]
            return when {
                a is Row.Header && b is Row.Header -> a.label == b.label
                a is Row.Entry && b is Row.Entry -> a.todo.id == b.todo.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos] == next[newPos]
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.itemTitle)
        val time: TextView = view.findViewById(R.id.itemTime)
        val duration: TextView = view.findViewById(R.id.itemDuration)
        val note: TextView = view.findViewById(R.id.itemNote)
        val progressFill: View = view.findViewById(R.id.itemProgressFill)
        val progressBar: View = view.findViewById(R.id.itemProgressBar)
        val progressLabel: TextView = view.findViewById(R.id.itemProgressLabel)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.headerText)
        val arrow: ImageView = view.findViewById(R.id.headerArrow)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_todo, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> bindHeader(holder as HeaderHolder, row)
            is Row.Entry -> bindItem(holder as ItemHolder, row.todo)
        }
    }

    /** 进度条的局部刷新：只重绑「还剩多久」那一层，不重设标题和点击。 */
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val row = rows.getOrNull(position)
        if (payloads.contains(PAYLOAD_PROGRESS) && holder is ItemHolder && row is Row.Entry) {
            bindProgress(holder, row.todo)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindHeader(holder: HeaderHolder, row: Row.Header) {
        holder.text.text = if (row.collapsed) "${row.label} · ${row.count}" else row.label
        // 收起时箭头朝右，展开时朝下；先转，别等列表动完（这一下就是「我点到了」的回执）
        val target = if (row.collapsed) 0f else 90f
        if (holder.arrow.rotation != target) {
            holder.arrow.animate()
                .rotation(target)
                .setDuration(120L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        holder.itemView.setOnClickListener {
            // 手指上给一点点震感，收展这种「看不见结果」的操作靠它确认
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            toggleGroup(row.label)
        }
    }

    /** 收起/展开：把用户这次的选择记进对应的集合，再交给 DiffUtil 重算。 */
    private fun toggleGroup(label: String) {
        if (!rows.any { it is Row.Header && it.label == label }) return
        if (isCollapsed(label)) {
            // 这次是展开：从「收起」里拿掉，记进「展开过」
            collapsed.remove(label)
            expanded.add(label)
        } else {
            // 这次是收起：从「展开过」里拿掉，记进「收起」
            expanded.remove(label)
            collapsed.add(label)
        }

        val next = buildRows(source)
        val old = rows
        rows = next
        dispatch(old, next)
        onGroupStateChanged(collapsed.toSet(), expanded.toSet())
    }

    private fun bindItem(holder: ItemHolder, item: TodoItem) {
        holder.title.text = item.title
        holder.time.text = TimeFormat.clock(item.startMillis)
        val repeatSuffix = if (item.repeat == RepeatRule.NONE) "" else " · ${item.repeat.label}"
        holder.duration.text = "${item.durationMinutes} 分$repeatSuffix"
        val note = item.note
        if (note.isNullOrBlank()) {
            holder.note.visibility = View.GONE
        } else {
            holder.note.visibility = View.VISIBLE
            holder.note.text = note
        }
        val ended = System.currentTimeMillis() >= item.startMillis + item.durationMinutes * 60_000L
        styleProgressLook(holder, ended)
        styleCardShadow(holder, ended)
        bindProgress(holder, item)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    /**
     * 进度的两种形态（设置 → 外观 → 进度条）：
     *
     * - **wash（默认）**：整张卡从左往右铺一层**不显眼的中性色**。它能表示「走到哪了」，
     *   又不会抢走没做完的事；铺满时整张卡被压暗一档，自己就退下去了——不需要再额外加
     *   透明度或改文字颜色。深色模式也铺黑（深底铺白会把卡片点亮，反而更显眼）。
     * - **bar**：卡片底边一条 3dp 进度条。进行中使用主题色（面积小，可以亮）；结束后转成
     *   弱色，避免整条亮线跳出来。
     *
     * 注意用 `View.backgroundTintList`，别用 `drawable.setTint`：后者改的是所有卡片
     * 共用的 ConstantState，会串色（2026-11-14 踩过）。
     */
    private fun styleProgressLook(holder: ItemHolder, ended: Boolean) {
        val context = holder.itemView.context
        val night = StatusColors.isNight(context)
        val bar = Prefs.endedStyle(context) == Prefs.ENDED_STYLE_BAR

        holder.progressFill.visibility = if (bar) View.GONE else View.VISIBLE
        holder.progressBar.visibility = if (bar) View.VISIBLE else View.GONE

        if (bar) {
            val tint = when {
                ended -> StatusColors.attr(context, R.attr.bnMuted)
                night -> android.graphics.Color.WHITE
                else -> StatusColors.attr(context, R.attr.bnPrimary)
            }
            holder.progressBar.backgroundTintList =
                android.content.res.ColorStateList.valueOf(tint)
            holder.progressBar.alpha = if (ended) 0.55f else 0.95f
        } else {
            // 整卡着色垫在**文字下面**：给「还没走完的那一段」上一层淡主题色。
            // 以前是盖在文字上面洗淡，结果已结束的卡糊成一块灰（老师 2026-11-14 指出）。
            // 现在：没走完的部分有色、走完的部分是干净底色，文字始终清晰。
            val tint = StatusColors.attr(context, R.attr.bnPrimary)
            holder.progressFill.backgroundTintList =
                android.content.res.ColorStateList.valueOf(tint)
            holder.progressFill.alpha = if (night) 0.16f else 0.13f
        }

        // 浅色模式下卡片底子稍微带一点主题色：全白卡上那层「页面底色」几乎看不出边界，
        // 稍微着色之后进度线才读得出来（老师 2026-11-14 提）。深色模式原样不动。
        holder.itemView.backgroundTintList = if (night) {
            null
        } else {
            val card = StatusColors.attr(context, R.attr.bnCard)
            val primary = StatusColors.attr(context, R.attr.bnPrimary)
            android.content.res.ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.blendARGB(card, primary, 0.07f)
            )
        }
        // 文字：没做完的永远满对比、时间用主题色；
        // 已结束的**时间一律去掉主题色**（两种模式都去掉，不然那串彩色时间最扎眼）；
        // 亮色模式另外把标题/备注也压一档——浅色那层 6% 的灰扫过还不够淡。
        val primary = StatusColors.attr(context, R.attr.bnTextPrimary)
        val secondary = StatusColors.attr(context, R.attr.bnTextSecondary)
        val muted = StatusColors.attr(context, R.attr.bnMuted)
        val accent = StatusColors.attr(context, R.attr.bnPrimary)
        holder.title.setTextColor(if (ended && !night) muted else primary)
        holder.time.setTextColor(if (ended) muted else accent)
        holder.duration.setTextColor(if (ended) muted else secondary)
        holder.note.setTextColor(if (ended) muted else secondary)
        holder.progressLabel.setTextColor(if (ended) muted else secondary)
    }

    /** 换了「已结束的样子」以后，把列表整体重绑一遍（数据没变，Diff 不会动）。 */
    fun refreshStyles() {
        if (rows.isEmpty()) return
        notifyItemRangeChanged(0, rows.size)
    }

    /**
     * 进度底色：内部把「整卡铺色」和「条状」两种形态的进度都按同一个比例画出来，
     * 具体显示哪个由 styleProgressLook 决定。
     */
    private fun styleProgressFill(holder: ItemHolder) {
        val context = holder.progressFill.context
        val night = StatusColors.isNight(context)
        val tint = if (night) android.graphics.Color.WHITE else StatusColors.attr(context, R.attr.bnPrimary)
        holder.progressFill.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        holder.progressFill.alpha = if (night) 0.10f else 0.14f
    }

    /**
     * 卡片阴影：不用系统那层发灰的黑影，改成带主题色的柔和投影
     * （Android 9+ 才能单独指定投影颜色，低版本退回系统默认）。
     * 已结束的卡片不给主题色光晕——不然做完的事反而比没做的更扎眼。
     */
    private fun styleCardShadow(holder: ItemHolder, ended: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        val context = holder.itemView.context
        val tint = StatusColors.attr(context, R.attr.bnPrimary)
        holder.itemView.outlineSpotShadowColor = if (ended) {
            android.graphics.Color.TRANSPARENT
        } else {
            androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 120)
        }
        holder.itemView.outlineAmbientShadowColor = android.graphics.Color.TRANSPARENT
    }

    /**
     * 时间进度 = **这条待办自身走过了多少**：
     *  - 还没开始：0（一点不涂，别提前给进度）
     *  - 进行中：按「已经过去 / 总时长」从 0 长到 1
     *  - 已结束：1（走满），文字写「已结束」，颜色更淡（强调留给没做完的事）
     *
     * 以前是反的：进行中直接涂满、没开始的按「离开始还有多久」提前涂，看着像进度错了。
     */
    private fun bindProgress(holder: ItemHolder, item: TodoItem) {
        val now = System.currentTimeMillis()
        val start = item.startMillis
        val end = start + item.durationMinutes * 60_000L
        val total = (end - start).coerceAtLeast(1L)
        val barStyle = Prefs.endedStyle(holder.itemView.context) == Prefs.ENDED_STYLE_BAR

        when {
            now >= end -> {
                setProgressFraction(holder, 1f)
                // 整卡铺色靠「被压暗的卡」表达结束；条状只靠一条线，需要文字补一句
                if (barStyle) {
                    holder.progressLabel.visibility = View.VISIBLE
                    holder.progressLabel.text = "已结束"
                } else {
                    holder.progressLabel.visibility = View.GONE
                }
            }

            now >= start -> {
                setProgressFraction(holder, ((now - start).toFloat() / total).coerceIn(0f, 1f))
                holder.progressLabel.visibility = View.VISIBLE
                holder.progressLabel.text = "进行中 · 还剩 ${humanize(end - now)}"
            }

            else -> {
                // 还没开始：进度就是 0；两个小时内给一句「还有多久」，方便安排
                setProgressFraction(holder, 0f)
                val remain = start - now
                if (remain <= 2 * 60 * 60 * 1000L) {
                    holder.progressLabel.visibility = View.VISIBLE
                    holder.progressLabel.text = "还有 ${humanize(remain)}"
                } else {
                    holder.progressLabel.visibility = View.GONE
                }
            }
        }
    }

    private fun setProgressFraction(holder: ItemHolder, fraction: Float) {
        // 整卡着色表达的是「还剩多少」：以右边为轴，剩得越少涂得越窄，走完就没有了
        holder.progressFill.pivotX = holder.progressFill.width.toFloat()
        holder.progressFill.scaleX = (1f - fraction).coerceIn(0f, 1f)
        holder.progressFill.post {
            holder.progressFill.pivotX = holder.progressFill.width.toFloat()
        }
        holder.progressBar.scaleX = fraction
    }

    private fun humanize(millis: Long): String {
        val minutes = (millis / 60_000L).coerceAtLeast(1)
        return when {
            minutes < 60 -> "$minutes 分钟"
            minutes < 24 * 60 -> {
                val hours = minutes / 60
                val rest = minutes % 60
                if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分"
            }

            else -> "${minutes / (24 * 60)} 天"
        }
    }

    private fun buildRows(source: List<TodoItem>): List<Row> {
        val out = mutableListOf<Row>()
        val groups = source.sortedBy { it.startMillis }.groupBy { DayGroups.label(it.startMillis) }
        for ((label, entries) in groups) {
            val folded = isCollapsed(label)
            out.add(Row.Header(label, entries.size, folded))
            if (!folded) entries.forEach { out.add(Row.Entry(it)) }
        }
        return out
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        /** 只刷新卡片上的时间进度，不重绑整条。 */
        private const val PAYLOAD_PROGRESS = "progress"
    }
}
