package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.ai.LocalLlm
import com.bellnote.ai.ModelDownloader
import com.bellnote.util.SidebarEntry
import com.bellnote.util.ThemeManager
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.data.TodoStore
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
    private val uiHandler = Handler(Looper.getMainLooper())
    private var calendars: List<CalendarRepository.CalendarInfo> = emptyList()

    private lateinit var spCalendar: Spinner
    private lateinit var tvCalendarHint: TextView
    private lateinit var etRemind: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var cbReconcile: CheckBox
    private lateinit var cbReconcileNotify: CheckBox
    private lateinit var spReconcileInterval: Spinner
    private lateinit var cbShotWatch: CheckBox
    private lateinit var btnMediaPerm: Button
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

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        toast(if (granted) "通知已开启" else "没开通知：写日历结果、对账差异都不会弹提醒，App 里照样能看到")
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
        cbReconcile = findViewById(R.id.cbReconcile)
        cbReconcileNotify = findViewById(R.id.cbReconcileNotify)
        spReconcileInterval = findViewById(R.id.spReconcileInterval)
        cbShotWatch = findViewById(R.id.cbShotWatch)
        btnMediaPerm = findViewById(R.id.btnMediaPerm)
        setupThemeSpinner()
        setupThemeModeSpinner()
        setupEngineChooser()

        etRemind.setText(Prefs.remindMinutes(this).ifEmpty { listOf(1440, 60) }.joinToString(","))
        etApiKey.setText(Prefs.apiKey(this))
        etModel.setText(Prefs.model(this))
        cbReconcile.isChecked = Prefs.reconcileEnabled(this)
        cbReconcileNotify.isChecked = Prefs.reconcileNotify(this)
        cbShotWatch.isChecked = Prefs.shotWatchEnabled(this)
        btnMediaPerm.setOnClickListener { requestMediaPermission() }
        findViewById<Button>(R.id.btnNtfy).setOnClickListener {
            startActivity(Intent(this, ThirdPartyActivity::class.java))
        }
        findViewById<Button>(R.id.btnExportIcs).setOnClickListener { exportIcs() }
        findViewById<Button>(R.id.btnBackupLedger).setOnClickListener { backupLedger() }
        findViewById<Button>(R.id.btnCrashLog).setOnClickListener { showCrashLog() }
        findViewById<Button>(R.id.btnFeedback).setOnClickListener {
            startActivity(Intent(this, FeedbackActivity::class.java))
        }

        // 离线大模型：下载 / 取消 / 试跑，按钮在「识别方式」选中本地模型时出现
        findViewById<Button>(R.id.btnPrivacy).setOnClickListener {
            com.bellnote.util.PrivacyGate.openFullText(this)
        }
        findViewById<Button>(R.id.btnOnboarding).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<Button>(R.id.btnHealth).setOnClickListener {
            startActivity(Intent(this, HealthActivity::class.java))
        }
        findViewById<Button>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        renderNtfyEntry()
        setupIntervalSpinner()

        findViewById<Button>(R.id.btnReconcileNow).setOnClickListener {
            startActivity(Intent(this, ReconcileActivity::class.java))
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
        setupHelp()
        setupSections()
        findViewById<Button>(R.id.btnCreateCalendar).setOnClickListener { createLocalCalendar() }
        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            if (com.bellnote.service.ScreenCaptureService.isConnected()) {
                toast("截屏服务已经开着")
            } else if (!DeviceVendor.openAccessibility(this)) {
                toast("打不开无障碍页面，请手动到 设置→无障碍→Bellnote 截屏")
            }
        }
        findViewById<Button>(R.id.btnNotifPerm).setOnClickListener {
            if (Permissions.hasPostNotifications(this)) {
                toast("通知权限已经有了")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                toast("这个系统版本不用单独授权通知")
            }
        }
        findViewById<Button>(R.id.btnBatteryPerm).setOnClickListener {
            if (com.bellnote.service.HealthInspector.isIgnoringBatteryOptimizations(this)) {
                toast("已经在白名单里了")
            } else if (!DeviceVendor.openBattery(this)) {
                toast("打不开电池页面，请手动到 设置 → 电池 → 应用耗电管理")
            }
        }
        findViewById<Button>(R.id.btnAutostartPerm).setOnClickListener {
            if (!DeviceVendor.openAutostart(this)) {
                toast("打不开自启动页面，去系统设置里搜「自启动」")
            }
        }
        findViewById<Button>(R.id.btnSidebarEntry).setOnClickListener {
            val now = SidebarEntry.isEnabled(this)
            val next = SidebarEntry.setEnabled(this, !now)
            renderSidebarEntry()
            toast(getString(if (next) R.string.sidebar_entry_on_toast else R.string.sidebar_entry_off_toast))
        }
        renderSidebarEntry()

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
        findViewById<Button>(R.id.btnAppDetail).setOnClickListener {
            SystemPages.openAppDetails(this)
        }

        loadCalendars()
    }

    // ---------------- 识别方式（四行单选 + 本地模型） ----------------

    private var llmTicker: Runnable? = null

    /** 一行 = 一个识别方式；点整行就选中，选中的那行右侧打勾。 */
    private data class EngineRow(val engine: String, val rowId: Int, val checkId: Int)

    private val engineRows by lazy {
        listOf(
            EngineRow(Prefs.ENGINE_AUTO, R.id.engineAuto, R.id.engineAutoCheck),
            EngineRow(Prefs.ENGINE_LOCAL, R.id.engineLocal, R.id.engineLocalCheck),
            EngineRow(Prefs.ENGINE_LLM, R.id.engineLlm, R.id.engineLlmCheck),
            EngineRow(Prefs.ENGINE_CLOUD, R.id.engineCloud, R.id.engineCloudCheck)
        )
    }

    private fun setupEngineChooser() {
        engineRows.forEach { row ->
            findViewById<View>(row.rowId).setOnClickListener { pickEngine(row.engine) }
        }
        findViewById<Button>(R.id.btnLlmAction).setOnClickListener { onLlmAction() }
        renderEngineUi()
    }

    private fun pickEngine(engine: String) {
        val previous = Prefs.ocrEngine(this)
        Prefs.setOcrEngine(this, engine)
        renderEngineUi()
        // 选了本地模型但还没下：直接问一句，别让人自己找下载入口
        if (engine == Prefs.ENGINE_LLM && previous != engine && !LocalLlm.isReady(this)) {
            confirmDownloadModel()
        }
    }

    private fun confirmDownloadModel() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("下载本地模型？")
            .setMessage("657 MB，下载一次就一直在手机上，之后断网也能识别。不想现在下可以先取消，识别方式保留不动。")
            .setPositiveButton("下载") { _, _ -> startModelDownload() }
            .setNegativeButton("先不下", null)
            .show()
    }

    private fun startModelDownload() {
        if (ModelDownloader.enqueue(this)) {
            toast("开始下载，通知栏也能看进度")
        } else {
            toast("下载没起来，检查一下网络")
        }
        renderEngineUi()
    }

    /** 本地模型那一块的动作按钮：没下就下，下着就取消，下好了就试跑。 */
    private fun onLlmAction() {
        when (ModelDownloader.status(this)) {
            ModelDownloader.Status.Ready -> runLocalLlmProbe()
            is ModelDownloader.Status.Downloading -> confirmCancelDownload()
            else -> startModelDownload()
        }
    }

    private fun confirmCancelDownload() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("取消下载？")
            .setMessage("已经下了一部分，取消后下次要重新开始。")
            .setPositiveButton("取消下载") { _, _ ->
                ModelDownloader.cancel(this)
                renderEngineUi()
            }
            .setNegativeButton("继续下", null)
            .show()
    }

    /** 勾选状态、本地模型的小状态、下面的下载/试跑块，都从这里统一刷。 */
    private fun renderEngineUi() {
        val current = Prefs.ocrEngine(this).ifBlank { Prefs.ENGINE_AUTO }
        engineRows.forEach { row ->
            findViewById<ImageView>(row.checkId).visibility =
                if (row.engine == current) View.VISIBLE else View.INVISIBLE
        }

        val state = ModelDownloader.status(this)
        // 状态直接写在「本地模型」那行的说明里，不再挤一个右侧小字（会跟说明抢位置）
        findViewById<TextView>(R.id.tvEngineLlmDesc).text = when (state) {
            ModelDownloader.Status.Ready -> "手机上的 AI；已下载，断网也能用"
            is ModelDownloader.Status.Downloading -> "手机上的 AI；正在下载 ${state.percent}%"
            is ModelDownloader.Status.Failed -> "手机上的 AI；上次没下完"
            ModelDownloader.Status.Missing -> "手机上的 AI；要先下载 657 MB"
        }

        llmTicker?.let { uiHandler.removeCallbacks(it) }
        llmTicker = null

        // 只有选了本地模型、或者正在下载时才展开，平时不占地方
        val panel = findViewById<View>(R.id.llmPanel)
        val showPanel = current == Prefs.ENGINE_LLM || state is ModelDownloader.Status.Downloading
        panel.visibility = if (showPanel) View.VISIBLE else View.GONE
        if (!showPanel) return

        val button = findViewById<Button>(R.id.btnLlmAction)
        val status = findViewById<TextView>(R.id.tvLlmStatus)
        val totalMb = ModelDownloader.EXPECTED_BYTES / 1024 / 1024
        when (state) {
            ModelDownloader.Status.Ready -> {
                button.text = "试跑一次"
                status.text = "已就绪（$totalMb MB），识别时不联网。"
            }
            is ModelDownloader.Status.Downloading -> {
                button.text = "取消下载"
                status.text = "已下 ${state.bytes / 1024 / 1024} / $totalMb MB，通知栏也能看进度"
                val tick = Runnable { renderEngineUi() }
                llmTicker = tick
                uiHandler.postDelayed(tick, 1000)
            }
            is ModelDownloader.Status.Failed -> {
                button.text = "重新下载（$totalMb MB）"
                status.text = "${state.reason}，点按钮重下"
            }
            ModelDownloader.Status.Missing -> {
                button.text = "下载模型（$totalMb MB）"
                status.text = "还没下载：下载一次，之后断网也能用它识别。"
            }
        }
    }

    /**
     * 离线大模型试跑：拿一段固定样例（就像聊天截图里的几行）跑一遍，
     * 把耗时 / 原始输出 / 解析结果摊出来。
     */
    private fun runLocalLlmProbe() {
        val sample = "化学课\n11月14日 周六\n20:30\n把第三章错题重做一遍\n明天 9:00 交实验报告"
        toast("正在跑离线模型，第一次要几秒…")
        Thread {
            val probe = com.bellnote.ai.LocalLlm.probe(this, sample)
            runOnUiThread {
                val lines = buildString {
                    appendLine("耗时：${probe.millis} ms")
                    appendLine("解析出：${probe.specs.size} 条")
                    probe.specs.forEach {
                        appendLine(
                            "· ${it.title} | ${com.bellnote.util.TimeFormat.full(it.startMillis)}" +
                                " | ${it.durationMinutes} 分钟"
                        )
                    }
                    probe.error?.let { appendLine("错误：$it") }
                    appendLine()
                    appendLine("原始输出：")
                    appendLine(probe.raw.take(1500))
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("离线模型试跑")
                    .setMessage(lines)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }.start()
    }

    /** 配色主题：选中即生效，直接 recreate 让整页换成新调色板。 */
    private fun setupThemeSpinner() {        val spinner = findViewById<Spinner>(R.id.spTheme)
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
                ThemeManager.scheduleLauncherIcon()
                toast("已换成「${picked.label}」")
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 明暗：跟随系统 / 浅色 / 深色。选中即生效，AppCompat 会自己重建页面。 */
    private fun setupThemeModeSpinner() {
        val spinner = findViewById<Spinner>(R.id.spThemeMode)
        val modes = ThemeManager.nightModes
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            modes.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val current = Prefs.themeMode(this)
        spinner.setSelection(modes.indexOfFirst { it.id == current }.coerceAtLeast(0))
        var initial = true
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (initial) {
                    initial = false
                    return
                }
                val picked = modes.getOrNull(position) ?: return
                if (picked.id == Prefs.themeMode(this@SettingsActivity)) return
                Prefs.setThemeMode(this@SettingsActivity, picked.id)
                ThemeManager.applyNightMode(this@SettingsActivity)
                toast("外观：${picked.label}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 从「第三方通知」页回来时刷新这行状态。 */
    override fun onResume() {
        super.onResume()
        renderNtfyEntry()
        renderEngineUi()
    }

    override fun onDestroy() {
        llmTicker?.let { uiHandler.removeCallbacks(it) }
        llmTicker = null
        super.onDestroy()
    }





    /** 分级目录：四个分组都是收起的，点标题才展开。 */
    private fun setupSections() {
        // 每次打开都从全收起开始，不记住上次的展开状态
        bindSection(R.id.headInput, R.id.groupInput, R.id.arrowInput)
        bindSection(R.id.headCalendar, R.id.groupCalendar, R.id.arrowCalendar)
        bindSection(R.id.headBackup, R.id.groupBackup, R.id.arrowBackup)
        bindSection(R.id.headAppearance, R.id.groupAppearance, R.id.arrowAppearance)
        bindSection(R.id.headDiagnostics, R.id.groupDiagnostics, R.id.arrowDiagnostics)
    }

    private fun bindSection(headId: Int, groupId: Int, arrowId: Int) {
        val group = findViewById<View>(groupId)
        val arrow = findViewById<android.widget.ImageView>(arrowId)
        var open = false
        group.visibility = View.GONE
        arrow.rotation = 0f
        findViewById<View>(headId).setOnClickListener {
            open = !open
            group.visibility = if (open) View.VISIBLE else View.GONE
            arrow.animate().rotation(if (open) 90f else 0f).setDuration(150L).start()
        }
    }

    /** 每个分组的问号：把原来铺在页面上的长说明收进弹窗。 */
    private fun setupHelp() {
        help(R.id.btnHelpEntry, "其他入口", "除了首页输入框，还有两条更快的路：\n\n" +
            "· 侧边栏：系统侧边栏添加 App 时只认带桌面图标的，所以要先临时显示「Bellnote 截屏记」这个桌面入口，" +
            "加进侧边栏后再把它隐藏——它只是添加时的临时副产物，不用长期放在桌面上。已经加进侧边栏的入口不受影响。\n" +
            "· 通知栏：下拉通知栏点「记一条」，直接回首页并聚焦输入框。")

        help(R.id.btnHelpA11y, "系统权限", "· 截屏服务（无障碍）：只用来截当前屏幕，不读输入内容；不开就不能从侧边栏点一下截屏。\n" +
            "· 推送不需要任何系统权限：Bellnote 自己定时去 ntfy 服务器拉。")

        help(R.id.btnHelpReconcile, "对账", "定时把本地的待办清单和系统日历对一遍，" +
            "发现「清单有、日历没有」「日历被删了」「时间被改了」会提醒你。\n\n" +
            "· 通知开关：控制对账发现问题时要不要弹提醒。\n" +
            "· 立即对账：不看间隔，马上跑一次。")


        help(R.id.btnHelpBackup, "备份", "· 导出 .ics：把 Bellnote 里记的待办导成日历文件，可以导进别的日历 App。\n" +
            "· 备份账本：导出 JSON（待办 + 它们和系统日历事件的对应关系），换机或重装后拿来核对，不会自动还原。")
    }

    private fun help(buttonId: Int, title: String, message: String) {
        findViewById<View>(buttonId).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /** 状态行只在启用了 ntfy 时出现；没启用就完全不占地方。 */
    private fun renderNtfyEntry() {
        val on = Prefs.ntfyEnabled(this)
        val hasTopic = Prefs.ntfyTopic(this).isNotBlank()
        val line = findViewById<TextView>(R.id.tvNtfySummary)
        line.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            line.text = if (hasTopic) {
                "已开启：电脑上的待办会同步进日历"
            } else {
                "已开启，还没填主题"
            }
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
                    "读不到可写日历（不少系统不自带可写的日历账号）：点下面「创建本地日历」"
                } else {
                    "共 ${list.size} 个可写日历，收到通知后写到选中的这个"
                }
            }
        }.start()
    }




    /** 桌面入口开关的文案跟着实际状态走。 */
    private fun renderSidebarEntry() {
        val shown = SidebarEntry.isEnabled(this)
        findViewById<Button>(R.id.btnSidebarEntry).text = getString(
            if (shown) R.string.sidebar_entry_hide else R.string.sidebar_entry_show
        )
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
        Prefs.setShotWatchEnabled(this, cbShotWatch.isChecked)
        if (cbShotWatch.isChecked) {
            // 打开截图监听时才要相册权限，没给就当场问一次
            if (Permissions.hasMediaImages(this)) ScreenshotWatcher.ensureStarted(this)
            else requestMediaPermission()
        }

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
        finish()
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
