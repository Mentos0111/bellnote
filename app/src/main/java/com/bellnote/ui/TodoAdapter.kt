package com.bellnote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.R
import com.bellnote.data.RepeatRule
import com.bellnote.data.TodoItem
import com.bellnote.util.StatusColors
import com.bellnote.util.TimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 主页待办列表：按「今天 / 明天 / 后天 / 已过期 / 具体日期」分组，条目多了也不糊。
 * 分组标题可以点一下收起／展开，收起时标题上写着这一组有几条；状态记在 Prefs 里。
 */
class TodoAdapter(
    items: List<TodoItem> = emptyList(),
    collapsedGroups: Set<String> = emptySet(),
    private val onClick: (TodoItem) -> Unit,
    private val onLongClick: (TodoItem) -> Unit,
    private val onCollapseChanged: (Set<String>) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val label: String, val count: Int, val collapsed: Boolean) : Row
        data class Entry(val todo: TodoItem) : Row
    }

    private var source: List<TodoItem> = items
    private var collapsed: MutableSet<String> = collapsedGroups.toMutableSet()
    private var rows: List<Row> = buildRows(items, collapsed)

    var items: List<TodoItem>
        get() = source
        set(value) {
            source = value
            rows = buildRows(value, collapsed)
        }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.itemTitle)
        val time: TextView = view.findViewById(R.id.itemTime)
        val duration: TextView = view.findViewById(R.id.itemDuration)
        val note: TextView = view.findViewById(R.id.itemNote)
        val progressFill: View = view.findViewById(R.id.itemProgressFill)
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

    private fun bindHeader(holder: HeaderHolder, row: Row.Header) {
        holder.text.text = if (row.collapsed) "${row.label} · ${row.count}" else row.label
        // 收起时箭头朝右，展开时朝下
        holder.arrow.rotation = if (row.collapsed) 0f else 90f
        holder.itemView.setOnClickListener { toggleGroup(row.label) }
    }

    /** 收起/展开：只通知被插/删的那几行，让 RecyclerView 自己把它们滑走或滑进来。 */
    private fun toggleGroup(label: String) {
        val headerIndex = rows.indexOfFirst { it is Row.Header && it.label == label }
        if (headerIndex < 0) return
        val header = rows[headerIndex] as Row.Header
        val expanding = header.collapsed
        if (expanding) collapsed.remove(label) else collapsed.add(label)

        rows = buildRows(source, collapsed)
        notifyItemChanged(headerIndex)
        if (expanding) {
            notifyItemRangeInserted(headerIndex + 1, header.count)
        } else {
            notifyItemRangeRemoved(headerIndex + 1, header.count)
        }
        onCollapseChanged(collapsed.toSet())
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
        styleProgressFill(holder)
        styleCardShadow(holder)
        bindProgress(holder, item)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    /** 进度底色：浅色模式用主题色淡淡铺一层，深色模式用白色铺一层。 */
    private fun styleProgressFill(holder: ItemHolder) {
        val context = holder.progressFill.context
        val night = StatusColors.isNight(context)
        val color = if (night) android.graphics.Color.WHITE else StatusColors.attr(context, R.attr.bnPrimary)
        holder.progressFill.background?.setTint(color)
        holder.progressFill.alpha = if (night) 0.10f else 0.14f
    }

    /**
     * 卡片阴影：不用系统那层发灰的黑影，改成带主题色的柔和投影
     * （Android 9+ 才能单独指定投影颜色，低版本退回系统默认）。
     */
    private fun styleCardShadow(holder: ItemHolder) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        val context = holder.itemView.context
        val tint = StatusColors.attr(context, R.attr.bnPrimary)
        holder.itemView.outlineSpotShadowColor =
            androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 72)
        holder.itemView.outlineAmbientShadowColor = android.graphics.Color.TRANSPARENT
    }

    /**
     * 时间进度：开始前 2 小时内，卡片从左边开始逐渐涂上一块底色，越接近开始涂得越满；
     * 进行中整张涂满并显示剩余时间。更早的（还有好几个小时）和已结束的都不涂。
     */
    private fun bindProgress(holder: ItemHolder, item: TodoItem) {
        val now = System.currentTimeMillis()
        val start = item.startMillis
        val end = start + item.durationMinutes * 60_000L
        val window = 2 * 60 * 60 * 1000L

        when {
            now >= end -> hideProgress(holder)

            now >= start -> {
                holder.progressFill.scaleX = 1f
                holder.progressLabel.visibility = View.VISIBLE
                holder.progressLabel.text = "进行中 · 还剩 ${humanize(end - now)}"
            }

            start - now <= window -> {
                val remain = start - now
                val fraction = (100f - remain * 100f / window).coerceIn(0f, 100f) / 100f
                holder.progressFill.scaleX = fraction
                holder.progressLabel.visibility = View.VISIBLE
                holder.progressLabel.text = "还有 ${humanize(remain)}"
            }

            else -> hideProgress(holder)
        }
    }

    private fun hideProgress(holder: ItemHolder) {
        holder.progressFill.scaleX = 0f
        holder.progressLabel.visibility = View.GONE
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

    private fun buildRows(source: List<TodoItem>, collapsed: Set<String>): List<Row> {
        val out = mutableListOf<Row>()
        val groups = source.sortedBy { it.startMillis }.groupBy { dayLabel(it.startMillis) }
        for ((label, entries) in groups) {
            val isCollapsed = collapsed.contains(label)
            out.add(Row.Header(label, entries.size, isCollapsed))
            if (!isCollapsed) entries.forEach { out.add(Row.Entry(it)) }
        }
        return out
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        private fun dayLabel(millis: Long): String {
            val zone = ZoneId.systemDefault()
            val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            val today = LocalDate.now(zone)
            return when {
                day.isBefore(today) -> "已过期"
                day == today -> "今天"
                day == today.plusDays(1) -> "明天"
                day == today.plusDays(2) -> "后天"
                else -> "${day.monthValue} 月 ${day.dayOfMonth} 日"
            }
        }
    }
}
