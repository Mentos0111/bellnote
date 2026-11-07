package com.bellnote.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.R
import com.bellnote.data.DiffItem
import com.bellnote.data.DiffKind
import com.bellnote.util.TimeFormat

/** 对账差异列表：〔缺〕〔多〕〔改〕三色徽章 + 标题 + 时间 + 原因。 */
class DiffAdapter : RecyclerView.Adapter<DiffAdapter.Holder>() {

    var items: List<DiffItem> = emptyList()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val badge: TextView = view.findViewById(R.id.diffBadge)
        val title: TextView = view.findViewById(R.id.diffTitle)
        val time: TextView = view.findViewById(R.id.diffTime)
        val detail: TextView = view.findViewById(R.id.diffDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_diff, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val color = colorFor(item.kind)
        holder.badge.text = item.kind.badge
        holder.badge.setTextColor(Color.WHITE)
        holder.badge.background = GradientDrawable().apply {
            cornerRadius = 4f * holder.itemView.resources.displayMetrics.density
            setColor(color)
        }
        holder.title.text = item.title
        holder.time.text = TimeFormat.full(item.startMillis)
        holder.time.setTextColor(color)
        holder.detail.text = item.detail
    }

    private fun colorFor(kind: DiffKind): Int = when (kind) {
        DiffKind.MISSING -> Color.parseColor("#D93025")
        DiffKind.EXTRA -> Color.parseColor("#EF8F32")
        DiffKind.CHANGED -> Color.parseColor("#156B5A")
    }
}
