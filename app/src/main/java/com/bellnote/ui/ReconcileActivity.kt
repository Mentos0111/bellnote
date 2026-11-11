package com.bellnote.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.util.ThemeManager
import com.bellnote.R
import com.bellnote.data.DiffItem
import com.bellnote.data.ReconcileReport
import com.bellnote.data.Reconciler
import com.bellnote.service.ReconcileRunner
import com.bellnote.util.Permissions
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp
import com.bellnote.util.TimeFormat

/**
 * 对账页：把「本地清单」和「日历里实际有的」摆在一起看。
 * 只报告，所有修复都要手动点，避免误删误写。
 */
class ReconcileActivity : AppCompatActivity() {

    private lateinit var adapter: DiffAdapter
    private lateinit var tvSummary: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnFill: Button
    private lateinit var btnClean: Button
    private lateinit var btnFix: Button
    private lateinit var btnPurge: Button

    private var report: ReconcileReport? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reconcile)
        enableHomeUp()
        title = getString(R.string.title_reconcile)

        tvSummary = findViewById(R.id.tvSummary)
        tvDetail = findViewById(R.id.tvDetail)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnFill = findViewById(R.id.btnFill)
        btnClean = findViewById(R.id.btnClean)
        btnFix = findViewById(R.id.btnFix)
        btnPurge = findViewById(R.id.btnPurge)

        adapter = DiffAdapter()
        val recycler: RecyclerView = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnRefresh.setOnClickListener { refresh() }
        btnFill.setOnClickListener { repair("已补齐", { Reconciler.applyMissing(this, it) }, report?.missing.orEmpty()) }
        btnClean.setOnClickListener { repair("已清理", { Reconciler.applyExtra(this, it) }, report?.extra.orEmpty()) }
        btnFix.setOnClickListener { repair("已修正", { Reconciler.applyChanged(this, it) }, report?.changed.orEmpty()) }
        btnPurge.setOnClickListener { repair("已从清单移除", { Reconciler.applyRemoved(this, it) }, report?.removed.orEmpty()) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (busy) return
        if (!Permissions.hasCalendar(this)) {
            tvSummary.text = "读不到日历"
            tvDetail.text = "先给日历读写权限，再回来对账"
            tvEmpty.text = "缺少日历权限"
            tvEmpty.visibility = View.VISIBLE
            adapter.items = emptyList()
            adapter.notifyDataSetChanged()
            setButtonsEnabled(false, false, false, false)
            return
        }
        busy = true
        btnRefresh.isEnabled = false
        tvDetail.text = "正在对账…"
        Thread {
            val result = try {
                ReconcileRunner.check(this, notify = false)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                busy = false
                btnRefresh.isEnabled = true
                if (result == null) {
                    tvDetail.text = "对账失败：读日历出错了，稍后再点一次"
                    return@runOnUiThread
                }
                report = result
                render(result)
            }
        }.start()
    }

    private fun render(result: ReconcileReport) {
        tvSummary.text = "清单 ${result.localCount} 条 · 日历 ${result.calendarCount} 条"

        val lines = mutableListOf<String>()
        lines.add(if (result.consistent) "一致 ✅ 没有发现差异" else "差异：${result.summary()}")
        if (result.baselineImported > 0) {
            lines.add("首次对账：已把日历里现有 ${result.baselineImported} 条待办收进本地清单当基线")
        }
        if (result.checkedAt > 0) lines.add("刚对完 · ${TimeFormat.full(result.checkedAt)}")
        tvDetail.text = lines.joinToString("\n")

        adapter.items = result.diffs
        adapter.notifyDataSetChanged()

        if (result.diffs.isEmpty()) {
            tvEmpty.text = "日历和待办清单完全对得上，不用管"
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
        setButtonsEnabled(
            result.missing.isNotEmpty(),
            result.extra.isNotEmpty(),
            result.changed.isNotEmpty(),
            result.removed.isNotEmpty()
        )
    }

    private fun setButtonsEnabled(fill: Boolean, clean: Boolean, fix: Boolean, purge: Boolean) {
        btnFill.isEnabled = fill && !busy
        btnClean.isEnabled = clean && !busy
        btnFix.isEnabled = fix && !busy
        btnPurge.isEnabled = purge && !busy
    }

    private fun repair(
        label: String,
        action: (List<DiffItem>) -> Int,
        targets: List<DiffItem>
    ) {
        if (busy) return
        if (targets.isEmpty()) {
            toast("没有要处理的条目")
            return
        }
        busy = true
        setButtonsEnabled(false, false, false, false)
        btnRefresh.isEnabled = false
        tvDetail.text = "正在处理 ${targets.size} 条…"
        Thread {
            val done = try {
                action(targets)
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                busy = false
                btnRefresh.isEnabled = true
                if (done < 0) toast("$label 失败：日历拒绝了操作")
                else toast("$label $done 条")
                refresh()
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
