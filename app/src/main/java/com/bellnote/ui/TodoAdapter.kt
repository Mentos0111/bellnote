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
        val note: TextView = view.findViewById(R.id.itemNote)
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
        val repeatSuffix = if (item.repeat == RepeatRule.NONE) "" else " · ${item.repeat.label}"
        holder.time.text =
            "${TimeFormat.compact(item.startMillis)} · ${item.durationMinutes} 分$repeatSuffix"
        val note = item.note
        if (note.isNullOrBlank()) {
            holder.note.visibility = View.GONE
        } else {
            holder.note.visibility = View.VISIBLE
            holder.note.text = note
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
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
