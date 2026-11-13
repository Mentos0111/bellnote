package com.bellnote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.R
import com.bellnote.data.CalendarRepository.EventInfo
import com.bellnote.util.TimeFormat

/** 「全部日程」列表：按天分组的标题 + 每一条事件。 */
class AllEventsAdapter(
    private val onClick: (EventInfo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val label: String) : Row()
        data class Event(val info: EventInfo) : Row()
    }

    private var rows: List<Row> = emptyList()

    /** 走 Diff：只通知真正变了的那几行，新增/删除才有动画（以前是整表硬刷）。 */
    fun submit(events: List<EventInfo>) {
        val next = buildRows(events)
        val old = rows
        // 先换数据再派发，否则派发期间读到的还是旧数量
        rows = next
        com.bellnote.util.DiffDispatch.dispatch(this, old, next, ::sameRow)
    }

    private fun sameRow(a: Row, b: Row): Boolean = when {
        a is Row.Header && b is Row.Header -> a.label == b.label
        a is Row.Event && b is Row.Event -> a.info.id == b.info.id
        else -> false
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_EVENT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_day_header, parent, false))
        } else {
            EventHolder(inflater.inflate(R.layout.item_all_event, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.label)
            is Row.Event -> (holder as EventHolder).bind(row.info, onClick)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.tvDay)
        fun bind(text: String) {
            label.text = text
        }
    }

    private class EventHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tvTitle)
        private val time: TextView = view.findViewById(R.id.tvTime)
        private val meta: TextView = view.findViewById(R.id.tvMeta)

        fun bind(info: EventInfo, onClick: (EventInfo) -> Unit) {
            title.text = info.title.ifBlank { "（无标题）" }
            val span = if (info.allDay) {
                "全天"
            } else {
                val minutes = ((info.endMillis - info.startMillis) / 60_000L).toInt().coerceAtLeast(0)
                "${TimeFormat.monthDay(info.startMillis)} · ${minutes} 分钟"
            }
            time.text = span
            val source = if (info.isOwn) "Bellnote" else info.calendarName
            meta.text = "$source · ${info.calendarName}"
            itemView.setOnClickListener { onClick(info) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_EVENT = 1

        /** 纯函数：按开始时间排序后插入日期标题，方便单测。 */
        fun buildRows(events: List<EventInfo>): List<Row> {
            val sorted = events.sortedBy { it.startMillis }
            val out = mutableListOf<Row>()
            var lastDay: String? = null
            for (event in sorted) {
                val day = TimeFormat.dayKey(event.startMillis)
                if (day != lastDay) {
                    out.add(Row.Header(TimeFormat.dayLabel(event.startMillis)))
                    lastDay = day
                }
                out.add(Row.Event(event))
            }
            return out
        }
    }
}
