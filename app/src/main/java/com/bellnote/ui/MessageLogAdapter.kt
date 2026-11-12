package com.bellnote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.R
import com.bellnote.data.MessageLog
import com.bellnote.util.StatusColors
import com.bellnote.util.TimeFormat

/** 消息记录列表：一条推送一行，结果用颜色区分，失败的可以原地重试。 */
class MessageLogAdapter(
    private val onRetry: (MessageLog.Entry) -> Unit
) : RecyclerView.Adapter<MessageLogAdapter.Holder>() {

    private val items = mutableListOf<MessageLog.Entry>()

    fun submit(entries: List<MessageLog.Entry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onRetry)
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val time: TextView = itemView.findViewById(R.id.msgTime)
        private val direction: TextView = itemView.findViewById(R.id.msgDirection)
        private val result: TextView = itemView.findViewById(R.id.msgResult)
        private val title: TextView = itemView.findViewById(R.id.msgTitle)
        private val body: TextView = itemView.findViewById(R.id.msgBody)
        private val detail: TextView = itemView.findViewById(R.id.msgDetail)
        private val retry: Button = itemView.findViewById(R.id.btnMsgRetry)

        fun bind(entry: MessageLog.Entry, onRetry: (MessageLog.Entry) -> Unit) {
            val context = itemView.context
            val source = if (entry.source.isBlank()) "" else " · ${entry.source}"
            time.text = TimeFormat.compact(entry.at) + source
            direction.text = if (entry.fromUser) "收到" else "发出"
            result.text = MessageLog.resultLabel(entry.result)
            result.setTextColor(
                when (entry.result) {
                    MessageLog.RESULT_FAILED, MessageLog.RESULT_DROPPED ->
                        StatusColors.attr(context, R.attr.bnDanger)
                    MessageLog.RESULT_OK, MessageLog.RESULT_UPDATED, MessageLog.RESULT_SENT ->
                        StatusColors.attr(context, R.attr.bnOk)
                    else -> StatusColors.attr(context, R.attr.bnTextSecondary)
                }
            )

            title.text = entry.title.ifBlank { "（无标题）" }
            body.text = entry.body.trim()
            body.visibility = if (entry.body.isBlank()) View.GONE else View.VISIBLE
            detail.text = entry.detail.trim()
            detail.visibility = if (entry.detail.isBlank()) View.GONE else View.VISIBLE

            retry.visibility = if (entry.canRetry) View.VISIBLE else View.GONE
            retry.setOnClickListener { onRetry(entry) }
        }
    }
}
