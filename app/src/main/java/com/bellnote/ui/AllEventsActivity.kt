package com.bellnote.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.util.ThemeManager
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.CalendarRepository.EventInfo
import com.bellnote.data.RepeatRule
import com.bellnote.data.SOURCE_BASELINE
import com.bellnote.data.TodoItem
import com.bellnote.data.TodoSpec
import com.bellnote.data.TodoStore
import com.bellnote.util.Permissions
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp
import java.util.concurrent.TimeUnit

/** 「全部日程」：把手机日历里的事件都列出来，不只 Bellnote 自己写的那部分。 */
class AllEventsActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var adapter: AllEventsAdapter
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var repository: CalendarRepository
    private lateinit var store: TodoStore

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_events)
        enableHomeUp()
        title = getString(R.string.title_all_events)

        repository = CalendarRepository(this)
        store = TodoStore(this)
        tvSummary = findViewById(R.id.tvSummary)
        tvEmpty = findViewById(R.id.tvEmpty)

        adapter = AllEventsAdapter { info -> showActions(info) }
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@AllEventsActivity)
            adapter = this@AllEventsActivity.adapter
            com.bellnote.util.ListMotion.apply(this)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        if (!Permissions.hasCalendar(this)) {
            tvSummary.text = getString(R.string.all_events_need_permission)
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvSummary.text = getString(R.string.all_events_loading)
        Thread {
            val now = System.currentTimeMillis()
            val events = repository.listAllEvents(
                now - TimeUnit.DAYS.toMillis(7),
                now + TimeUnit.DAYS.toMillis(120)
            )
            ui.post {
                if (isFinishing || isDestroyed) return@post
                adapter.submit(events)
                tvSummary.text = getString(R.string.all_events_summary, events.size)
                tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun showActions(info: EventInfo) {
        val actions = mutableListOf<String>()
        actions.add(getString(R.string.all_events_action_edit))
        if (!info.isOwn) actions.add(getString(R.string.all_events_action_adopt))
        actions.add(getString(R.string.all_events_action_delete))

        AlertDialog.Builder(this)
            .setTitle(info.title.ifBlank { getString(R.string.all_events_untitled) })
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.all_events_action_edit) -> openEdit(info)
                    getString(R.string.all_events_action_adopt) -> adopt(info)
                    else -> confirmDelete(info)
                }
            }
            .show()
    }

    private fun openEdit(info: EventInfo) {
        startActivity(EditActivity.intentForItem(this, info.toTodoItem()))
    }

    /** 把别人建的事件纳入 Bellnote 账本：以后对账、主页都会管它，但事件本身不动。 */
    private fun adopt(info: EventInfo) {
        Thread {
            store.record(info.toSpec(), info.id, SOURCE_BASELINE)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                toast(getString(R.string.all_events_adopted, info.title))
            }
        }.start()
    }

    private fun confirmDelete(info: EventInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.all_events_delete_title))
            .setMessage(getString(R.string.all_events_delete_message, info.title))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                Thread {
                    val ok = repository.deleteEvent(info.id)
                    if (ok) store.markDeletedByEventIds(listOf(info.id))
                    ui.post {
                        if (isFinishing || isDestroyed) return@post
                        toast(if (ok) getString(R.string.all_events_deleted) else getString(R.string.all_events_delete_failed))
                        load()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun EventInfo.toSpec(): TodoSpec {
        val minutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(1)
        return TodoSpec(title = title, startMillis = startMillis, durationMinutes = minutes, note = note)
    }

    private fun EventInfo.toTodoItem(): TodoItem {
        val minutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(1)
        return TodoItem(
            id = id,
            title = title,
            startMillis = startMillis,
            durationMinutes = minutes,
            note = note,
            repeat = RepeatRule.NONE,
            calendarId = calendarId
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
