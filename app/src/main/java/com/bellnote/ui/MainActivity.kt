package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.data.TodoItem
import com.bellnote.data.TodoStore
import com.bellnote.service.ReconcileRunner
import com.bellnote.util.Permissions
import com.bellnote.util.TimeFormat

class MainActivity : AppCompatActivity() {

    private val repository by lazy { CalendarRepository(this) }
    private lateinit var adapter: TodoAdapter
    private lateinit var emptyView: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        emptyView = findViewById(R.id.emptyView)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)

        adapter = TodoAdapter(
            onClick = { item -> startActivity(EditActivity.intentForItem(this, item)) },
            onLongClick = { item -> confirmDelete(item) }
        )

        val recycler: RecyclerView = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<View>(R.id.btnAdd).setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
        }
        findViewById<View>(R.id.btnShot).setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnReconcile).setOnClickListener {
            startActivity(Intent(this, ReconcileActivity::class.java))
        }
        findViewById<View>(R.id.statusCard).setOnClickListener {
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Permissions.hasPostNotifications(this) && !askedNotifications
        ) {
            askedNotifications = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refresh()
        runReconcileIfDue()
    }

    /** 闹钟可能被 realme 的后台限制掐掉，打开 App 时补跑一次。 */
    private fun runReconcileIfDue() {
        if (!Prefs.reconcileEnabled(this)) return
        if (!Permissions.hasCalendar(this)) return
        val interval = Prefs.reconcileInterval(this) * 60_000L
        if (System.currentTimeMillis() - Prefs.lastReconcileAt(this) < interval) return
        Thread {
            try {
                ReconcileRunner.check(this, notify = false)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "补跑对账失败", e)
            }
            runOnUiThread { updateStatus() }
        }.start()
    }

    private fun refresh() {
        updateStatus()
        if (!Permissions.hasCalendar(this)) {
            adapter.items = emptyList()
            adapter.notifyDataSetChanged()
            emptyView.visibility = View.VISIBLE
            emptyView.text = "需要日历权限才能读取待办，点上方卡片去授权"
            return
        }
        Thread {
            val items = repository.queryOwnEvents()
            runOnUiThread {
                adapter.items = items
                adapter.notifyDataSetChanged()
                emptyView.text = getString(R.string.main_empty)
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun updateStatus() {
        val problems = mutableListOf<String>()
        if (!Permissions.hasCalendar(this)) problems.add("· 日历读写权限未授权")
        if (!Permissions.hasNotificationAccess(this)) problems.add("· 通知使用权没开，收不到 ntfy 通知")
        if (Prefs.calendarId(this) <= 0) problems.add("· 还没选目标日历")

        if (problems.isEmpty()) {
            statusTitle.text = getString(R.string.main_status_ok)
            val lastAt = Prefs.lastReconcileAt(this)
            val summary = Prefs.lastReconcileSummary(this)
            val reconcileLine = if (lastAt > 0) {
                "对账：$summary（${TimeFormat.full(lastAt)}）"
            } else {
                "还没跑过对账，点下方「对账」看清单和日历差在哪"
            }
            statusDetail.text =
                "收到 ntfy 通知会自动写进日历，双提醒已生效\n$reconcileLine\n点这里可查看权限说明"
        } else {
            statusTitle.text = getString(R.string.main_status_need)
            statusDetail.text = problems.joinToString("\n") + "\n点这里逐项去开"
        }
    }

    private fun confirmDelete(item: TodoItem) {
        AlertDialog.Builder(this)
            .setTitle("删除待办")
            .setMessage("确定删除「${item.title}」？日历里的这条事件会被删除。")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    val ok = repository.deleteEvent(item.id)
                    if (ok) TodoStore(this).markDeletedByEventIds(listOf(item.id))
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this,
                            if (ok) "已删除" else "删除失败",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private var askedNotifications = false
    }
}
