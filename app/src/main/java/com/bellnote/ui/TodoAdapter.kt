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

class TodoAdapter(
    var items: List<TodoItem> = emptyList(),
    private val onClick: (TodoItem) -> Unit,
    private val onLongClick: (TodoItem) -> Unit
) : RecyclerView.Adapter<TodoAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.itemTitle)
        val time: TextView = view.findViewById(R.id.itemTime)
        val note: TextView = view.findViewById(R.id.itemNote)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_todo, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val repeatSuffix = if (item.repeat == RepeatRule.NONE) "" else " · ${item.repeat.label}"
        holder.time.text = "${TimeFormat.full(item.startMillis)} · ${item.durationMinutes} 分钟$repeatSuffix"
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
}
