package com.bellnote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.R
import com.bellnote.data.RepeatRule
import com.bellnote.data.TodoItem
import com.bellnote.util.TimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 主页待办列表：按「今天 / 明天 / 后天 / 已过期 / 具体日期」分组，条目多了也不糊。 */
class TodoAdapter(
    items: List<TodoItem> = emptyList(),
    private val onClick: (TodoItem) -> Unit,
    private val onLongClick: (TodoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val label: String) : Row
        data class Entry(val todo: TodoItem) : Row
    }

    private var rows: List<Row> = buildRows(items)

    var items: List<TodoItem>
        get() = rows.filterIsInstance<Row.Entry>().map { it.todo }
        set(value) {
            rows = buildRows(value)
        }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.itemTitle)
        val time: TextView = view.findViewById(R.id.itemTime)
        val note: TextView = view.findViewById(R.id.itemNote)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.headerText)
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
            is Row.Header -> (holder as HeaderHolder).text.text = row.label
            is Row.Entry -> bindItem(holder as ItemHolder, row.todo)
        }
    }

    private fun bindItem(holder: ItemHolder, item: TodoItem) {
        holder.title.text = item.title
        val repeatSuffix = if (item.repeat == RepeatRule.NONE) "" else " · ${item.repeat.label}"
        holder.time.text =
            "${TimeFormat.full(item.startMillis)} · ${item.durationMinutes} 分钟$repeatSuffix"
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

    private fun buildRows(source: List<TodoItem>): List<Row> {
        val out = mutableListOf<Row>()
        var lastLabel: String? = null
        for (item in source.sortedBy { it.startMillis }) {
            val label = dayLabel(item.startMillis)
            if (label != lastLabel) {
                out.add(Row.Header(label))
                lastLabel = label
            }
            out.add(Row.Entry(item))
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
