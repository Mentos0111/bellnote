package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.util.ThemeManager
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.data.TodoStore
import com.bellnote.service.NtfyFetcher
import com.bellnote.service.NtfySender
import com.bellnote.service.ReconcileScheduler
import com.bellnote.service.ScreenshotWatcher
import com.bellnote.util.CrashLog
import com.bellnote.util.DeviceVendor
import com.bellnote.util.IcsExport
import com.bellnote.util.LedgerBackup
import com.bellnote.util.Permissions
import com.bellnote.util.TimeFormat
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp
import com.bellnote.util.SystemPages

class SettingsActivity : AppCompatActivity() {

    private val repository by lazy { CalendarRepository(this) }
    private var calendars: List<CalendarRepository.CalendarInfo> = emptyList()

    private lateinit var spCalendar: Spinner
    private lateinit var tvCalendarHint: TextView
    private lateinit var etRemind: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var cbAutoImport: CheckBox
    private lateinit var cbStrictParse: CheckBox
    private lateinit var cbReconcile: CheckBox
    private lateinit var cbReconcileNotify: CheckBox
    private lateinit var spReconcileInterval: Spinner
    private lateinit var cbShotWatch: CheckBox
    private lateinit var btnMediaPerm: Button
    private lateinit var etNtfyTopic: EditText
    private lateinit var tvIgnored: TextView
    private var intervalValues: List<Int> = emptyList()

    /** 导出内容先放这儿，用户选完文件再写（SAF 回调是异步的）。 */
    private var pendingExport: String? = null

    private val createIcs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri -> writeExport(uri) }

    private val createJson = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> writeExport(uri) }

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadCalendars() }

    private val requestMedia = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ScreenshotWatcher.ensureStarted(this)
            toast(getString(R.string.shot_saved))
        } else {
            toast(getString(R.string.shot_watch_hint))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        enableHomeUp()
        title = getString(R.string.title_settings)

        spCalendar = findViewById(R.id.spCalendar)
        tvCalendarHint = findViewById(R.id.tvCalendarHint)
        etRemind = findViewById(R.id.etRemind)
        etApiKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        cbAutoImport = findViewById(R.id.cbAutoImport)
        cbStrictParse = findViewById(R.id.cbStrictParse)
        cbReconcile = findViewById(R.id.cbReconcile)
        cbReconcileNotify = findViewById(R.id.cbReconcileNotify)
        spReconcileInterval = findViewById(R.id.spReconcileInterval)
        cbShotWatch = findViewById(R.id.cbShotWatch)
        btnMediaPerm = findViewById(R.id.btnMediaPerm)
        etNtfyTopic = findViewById(R.id.etNtfyTopic)
        setupThemeSpinner()
        tvIgnored = findViewById(R.id.tvIgnored)

        etRemind.setText(Prefs.remindMinutes(this).ifEmpty { listOf(1440, 60) }.joinToString(","))
        etApiKey.setText(Prefs.apiKey(this))
        etModel.setText(Prefs.model(this))
        cbAutoImport.isChecked = Prefs.autoImport(this)
        cbStrictParse.isChecked = Prefs.strictParse(this)
        cbReconcile.isChecked = Prefs.reconcileEnabled(this)
        cbReconcileNotify.isChecked = Prefs.reconcileNotify(this)
        cbShotWatch.isChecked = Prefs.shotWatchEnabled(this)
        etNtfyTopic.setText(Prefs.ntfyTopic(this))
        btnMediaPerm.setOnClickListener { requestMediaPermission() }
        findViewById<Button>(R.id.btnCatchUpNow).setOnClickListener { catchUpNow() }
        findViewById<Button>(R.id.btnSendTest).setOnClickListener { sendTestPush() }
        findViewById<Button>(R.id.btnExportIcs).setOnClickListener { exportIcs() }
        findViewById<Button>(R.id.btnBackupLedger).setOnClickListener { backupLedger() }
        findViewById<Button>(R.id.btnCrashLog).setOnClickListener { showCrashLog() }
        findViewById<Button>(R.id.btnMailDev).setOnClickListener { mailDeveloper() }
        findViewById<Button>(R.id.btnClearIgnored).setOnClickListener {
            Prefs.clearIgnoredNotices(this)
            renderIgnored()
            toast("已清空")
        }
        renderIgnored()
        setupIntervalSpinner()
        findViewById<TextView>(R.id.tvAboutVersion).text = aboutText()

        findViewById<Button>(R.id.btnReconcileNow).setOnClickListener {
            startActivity(Intent(this, ReconcileActivity::class.java))
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnCreateCalendar).setOnClickListener { createLocalCalendar() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            if (!SystemPages.openNotificationAccess(this)) {
                toast("打不开通知使用权页面，请手动到 设置→应用→特殊应用权限")
            }
        }
        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            if (com.bellnote.service.ScreenCaptureService.isConnected()) {
                toast("截屏服务已经开着")
            } else if (!DeviceVendor.openAccessibility(this)) {
                toast("打不开无障碍页面，请手动到 设置→无障碍→Bellnote 截屏")
            }
        }
        findViewById<Button>(R.id.btnSidebarGuide).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.sidebar_howto_title))
                .setMessage(DeviceVendor.sidebarGuide())
                .setPositiveButton("知道了", null)
                .show()
        }
        findViewById<Button>(R.id.btnTileGuide).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.nav_tile))
                .setMessage(getString(R.string.tile_guide))
                .setPositiveButton("知道了", null)
                .show()
        }
        findViewById<Button>(R.id.btnCalendarPerm).setOnClickListener {
            if (Permissions.hasCalendar(this)) toast("日历权限已经有了")
            else requestCalendar.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener {
            if (!SystemPages.openAutostart(this)) toast("打不开自启动页面，请手动到 设置→应用→自启动管理")
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            if (!SystemPages.openBattery(this)) toast("打不开电池页面，请手动到 设置→电池→应用耗电管理")
        }
        findViewById<Button>(R.id.btnAppDetail).setOnClickListener {
            SystemPages.openAppDetails(this)
        }

        loadCalendars()
    }

    /** 配色主题：选中即生效，直接 recreate 让整页换成新调色板。 */
    private fun setupThemeSpinner() {
        val spinner = findViewById<Spinner>(R.id.spTheme)
        val palettes = ThemeManager.palettes
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            palettes.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val current = Prefs.theme(this)
        spinner.setSelection(palettes.indexOfFirst { it.id == current }.coerceAtLeast(0))
        var initial = true
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (initial) {
                    initial = false
                    return
                }
                val picked = palettes.getOrNull(position) ?: return
                if (picked.id == Prefs.theme(this@SettingsActivity)) return
                Prefs.setTheme(this@SettingsActivity, picked.id)
                toast("已换成「${picked.label}」")
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 间隔做成选项，避免手输数字；当前值不在列表里时补进去，不动用户已有设置。 */
    private fun setupIntervalSpinner() {
        val presets = listOf(15, 30, 45, 60, 90, 120, 180, 1440)
        val current = Prefs.reconcileInterval(this)
        intervalValues = (presets + current).distinct().sorted()
        spReconcileInterval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            intervalValues.map { intervalLabel(it) }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spReconcileInterval.setSelection(intervalValues.indexOf(current).coerceAtLeast(0))
    }

    private fun intervalLabel(minutes: Int): String = when {
        minutes >= 1440 && minutes % 1440 == 0 -> "每天一次（${minutes / 1440} 天）"
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "$minutes 分钟"
    }

    private fun loadCalendars() {
        if (!Permissions.hasCalendar(this)) {
            spCalendar.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item, listOf("（先授权日历权限）")
            )
            tvCalendarHint.text = "读不到日历列表：先点下面的「日历读写权限」"
            requestCalendar.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
            return
        }
        Thread {
            val list = repository.listCalendars()
            runOnUiThread {
                calendars = list
                val names = if (list.isEmpty()) listOf("（没有可写日历）")
                else list.map { "${it.displayName}" + if (it.accountName.isBlank()) "" else "（${it.accountName}）" }
                spCalendar.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item, names
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                val saved = Prefs.calendarId(this)
                val index = list.indexOfFirst { it.id == saved }
                if (index >= 0) spCalendar.setSelection(index)
                tvCalendarHint.text = if (list.isEmpty()) {
                    "没有可写日历（无 GMS 的 realme 很常见）：点下面「创建本地日历」"
                } else {
                    "共 ${list.size} 个可写日历，收到通知后写到选中的这个"
                }
            }
        }.start()
    }

    /** 「关于」卡：版本 + 构建号 + 开发者 + 邮箱。 */
    private fun aboutText(): String = buildString {
        append(versionLabel())
        append("\n开发者：")
        append(getString(R.string.dev_name))
        append("（")
        append(getString(R.string.dev_mail))
        append("）")
        append("\n")
        append(com.bellnote.util.DeviceVendor.current().label)
        append(" · Android ")
        append(android.os.Build.VERSION.RELEASE)
    }

    private fun mailDeveloper() {
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:" + getString(R.string.dev_mail))
            putExtra(
                android.content.Intent.EXTRA_SUBJECT,
                "Bellnote 反馈（${com.bellnote.util.ThemeManager.labelOf(Prefs.theme(this@SettingsActivity))}）"
            )
            putExtra(
                android.content.Intent.EXTRA_TEXT,
                "\n\n——\n" + versionLabel() + "\n" +
                    com.bellnote.util.DeviceVendor.current().label + " Android " + android.os.Build.VERSION.RELEASE
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 没装邮件 App 就直接把地址复制到剪贴板
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("email", getString(R.string.dev_mail))
            )
            toast("没装邮件 App，地址已复制：${getString(R.string.dev_mail)}")
        }
    }

    private fun versionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "Bellnote v${info.versionName}（构建 $code）"
    } catch (e: Exception) {
        "Bellnote"
    }

    private fun createLocalCalendar() {
        if (!Permissions.hasCalendar(this)) {
            toast("先给日历权限，再点一次")
            requestCalendar.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
            return
        }
        Thread {
            val id = repository.createLocalCalendar("待办")
            runOnUiThread {
                if (id > 0) {
                    Prefs.setCalendarId(this, id)
                    toast("已创建「待办」本地日历并设为目标日历")
                    loadCalendars()
                } else {
                    toast("创建失败（系统拒绝了）：可装个支持本地日历的日历 App 再试")
                }
            }
        }.start()
    }

    private fun saveSettings() {
        val remindText = etRemind.text.toString()
        val minutes = remindText.split(',', '，', ' ', '、')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .take(Prefs.MAX_REMINDERS)
        val effective = minutes.ifEmpty { listOf(1440, 60) }
        Prefs.setRemindMinutes(this, effective)
        Prefs.setApiKey(this, etApiKey.text.toString())
        Prefs.setModel(this, etModel.text.toString())
        Prefs.setAutoImport(this, cbAutoImport.isChecked)
        Prefs.setStrictParse(this, cbStrictParse.isChecked)
        Prefs.setShotWatchEnabled(this, cbShotWatch.isChecked)
        Prefs.setNtfyTopic(this, etNtfyTopic.text.toString())
        if (cbShotWatch.isChecked) ScreenshotWatcher.ensureStarted(this)

        val interval = intervalValues.getOrNull(spReconcileInterval.selectedItemPosition)
            ?: Prefs.DEFAULT_RECONCILE_INTERVAL
        Prefs.setReconcileEnabled(this, cbReconcile.isChecked)
        Prefs.setReconcileNotify(this, cbReconcileNotify.isChecked)
        Prefs.setReconcileInterval(this, interval)
        ReconcileScheduler.sync(this)

        val index = spCalendar.selectedItemPosition
        if (index in calendars.indices) {
            Prefs.setCalendarId(this, calendars[index].id)
        }
        val reconcileText = if (cbReconcile.isChecked) {
            "${intervalLabel(Prefs.reconcileInterval(this))}对账一次"
        } else {
            "定时对账已关"
        }
        toast("设置已保存（提醒：${effective.joinToString(" / ")} 分钟前；$reconcileText）")
        if (Prefs.ntfyTopic(this).isNotBlank()) NtfyFetcher.catchUpInBackground(this)
        finish()
    }

    /** 把「最近没写进日历的推送」和上次补查结果摊在设置页，出问题不用连 adb 也能看。 */
    private fun renderIgnored() {
        val entries = Prefs.ignoredNotices(this)
        val lastAt = Prefs.lastCatchUpAt(this)
        val summary = Prefs.lastCatchUpSummary(this)
        val builder = StringBuilder()
        if (lastAt > 0L) {
            builder.append("上次补查：${TimeFormat.full(lastAt)}")
            if (summary.isNotBlank()) builder.append("　$summary")
            builder.append("\n")
        }
        if (entries.isEmpty()) {
            if (builder.isEmpty()) builder.append(getString(R.string.hint_ignored_empty))
        } else {
            builder.append("共 ${entries.size} 条（最新在上）：\n")
            for (entry in entries) {
                builder.append("· ${TimeFormat.monthDay(entry.at)}　${entry.reason}\n")
                builder.append("　${entry.text}\n")
            }
        }
        tvIgnored.text = builder.toString().trim()
    }

    /** 立即补查一次：先把输入框里的主题存下来，再联网拉，结果直接回显。 */
    private fun catchUpNow() {
        Prefs.setNtfyTopic(this, etNtfyTopic.text.toString())
        if (Prefs.ntfyTopic(this).isBlank()) {
            toast("先填 ntfy 主题（例如 todo-xxxx），再点补查")
            return
        }
        val button = findViewById<Button>(R.id.btnCatchUpNow)
        button.isEnabled = false
        toast("正在补查…")
        Thread {
            val result = NtfyFetcher.catchUp(this)
            runOnUiThread {
                button.isEnabled = true
                renderIgnored()
                toast(result.summary())
            }
        }.start()
    }

    /** 从手机这头发一条测试推送，不用电脑也能自检整条链路。 */
    private fun sendTestPush() {
        Prefs.setNtfyTopic(this, etNtfyTopic.text.toString())
        if (Prefs.ntfyTopic(this).isBlank()) {
            toast("先填 ntfy 主题（例如 todo-xxxx）")
            return
        }
        val button = findViewById<Button>(R.id.btnSendTest)
        button.isEnabled = false
        Thread {
            val result = NtfySender.sendTest(this)
            runOnUiThread {
                button.isEnabled = true
                toast(if (result.ok) result.detail else "发不出去：${result.detail}")
            }
        }.start()
    }

    /** 导出账本为 .ics（走系统文件选择器，不用存储权限）。 */
    private fun exportIcs() {
        Thread {
            val items = TodoStore(this).all()
            pendingExport = IcsExport.build(items)
            runOnUiThread {
                if (items.isEmpty()) {
                    toast("账本是空的，没什么可导出")
                    return@runOnUiThread
                }
                createIcs.launch("bellnote-${stampForFile()}.ics")
            }
        }.start()
    }

    private fun backupLedger() {
        Thread {
            val items = TodoStore(this).all()
            pendingExport = LedgerBackup.toJson(items)
            runOnUiThread {
                createJson.launch("bellnote-ledger-${stampForFile()}.json")
            }
        }.start()
    }

    private fun stampForFile(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
            .format(java.util.Date())

    private fun writeExport(uri: android.net.Uri?) {
        val content = pendingExport
        if (uri == null || content == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            toast("已导出")
        } catch (e: Exception) {
            toast("导出失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            pendingExport = null
        }
    }

    private fun showCrashLog() {
        val text = CrashLog.read(this)
        if (text.isNullOrBlank()) {
            toast("没有崩溃记录：后台被系统杀不会留痕，那种情况看首页的补查/对账状态")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("最近的崩溃")
            .setMessage(text.take(4000))
            .setPositiveButton("知道了", null)
            .setNeutralButton("清空") { _, _ ->
                CrashLog.clear(this)
                toast("已清空")
            }
            .show()
    }

    private fun requestMediaPermission() {        if (Permissions.hasMediaImages(this)) {
            toast(getString(R.string.shot_saved))
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestMedia.launch(permission)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
