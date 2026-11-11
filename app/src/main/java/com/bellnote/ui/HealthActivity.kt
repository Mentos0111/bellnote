package com.bellnote.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.HealthCheck
import com.bellnote.data.StatusLines
import com.bellnote.service.HealthInspector
import com.bellnote.util.DeviceVendor
import com.bellnote.util.Permissions
import com.bellnote.util.SystemPages
import com.bellnote.util.ThemeManager
import com.bellnote.util.TimeFormat

/**
 * 体检详情页：一项一条，红/黄/绿 + 一句人话 + 「去开启」。
 * 教程不单独开页面，点条目弹一段「怎么开 + 为什么要开」。
 */
class HealthActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health)
        title = "运行体检"

        container = findViewById(R.id.itemContainer)
        findViewById<Button>(R.id.btnRecheck).setOnClickListener {
            HealthInspector.refreshInBackground(this) { render() }
            render(loading = true)
        }
        render()
        HealthInspector.refreshInBackground(this) { render() }
    }

    private fun render(loading: Boolean = false) {
        val items = HealthInspector.cached(this)
        val summaryDot = findViewById<View>(R.id.summaryDot)
        val summaryTitle = findViewById<TextView>(R.id.summaryTitle)
        val summaryDetail = findViewById<TextView>(R.id.summaryDetail)
        val checkedAt = findViewById<TextView>(R.id.tvCheckedAt)

        findViewById<TextView>(R.id.tvRecent).text =
            StatusLines.recent(this).joinToString("\n")

        if (items.isEmpty()) {
            summaryTitle.text = if (loading) "正在检测…" else "还没检测过"
            summaryDetail.text = "点下面「重新检测」，几秒出结果"
            container.removeAllViews()
            checkedAt.text = ""
            return
        }

        val worst = HealthCheck.indicator(items)
        summaryDot.setBackgroundResource(dotOf(worst?.status ?: HealthCheck.Status.UNKNOWN))
        summaryTitle.text = when (worst?.status) {
            HealthCheck.Status.BAD -> "有 ${HealthCheck.pending(items).size} 项需要处理"
            HealthCheck.Status.WARN -> "一切可用，${HealthCheck.pending(items).size} 项可优化"
            else -> "一切正常"
        }
        summaryDetail.text = worst?.let { "${it.label}：${it.detail}" } ?: "所有检查项都通过"

        val at = HealthInspector.cachedAt(this)
        checkedAt.text = if (at > 0) "检测于 ${TimeFormat.full(at)}　·　${DeviceVendor.current().label}" else ""

        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.item_health, container, false)
            row.findViewById<View>(R.id.itemDot).setBackgroundResource(dotOf(item.status))
            row.findViewById<TextView>(R.id.itemLabel).text = item.label
            row.findViewById<TextView>(R.id.itemStatus).text = statusText(item.status)
            row.findViewById<TextView>(R.id.itemDetail).text = item.detail

            val action = row.findViewById<Button>(R.id.itemAction)
            if (item.action == HealthCheck.Action.NONE || item.status == HealthCheck.Status.OK) {
                action.visibility = View.GONE
            } else {
                action.visibility = View.VISIBLE
                action.text = actionText(item.action)
                action.setOnClickListener { openAction(item.action) }
            }
            row.setOnClickListener { showHowTo(item) }
            container.addView(row)
        }
    }

    private fun dotOf(status: HealthCheck.Status): Int = when (status) {
        HealthCheck.Status.BAD -> R.drawable.dot_bad
        HealthCheck.Status.WARN -> R.drawable.dot_warn
        HealthCheck.Status.UNKNOWN -> R.drawable.dot_warn
        HealthCheck.Status.OK -> R.drawable.dot_ok
    }

    private fun statusText(status: HealthCheck.Status): String = when (status) {
        HealthCheck.Status.OK -> "正常"
        HealthCheck.Status.WARN -> "可优化"
        HealthCheck.Status.BAD -> "需要处理"
        HealthCheck.Status.UNKNOWN -> "无法判定"
    }

    private fun actionText(action: HealthCheck.Action): String = when (action) {
        HealthCheck.Action.NOTIFICATION_ACCESS -> "去开通知使用权"
        HealthCheck.Action.ACCESSIBILITY -> "去开截屏服务"
        HealthCheck.Action.BATTERY -> "去加电池白名单"
        HealthCheck.Action.AUTOSTART -> "去开自启动"
        HealthCheck.Action.CALENDAR -> "去处理日历"
        HealthCheck.Action.SETTINGS -> "去设置"
        HealthCheck.Action.NONE -> "去开启"
    }

    private fun openAction(action: HealthCheck.Action) {
        val ok = when (action) {
            HealthCheck.Action.NOTIFICATION_ACCESS -> SystemPages.openNotificationAccess(this)
            HealthCheck.Action.ACCESSIBILITY -> DeviceVendor.openAccessibility(this)
            HealthCheck.Action.BATTERY -> DeviceVendor.openBattery(this)
            HealthCheck.Action.AUTOSTART -> DeviceVendor.openAutostart(this)
            HealthCheck.Action.CALENDAR -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            HealthCheck.Action.SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            HealthCheck.Action.NONE -> false
        }
        if (!ok) {
            android.widget.Toast.makeText(this, "打不开系统页面，请手动到设置里找", android.widget.Toast.LENGTH_LONG).show()
            SystemPages.openAppDetails(this)
        }
    }

    /** 点条目：讲清楚为什么 + 怎么开。 */
    private fun showHowTo(item: HealthCheck.Item) {
        val vendor = DeviceVendor.current()
        val how = when (item.action) {
            HealthCheck.Action.NOTIFICATION_ACCESS ->
                "设置 → 通知 → 通知使用权 → 找到 Bellnote → 打开。\n" +
                    "授权后监听服务才会把 ntfy 的通知读到。"
            HealthCheck.Action.ACCESSIBILITY ->
                "设置 → 无障碍（${vendor.label} 里可能叫「辅助功能」）→ 已下载的服务 → Bellnote 截屏 → 打开。\n" +
                    "只用来截当前屏幕，不读输入内容。"
            HealthCheck.Action.BATTERY ->
                "设置 → 电池 → 应用耗电管理 → Bellnote → 允许后台运行 / 不优化。\n" +
                    "不加白名单的话，息屏后 App 会被冻结，通知和补查都会停。"
            HealthCheck.Action.AUTOSTART ->
                "按机型找入口：\n${DeviceVendor.sidebarGuide().let { "" }}${vendorAutostartHint(vendor.label)}\n" +
                    "各 ROM 没有公开接口能读这个开关，所以这里只能按电池策略推断，开着也不会影响别的。"
            HealthCheck.Action.CALENDAR ->
                "设置 → 权限与后台 → 日历读写权限；没有可写日历时点「创建本地日历」。"
            HealthCheck.Action.SETTINGS ->
                "设置 → 输入渠道 → ntfy 推送 → 填主题（例如 todo-xxxx），并点「立即补查一次」确认能拉到消息。"
            HealthCheck.Action.NONE -> item.detail
        }
        AlertDialog.Builder(this)
            .setTitle("${item.label} · ${statusText(item.status)}")
            .setMessage(item.detail + "\n\n怎么处理：\n" + how)
            .setPositiveButton("知道了", null)
            .apply {
                if (item.action != HealthCheck.Action.NONE) {
                    setNeutralButton("去开启") { _, _ -> openAction(item.action) }
                }
            }
            .show()
    }

    private fun vendorAutostartHint(label: String): String = when (label) {
        "realme", "OPPO", "一加" -> "设置 → 应用 → 自启动管理 → Bellnote 允许"
        "小米 / 红米", "红米" -> "设置 → 应用设置 → 授权管理 → 自启动 → Bellnote 允许"
        "vivo / iQOO" -> "设置 → 应用与权限 → 权限管理 → 自启动 → Bellnote 允许"
        "华为", "荣耀" -> "设置 → 应用 → 应用启动管理 → Bellnote 手动管理"
        "三星" -> "设置 → 电池 → 后台使用限制 → 从不休眠的应用 → 添加 Bellnote"
        else -> "在系统设置里搜「自启动」，把 Bellnote 打开"
    }
}
