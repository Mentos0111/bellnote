package com.bellnote.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.App
import com.bellnote.ai.LocalLlm
import com.bellnote.ai.TextEnginePlan
import com.bellnote.ai.TextExtractor
import com.bellnote.ai.VisionExtractor
import com.bellnote.util.ThemeManager
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.MANUAL_DEFAULT_DURATION_MINUTES
import com.bellnote.data.HealthCheck
import com.bellnote.data.Prefs
import com.bellnote.data.SOURCE_AI
import com.bellnote.data.StatusLines
import com.bellnote.data.TodoItem
import com.bellnote.data.TodoEvents
import com.bellnote.data.TodoSpec
import com.bellnote.data.TodoStore
import com.bellnote.parser.QuickParse
import com.bellnote.service.HealthInspector
import com.bellnote.service.NtfyFetcher
import com.bellnote.service.NtfyReceiveService
import com.bellnote.service.ReconcileRunner
import com.bellnote.service.ScreenshotWatcher
import com.bellnote.util.DeviceVendor
import com.bellnote.util.Permissions
import com.bellnote.util.StatusColors
import com.bellnote.util.TimeFormat
import java.io.File

class MainActivity : AppCompatActivity() {

    private val repository by lazy { CalendarRepository(this) }
    private lateinit var adapter: TodoAdapter
    private var brandGlowAnimator: android.animation.ValueAnimator? = null
    private val glowHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var glowColorCurrent = 0
    private var lastGlowLevel: StatusLines.Level? = null
    private var warnFlashDone = false
    private var warnFlashUntil = 0L
    /** 每分钟重绑一次列表，让卡片上的时间进度条自己往前走。 */
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            // 只更新「还剩多久」那一层，整表别重绑，不然每分钟会抖一下
            adapter.tickProgress()
            ticker.postDelayed(this, 60_000L)
        }
    }
    private lateinit var emptyView: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var etQuick: EditText
    /** 首页输入框旁边那颗「AI」：把这一句交给云端 / 本地模型，而不是只走本地规则。 */
    private lateinit var btnQuickAi: TextView
    private var aiRunning = false
    private var aiDialog: AlertDialog? = null
    /** 批量写入前先要日历权限；授权回来后继续写这一批。 */
    private var pendingAiSpecs: List<TodoSpec>? = null
    private var pendingCameraUri: Uri? = null
    /** 抽屉还在滑回时要执行的动作（见 afterDrawerClosed）。 */
    private var pendingDrawerAction: (() -> Unit)? = null
    /** 抽屉位移/遮罩动画的当前值，自己控时长时用。 */
    private var drawerScrimCurrent: Int = android.graphics.Color.TRANSPARENT

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    /** AI 多条待办写入前补要日历权限，授权成功后继续写。 */
    private val requestCalendarForAi = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val pending = pendingAiSpecs
        pendingAiSpecs = null
        if (pending != null) {
            if (granted.values.all { it }) {
                saveAiSpecs(pending)
            } else {
                toast("没有日历权限，写不进系统日历")
            }
        }
    }

    /** 拍照建待办：系统相机拍完，图存进 App 缓存，直接进识别页。 */
    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) {
            startActivity(ImportActivity.intentForScreenshot(this, uri, auto = true))
        }
    }

    /** 语音说一条：用系统语音识别把话变成文字，再走同一套本地解析。 */
    private val voiceLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (spoken.isNullOrEmpty()) {
            toast("没听清，再说一次")
            return@registerForActivityResult
        }
        etQuick.setText(spoken)
        etQuick.setSelection(spoken.length)
        submitQuick()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 光晕必须用代码生成：API 26 解析「主题色渐变」的 XML 会抛 Resources$NotFoundException，
        // 一开首页就崩（见 brandHaloDrawable 注释）。颜色仍取主题色。
        findViewById<View>(R.id.brandHalo).background =
            brandHaloDrawable(StatusColors.attr(this, R.attr.bnPrimary))
        DeviceVendor.log(this)
        maybePlaySplash()
        com.bellnote.util.PrivacyGate.ensure(this)

        emptyView = findViewById(R.id.emptyView)
        drawer = findViewById(R.id.drawer)
        etQuick = findViewById(R.id.etQuick)

        adapter = TodoAdapter(
            onClick = { item -> startActivity(EditActivity.intentForItem(this, item)) },
            onLongClick = { item -> confirmDelete(item) },
            collapsedGroups = Prefs.collapsedGroups(this),
            expandedGroups = Prefs.expandedGroups(this),
            onGroupStateChanged = { folded, unfolded ->
                Prefs.setCollapsedGroups(this, folded)
                Prefs.setExpandedGroups(this, unfolded)
            }
        )

        val recycler: RecyclerView = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        // 条目动效统一在 ListMotion（只调那一个常量，别在各处写死时长）
        com.bellnote.util.ListMotion.apply(recycler)

        setupQuickAdd()
        setupDrawer()

        findViewById<View>(R.id.btnShotIcon).setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        findViewById<TextView>(R.id.tvVersionTop).text = versionLabel()
        if (!Prefs.onboardingDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<View>(R.id.healthChip).setOnClickListener {
            startActivity(Intent(this, HealthActivity::class.java))
        }
        findViewById<View>(R.id.statusChip).setOnClickListener { showSyncDialog() }
        findViewById<View>(R.id.brandPill).setOnClickListener {
            // 只有 ntfy 可用时它才是入口；否则点了不动
            if (Prefs.ntfyEnabled(this) && Prefs.ntfyTopic(this).isNotBlank()) {
                startActivity(Intent(this, NtfyActivity::class.java))
            }
        }
        maybeAskEngine()
        maybeGuideNotificationBanner()
        // 每次启动查一次更新；刚更新完那次先弹更新日志。失败静默，不挡启动。
        UpdateCenter.onAppStart(this)
    }

    /**
     * 一次性引导：Android 里「通知横幅（弹窗）」是用户侧开关，App 自己改不了
     * （ColorOS 上还多一层 App 级的「重要程度 / 横幅通知」）。只能把用户送到系统通知页打开。
     */
    private fun maybeGuideNotificationBanner() {
        if (Prefs.notifBannerGuided(this)) return
        if (!Prefs.shotWatchEnabled(this)) return
        if (!Prefs.onboardingDone(this)) return
        if (!Permissions.hasPostNotifications(this)) return
        if (isFinishing || isDestroyed) return
        Prefs.setNotifBannerGuided(this, true)
        AlertDialog.Builder(this)
            .setTitle("让截图提醒弹出来")
            .setMessage(
                "系统里 Bellnote 的「横幅通知 / 重要程度」不是高的话，截完图只会静默进通知栏，不会弹窗。\n\n" +
                    "App 自己改不了这个开关，只能带你过去：进「通知」页把「横幅通知」打开（或把重要程度调高）。"
            )
            .setPositiveButton("去打开") { _, _ ->
                com.bellnote.util.NotificationSettings.openAppNotificationSettings(this)
            }
            .setNegativeButton("以后再说", null)
            .show()
    }

    /**
     * 首次问一次「截图识别用哪种方式」。
     * 只问一次：选了就记住，之后要改去设置页。
     */
    private fun maybeAskEngine() {
        if (Prefs.ocrEngineChosen(this)) return
        if (!Prefs.onboardingDone(this)) return
        val options = arrayOf(
            "自动：有网走云端，断网用本地（推荐）",
            "只用本地：绝不联网",
            "只用云端：要 Key 和网络"
        )
        AlertDialog.Builder(this)
            .setTitle("截图识别用哪种方式？")
            .setItems(options) { _, which ->
                Prefs.setOcrEngine(
                    this,
                    when (which) {
                        1 -> Prefs.ENGINE_LOCAL
                        2 -> Prefs.ENGINE_CLOUD
                        else -> Prefs.ENGINE_AUTO
                    }
                )
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 冷启动开屏：日常只放图标；换了版本（含首次安装）再播一次带字标的品牌动效。
     * 进程内只播一次，换主题重建页面不会再盖。
     */
    private fun maybePlaySplash() {
        if (!App.splashPending) return
        App.splashPending = false
        val version = versionLabel().removePrefix("v")
        val fancy = Prefs.splashVersion(this) != version
        Prefs.setSplashVersion(this, version)
        SplashOverlay.show(this, fancy) { }
    }

    /** 首页顶部「一句话记」：敲完回车 / 点「记」→ 本地解析 → 填好编辑页给你确认。 */
    private fun setupQuickAdd() {
        findViewById<View>(R.id.btnQuickGo).setOnClickListener { submitQuick() }
        btnQuickAi = findViewById(R.id.btnQuickAi)
        btnQuickAi.setOnClickListener { submitQuickWithAi() }
        etQuick.setOnLongClickListener {
            startVoiceInput()
            true
        }
        etQuick.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitQuick()
                true
            } else {
                false
            }
        }
    }

    /** 叫系统语音识别；没装识别服务的话给一句人话提示。 */
    private fun startVoiceInput() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "说一条待办，比如「明天下午三点交作业」")
        val canRecognize = packageManager.queryIntentActivities(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        ).isNotEmpty()
        if (!canRecognize) {
            // 很多没有 GMS 的机器（含这台 realme）没装语音识别服务：退回到键盘，
            // 让用户直接点输入法上的麦克风，别再弹一个「打不开」的框
            focusQuick("说一条，用键盘上的麦克风")
            toast("这台手机没有语音识别服务，已帮你打开输入框：点键盘上的麦克风说")
            return
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            focusQuick("说一条，用键盘上的麦克风")
            toast("语音识别打不开，改用键盘上的麦克风")
        }
    }

    private fun submitQuick() {
        if (aiRunning) return
        val text = etQuick.text.toString().trim()
        if (text.isEmpty()) {
            toast(getString(R.string.quick_empty))
            return
        }
        val now = System.currentTimeMillis()
        val parsed = QuickParse.parse(text, now)
        etQuick.setText("")
        if (parsed != null) {
            toast("已认出：${parsed.title}　${TimeFormat.full(parsed.startMillis)}")
            startActivity(
                EditActivity.intentForSpec(
                    this,
                    TodoSpec(parsed.title, parsed.startMillis, parsed.durationMinutes)
                )
            )
        } else {
            toast("没认出时间，自己点一下")
            startActivity(
                EditActivity.intentForSpec(
                    this,
                    TodoSpec(
                        title = text.take(40),
                        startMillis = alignedNextHour(),
                        durationMinutes = MANUAL_DEFAULT_DURATION_MINUTES
                    )
                )
            )
        }
    }

    /**
     * 「AI」按钮：把输入框里的一句话交给云端模型 / 本地模型，而不是只走本地规则。
     *
     * 顺序由 [TextEnginePlan] 决定：云端（开着 + 有 Key）→ 本地模型（开着 + 已下载）。
     * 两边都没准备好就弹设置提示；任意一步失败都退回 [QuickParse]，
     * 和原来点「记」的体验一致，不会让用户白写一句话。
     */
    private fun submitQuickWithAi() {
        if (aiRunning) return
        val text = etQuick.text.toString().trim()
        if (text.isEmpty()) {
            focusQuick(getString(R.string.quick_empty))
            return
        }

        val hasKey = Prefs.apiKey(this).isNotBlank()
        val llmReady = LocalLlm.isReady(this)
        val chain = TextEnginePlan.chain(
            cloudOn = Prefs.cloudModelEnabled(this),
            hasKey = hasKey,
            localOn = Prefs.localModelEnabled(this),
            llmReady = llmReady
        )
        if (chain.isEmpty()) {
            showAiNotReady(hasKey, llmReady)
            return
        }

        aiRunning = true
        btnQuickAi.isEnabled = false
        btnQuickAi.alpha = 0.5f
        etQuick.isEnabled = false
        showAiProgress()
        val now = System.currentTimeMillis()
        val online = isOnline()

        Thread {
            val notes = mutableListOf<String>()
            var outcome: VisionExtractor.Outcome? = null
            for (engine in chain) {
                when (engine) {
                    TextEnginePlan.Engine.CLOUD -> {
                        if (!online) {
                            notes += "当前没有网络"
                            continue
                        }
                        val result = TextExtractor.extract(
                            apiKey = Prefs.apiKey(this),
                            model = Prefs.model(this),
                            text = text,
                            now = now,
                            baseUrl = Prefs.cloudBaseUrl(this)
                        )
                        if (result.specs.isNotEmpty()) {
                            outcome = result
                            break
                        }
                        notes += "云端：${result.error ?: "没有结果"}"
                    }

                    TextEnginePlan.Engine.LLM -> {
                        val result = LocalLlm.extract(this, text, now)
                        if (result.specs.isNotEmpty()) {
                            outcome = result
                            break
                        }
                        notes += "本地模型：${result.error ?: "没有结果"}"
                    }
                }
            }

            val specs = outcome?.specs.orEmpty()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                aiRunning = false
                btnQuickAi.isEnabled = true
                btnQuickAi.alpha = 1f
                etQuick.isEnabled = true
                dismissAiProgress()
                if (specs.isEmpty()) {
                    fallbackToLocalRule(text, notes)
                    return@runOnUiThread
                }
                if (specs.size == 1) {
                    etQuick.setText("")
                    val spec = specs.first()
                    val hint = if (notes.isEmpty()) "" else "（${notes.first().take(20)}）"
                    toast("AI 已识别$hint：${spec.title}　${TimeFormat.full(spec.startMillis)}")
                    startActivity(EditActivity.intentForSpec(this, spec))
                } else {
                    confirmAiBatch(specs)
                }
            }
        }.start()
    }

    /** 两个 AI 入口都没配置好时，说清缺什么，并把「先用本地规则」留成一条退路。 */
    private fun showAiNotReady(hasKey: Boolean, llmReady: Boolean) {
        val extra = buildString {
            if (Prefs.cloudModelEnabled(this@MainActivity) && !hasKey) {
                append("\n\n云端模型开着，但还没填 API Key。")
            }
            if (Prefs.localModelEnabled(this@MainActivity) && !llmReady) {
                append("\n\n本地模型开着，但模型文件还没下载完。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_ai_not_ready_title)
            .setMessage(getString(R.string.quick_ai_not_ready_message) + extra)
            .setPositiveButton(R.string.quick_ai_go_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNeutralButton(R.string.quick_ai_use_local) { _, _ -> submitQuick() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** AI 请求可能等十几秒，弹一个不可取消的小转圈；页面被回收时在 onDestroy 收掉。 */
    private fun showAiProgress() {
        dismissAiProgress()
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        row.addView(ProgressBar(this))
        row.addView(
            TextView(this).apply {
                text = getString(R.string.quick_ai_progress)
                setTextColor(StatusColors.attr(this@MainActivity, R.attr.bnTextPrimary))
                setPadding(pad, 0, 0, 0)
            }
        )
        aiDialog = AlertDialog.Builder(this)
            .setView(row)
            .setCancelable(false)
            .create()
        aiDialog?.show()
    }

    private fun dismissAiProgress() {
        try {
            aiDialog?.dismiss()
        } catch (_: Exception) {
            // 页面已经销毁时 dialog 可能已经不在窗口上
        }
        aiDialog = null
    }

    /** AI 两级都失败：退回原来的本地规则；再不行就按老办法填好标题和时间。 */
    private fun fallbackToLocalRule(text: String, notes: List<String>) {
        val parsed = QuickParse.parse(text, System.currentTimeMillis())
        etQuick.setText("")
        val prefix = if (notes.isEmpty()) "AI 没认出" else "AI 没成功"
        if (parsed != null) {
            toast("$prefix，已用本地规则填好")
            startActivity(
                EditActivity.intentForSpec(
                    this,
                    TodoSpec(parsed.title, parsed.startMillis, parsed.durationMinutes)
                )
            )
        } else {
            toast("$prefix，自己点一下时间")
            startActivity(
                EditActivity.intentForSpec(
                    this,
                    TodoSpec(
                        title = text.take(40),
                        startMillis = alignedNextHour(),
                        durationMinutes = MANUAL_DEFAULT_DURATION_MINUTES
                    )
                )
            )
        }
    }

    /** 多条 AI 结果先核对日历里是否已有，再弹勾选。 */
    private fun confirmAiBatch(specs: List<TodoSpec>) {
        Thread {
            val repo = CalendarRepository(this)
            val duplicate = BooleanArray(specs.size)
            for (i in specs.indices) {
                duplicate[i] = try {
                    repo.findOwnEvents(specs[i].title, specs[i].startMillis).isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showAiBatchDialog(specs, duplicate)
            }
        }.start()
    }

    private fun showAiBatchDialog(specs: List<TodoSpec>, duplicate: BooleanArray) {
        if (duplicate.all { it }) {
            toast("这几条日历里都有了")
            return
        }
        val labels = specs.mapIndexed { index, spec ->
            val suffix = if (duplicate[index]) "（已在日历里）" else ""
            "${spec.title}｜${TimeFormat.monthDay(spec.startMillis)} · ${spec.durationMinutes} 分钟$suffix"
        }.toTypedArray()
        val checked = BooleanArray(specs.size) { !duplicate[it] }
        AlertDialog.Builder(this)
            .setTitle("AI 识别到 ${specs.size} 条待办")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("写入选中") { _, _ ->
                val picked = specs.filterIndexed { index, _ -> checked[index] }
                if (picked.isEmpty()) {
                    toast("一条都没选，没有写入")
                } else {
                    etQuick.setText("")
                    saveAiSpecs(picked)
                }
            }
            .setNeutralButton("只看第一条") { _, _ ->
                etQuick.setText("")
                startActivity(EditActivity.intentForSpec(this, specs.first()))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 多条 AI 结果直接写日历；先补权限，再写账本，来源记成 ai。 */
    private fun saveAiSpecs(specs: List<TodoSpec>) {
        if (specs.isEmpty()) return
        if (!Permissions.hasCalendar(this)) {
            pendingAiSpecs = specs
            requestCalendarForAi.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
            return
        }
        Thread {
            val repo = CalendarRepository(this)
            val store = TodoStore(this)
            val calendarId = repo.defaultCalendarId()
            if (calendarId <= 0) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        toast("没有可写入的日历，先去设置里选一个")
                    }
                }
                return@Thread
            }
            var done = 0
            for (spec in specs) {
                val eventId = repo.insertEvent(spec, calendarId)
                if (eventId > 0) {
                    store.record(spec, eventId, SOURCE_AI)
                    done++
                }
            }
            val saved = done
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast("已写入 $saved / ${specs.size} 条")
                refresh()
            }
        }.start()
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    private fun alignedNextHour(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.HOUR_OF_DAY, 1)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun setupDrawer() {
        val menu = findViewById<View>(R.id.btnMenu)
        // 按下就开，别等抬手
        menu.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) openDrawerFast()
            false // 返回 false，点击/涟漪/无障碍仍照旧
        }
        menu.setOnClickListener { openDrawerFast() }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                val pending = pendingDrawerAction
                pendingDrawerAction = null
                pending?.invoke()
            }

            // 手指自己拖的时候别和我们的动画抢
            override fun onDrawerStateChanged(newState: Int) {
                if (newState == androidx.customview.widget.ViewDragHelper.STATE_DRAGGING) {
                    cancelDrawerAnim()
                }
            }
        })
        findViewById<View>(R.id.navAddManual).setOnClickListener {
            afterDrawerClosed { startActivity(Intent(this, EditActivity::class.java)) }
        }
        findViewById<View>(R.id.navQuick).setOnClickListener {
            afterDrawerClosed { focusQuick(getString(R.string.quick_hint)) }
        }
        findViewById<View>(R.id.navVoice).setOnClickListener {
            afterDrawerClosed { startVoiceInput() }
        }
        findViewById<View>(R.id.navCamera).setOnClickListener {
            afterDrawerClosed { takePhoto() }
        }
        findViewById<View>(R.id.navAllEvents).setOnClickListener {
            afterDrawerClosed { startActivity(Intent(this, AllEventsActivity::class.java)) }
        }
        findViewById<View>(R.id.navReconcile).setOnClickListener {
            afterDrawerClosed { startActivity(Intent(this, ReconcileActivity::class.java)) }
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            afterDrawerClosed { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        findViewById<View>(R.id.navHelp).setOnClickListener {
            afterDrawerClosed { showHelp() }
        }
    }

    // ── 抽屉开关：自己控时长 ────────────────────────────────────────────────
    //
    // DrawerLayout 的滑入时长是 ViewDragHelper 按「剩余距离/屏幕宽度」算的，
    // 点按钮时速度是 0，算出来约 (286/411+1)×256ms ≈ 430ms，而且没有公开接口能改。
    // 所以这里只借它的「开/关状态」（手势、返回键、无障碍都靠这个状态），
    // 位移我们自己用 150ms 补完，手指按下那一瞬间抽屉就已经露出一截。

    private var drawerAnimating = false
    private var drawerScrimAnimator: android.animation.ValueAnimator? = null

    private fun openDrawerFast() {
        val panel = findViewById<View>(R.id.drawerPanel) ?: return
        if (drawerAnimating && drawer.isDrawerOpen(GravityCompat.START)) {
            // 正在开/正要关：直接把位移补完，别让它看起来没反应
            finishDrawerAnimation(open = true)
            return
        }
        if (drawer.isDrawerOpen(GravityCompat.START) && panel.translationX == 0f) return

        if (!drawer.isDrawerOpen(GravityCompat.START)) {
            // 立刻进入「开」状态（手势/返回键/无障碍都认这个），但不播系统那段约 430ms 的位移
            drawer.openDrawer(GravityCompat.START, false)
        }
        val width = drawerPanelWidth(panel)
        panel.animate().cancel()
        drawerScrimAnimator?.cancel()
        // 遮罩从透明开始淡入，否则会被系统那一帧的满遮罩闪一下
        drawerScrimCurrent = android.graphics.Color.TRANSPARENT
        drawer.setScrimColor(drawerScrimCurrent)
        // 先退回到只露出一截的位置，再滑到位：按下就有反应，不用等整段位移
        panel.translationX = -width * OPEN_START_OFFSET
        panel.animate()
            .translationX(0f)
            .setDuration(OPEN_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction {
                panel.translationX = 0f
                drawer.setScrimColor(SCRIM_FULL)
                drawerAnimating = false
            }
            .start()
        drawerAnimating = true
        animateScrim(SCRIM_FULL, OPEN_MS)
    }

    /** 收起抽屉（带自己的动画），收完 DrawerLayout 才真正切到关闭状态并回调 onDrawerClosed。 */
    private fun closeDrawerFast() {
        val panel = findViewById<View>(R.id.drawerPanel) ?: return
        if (!drawer.isDrawerOpen(GravityCompat.START)) return
        val width = drawerPanelWidth(panel)
        panel.animate().cancel()
        drawerScrimAnimator?.cancel()
        drawerAnimating = true
        panel.animate()
            .translationX(-width)
            .setDuration(CLOSE_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction {
                panel.translationX = 0f
                drawer.setScrimColor(SCRIM_FULL)
                drawerAnimating = false
                drawer.closeDrawer(GravityCompat.START, false) // 这会触发 onDrawerClosed
            }
            .start()
        animateScrim(android.graphics.Color.TRANSPARENT, CLOSE_MS)
        // 兜底：万一动画的收尾没跑（页面被回收之类），待执行的动作也不能丢
        panel.postDelayed({
            val pending = pendingDrawerAction
            if (pending != null) {
                pendingDrawerAction = null
                finishDrawerAnimation(open = false)
                pending.invoke()
            }
        }, CLOSE_MS + 150L)
    }

    /** 手指开始拖抽屉时，把我们的动画收掉，避免和手势打架。 */
    private fun cancelDrawerAnim() {
        if (!drawerAnimating) return
        val panel = findViewById<View>(R.id.drawerPanel) ?: return
        panel.animate().cancel()
        panel.translationX = 0f
        drawerScrimAnimator?.cancel()
        drawer.setScrimColor(SCRIM_FULL)
        drawerAnimating = false
    }

    private fun finishDrawerAnimation(open: Boolean) {
        val panel = findViewById<View>(R.id.drawerPanel) ?: return
        panel.animate().cancel()
        panel.translationX = 0f
        drawerScrimAnimator?.cancel()
        drawer.setScrimColor(SCRIM_FULL)
        drawerAnimating = false
        if (!open && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START, false)
        }
    }

    private fun drawerPanelWidth(panel: View): Float =
        if (panel.width > 0) panel.width.toFloat() else 286f * resources.displayMetrics.density

    private fun animateScrim(to: Int, duration: Long) {
        val from = drawerScrimCurrent
        drawerScrimAnimator = android.animation.ValueAnimator.ofArgb(from, to).apply {
            this.duration = duration
            addUpdateListener {
                val color = it.animatedValue as Int
                drawerScrimCurrent = color
                drawer.setScrimColor(color)
            }
            start()
        }
    }

    /**
     * 等抽屉收好再执行动作。抽屉本来就关着就直接执行，
     * 这样程序化调用（通知磁贴、分享进来）不受影响。
     */
    private fun afterDrawerClosed(action: () -> Unit) {
        if (!drawer.isDrawerOpen(GravityCompat.START)) {
            action()
            return
        }
        pendingDrawerAction = action
        closeDrawerFast()
    }

    /** 说明类的东西都收在这里，抽屉里只留 8 个真入口。 */
    private fun showHelp() {
        val items = arrayOf(
            getString(R.string.nav_sidebar),
            getString(R.string.nav_tile),
            getString(R.string.nav_permission)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.nav_help))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSidebarGuide()
                    1 -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.nav_tile))
                        .setMessage(getString(R.string.tile_guide))
                        .setPositiveButton("知道了", null)
                        .show()

                    else -> startActivity(Intent(this, HealthActivity::class.java))
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun focusQuick(hint: String? = null) {
        hint?.let { etQuick.setHint(it) }
        etQuick.requestFocus()
        etQuick.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(etQuick, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** 拍照建待办：走系统相机，图存到 App 缓存再用 FileProvider 交给识别页。 */
    private fun takePhoto() {
        try {
            val dir = File(cacheDir, "photos").apply { mkdirs() }
            val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingCameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("打不开相机：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 系统侧边栏没有公开接口，只能把路径写清楚。 */
    private fun showSidebarGuide() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sidebar_howto_title))
            .setMessage(
                "最快：设置 → 其他入口 → 点「加入侧边栏」，先把「Bellnote 截屏记」显示出来（桌面会多一个图标），" +
                    "再按下面的路径把它加进侧边栏。\n\n" +
                    "手动找入口：\n" + DeviceVendor.sidebarGuide() + "\n\n" +
                    "加好后：在任何 App 页面划出侧边栏 → 点「Bellnote 截屏记」→ 自动截屏并识别。\n" +
                    "前提是开过「截屏服务」（无障碍），没开的话会提示你去开。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun versionLabel(): String = try {
        "v" + packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        ""
    }

    /** 标题右侧那颗灯：只读缓存结果，过期了才后台重算。 */
    private fun renderHealth() {
        val items = HealthInspector.cached(this)
        val chip = findViewById<TextView>(R.id.healthChip)
        val worst = HealthCheck.indicator(items)
        if (worst == null) {
            styleChip(chip, R.drawable.dot_warn, "检测中")
            return
        }
        val dot = when (worst.status) {
            HealthCheck.Status.BAD -> R.drawable.dot_bad
            HealthCheck.Status.WARN -> R.drawable.dot_warn
            HealthCheck.Status.UNKNOWN -> R.drawable.dot_warn
            HealthCheck.Status.OK -> R.drawable.dot_ok
        }
        styleChip(
            chip,
            dot,
            // 和右边那颗「同步正常」对齐：这里是整体状态，别只写「正常」两个字
            if (worst.status == HealthCheck.Status.OK) "状态正常"
            else "${HealthCheck.pending(items).size} 项可优化"
        )
    }

    /** 两颗灯同一个样子：圆点 + 文字 + 右侧箭头，点开都是「运行体检」。 */
    private fun styleChip(chip: TextView, dot: Int, label: String) {
        val changed = chip.text.toString() != label
        chip.text = label
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, R.drawable.ic_chevron_right, 0)
        if (changed) {
            // 状态变了就淡入一下，别硬切
            chip.alpha = 0.35f
            chip.animate().alpha(1f).setDuration(200L).start()
        }
    }

    private fun refreshHealthIfStale() {
        renderHealth()
        val gap = System.currentTimeMillis() - HealthInspector.cachedAt(this)
        if (gap in 0 until 5 * 60_000L) return
        HealthInspector.refreshInBackground(this) {
            if (!isFinishing) renderHealth()
        }
    }

    /** 子页面用 CLEAR_TOP 回到这里时会走 onNewIntent，顺手刷新一下列表。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refresh()
        if (intent.getBooleanExtra(EXTRA_FOCUS_QUICK, false)) {
            focusQuick(getString(R.string.quick_hint))
        }
    }

    override fun onPause() {
        super.onPause()
        // 微光和光标只在页面上跑，切走就停，别在后台烧电
        stopBrandPulse()
        ticker.removeCallbacks(tick)
    }

    override fun onDestroy() {
        dismissAiProgress()
        super.onDestroy()
    }

    /**
     * 列表跟着数据走：后台服务写完库会发 [TodoEvents]，这里收到就重查一遍。
     * 只在页面可见时注册，退到后台不占内存、也不白刷。
     */
    private val onTodosChanged: (String) -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) refresh()
        }
    }

    override fun onStart() {
        super.onStart()
        TodoEvents.addListener(onTodosChanged)
    }

    override fun onStop() {
        TodoEvents.removeListener(onTodosChanged)
        super.onStop()
    }

    override fun onResume() {
        // 从隐私政策页返回还没同意的话，继续挡着
        com.bellnote.util.PrivacyGate.ensure(this)

        super.onResume()
        // 回到前台先对齐一次时间，之后每分钟刷新进度条
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, 60_000L)
        refreshHealthIfStale()
        if (ThemeManager.refreshIfNeeded(this)) {
            recreate()
            return
        }
        // 相册权限给过了就顺手挂上截图监听（没权限时内部直接返回）
        ScreenshotWatcher.ensureStarted(this)
        // 进程被冻结时 observer 收不到回调：每次回前台都补查一次（4 小时内、最近 30 张图）
        ScreenshotWatcher.checkLatest(this)
        // 设置里可能改了「已结束的待办」的样子：数据没变 Diff 不会重绑，这里手动刷一遍
        adapter.refreshStyles()
        if (intent?.getBooleanExtra(EXTRA_FOCUS_QUICK, false) == true) {
            intent?.removeExtra(EXTRA_FOCUS_QUICK)
            focusQuick(getString(R.string.quick_hint))
        }
        refresh()
        runReconcileIfDue()
        runCatchUpIfDue()
    }

    /**
     * 打开 App 是「解锁」最可靠的信号：realme 上闹钟都可能被推迟，
     * 这里顺手补查一次 ntfy。
     *
     * 和「服务在轮询」区分开：
     * ① 常驻前台服务活着（[NtfyReceiveService.isAlive]）时它每分钟已经查过，这里不再补，
     *    避免两套节流互相顶掉；
     * ② 服务没在跑才用 3 分钟节流兜一次；
     * ③ 写完库由 [TodoEvents] 通知界面刷新，这里不再手动 refresh。
     */
    private fun runCatchUpIfDue() {
        if (!Prefs.ntfyEnabled(this)) return
        if (Prefs.ntfyTopic(this).isBlank()) return
        if (NtfyReceiveService.isAlive) return
        if (System.currentTimeMillis() - Prefs.lastCatchUpAt(this) < CATCH_UP_MIN_GAP_MS) return
        Thread {
            try {
                NtfyFetcher.catchUp(this)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "补查 ntfy 失败", e)
            }
        }.start()
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
            adapter.submit(emptyList())
            emptyView.text = "需要日历权限才能读取待办，点上方卡片去授权"
            showEmptyView(true)
            return
        }
        Thread {
            val items = repository.queryOwnEvents()
            runOnUiThread {
                adapter.submit(items)
                emptyView.text = getString(R.string.main_empty)
                showEmptyView(items.isEmpty())
            }
        }.start()
    }

    /** 空列表的提示淡入淡出，别在一堆卡片中间硬切出来。 */
    private fun showEmptyView(show: Boolean) {
        emptyView.animate().cancel()
        if (show) {
            if (emptyView.visibility == View.VISIBLE && emptyView.alpha > 0.9f) return
            emptyView.visibility = View.VISIBLE
            emptyView.alpha = 0f
            emptyView.animate().alpha(1f).setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            if (emptyView.visibility != View.VISIBLE) return
            emptyView.animate().alpha(0f).setDuration(140L).withEndAction {
                emptyView.visibility = View.GONE
                emptyView.alpha = 1f
            }.start()
        }
    }

    /** 右边那颗灯：同步状态（对账 / 补查 / 写失败），和左边「体检」不是一回事。 */
    private fun updateStatus() {
        val chip = findViewById<TextView>(R.id.statusChip)
        val dot = when (StatusLines.level(this)) {
            StatusLines.Level.OK -> R.drawable.dot_ok
            StatusLines.Level.IDLE -> R.drawable.dot_idle
            StatusLines.Level.WARN -> R.drawable.dot_warn
        }
        styleChip(chip, dot, StatusLines.chipLabel(this))
        renderNtfyQuick()
    }

    /**
     * 首页右上角的 ntfy 入口：模式开着且主题可用时，给「Bellnote + 版本号」套一颗胶囊，
     * 右下角一个 ntfy 的「>_」提示符，颜色跟同步灯一套；点胶囊进 ntfy 模式页。
     */
    private fun renderNtfyQuick() {
        val pill = findViewById<View>(R.id.brandPill)
        val glyph = findViewById<android.widget.ImageView>(R.id.brandGlyph)
        val available = Prefs.ntfyEnabled(this) && Prefs.ntfyTopic(this).isNotBlank()
        val density = resources.displayMetrics.density
        val padV = (5 * density).toInt()

        if (available) {
            // 不加卡片：只有「Bellnote」这行字带一层会呼吸的主色光晕
            pill.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            pill.setPadding((10 * density).toInt(), (4 * density).toInt(), (18 * density).toInt(), (4 * density).toInt())
            glyph.visibility = View.VISIBLE
            val level = StatusLines.level(this)
            // 徽章不跟主题也不跟状态，用最稳的前景色；状态交给状态灯
            glyph.imageTintList = android.content.res.ColorStateList.valueOf(
                StatusColors.attr(this, R.attr.bnTextPrimary)
            )
            pill.contentDescription = "Bellnote，ntfy 通道"
            val halo = findViewById<View>(R.id.brandHalo)
            halo.visibility = View.VISIBLE
            applyBrandGlow(level)
        } else {
            pill.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            pill.setPadding(0, padV, 0, padV)
            glyph.visibility = View.GONE
            pill.contentDescription = "Bellnote"
            findViewById<View>(R.id.brandHalo).visibility = View.GONE
            stopBrandPulse()
        }
    }

    /**
     * 方案 A+D：微光平时用主题色；只有出问题（WARN）时切琥珀色闪 2 秒，再回主题色。
     * 同一次异常只闪一次，避免每分钟刷新都闪。
     */
    private fun applyBrandGlow(level: StatusLines.Level) {
        if (level != lastGlowLevel) {
            lastGlowLevel = level
            warnFlashDone = false
        }
        if (level == StatusLines.Level.WARN && !warnFlashDone) {
            warnFlashDone = true
            warnFlashUntil = System.currentTimeMillis() + 3000L
            startBrandPulse(StatusColors.attr(this, R.attr.bnWarn))
            glowHandler.removeCallbacks(restorePrimaryGlow)
            glowHandler.postDelayed(restorePrimaryGlow, 3000L)
            return
        }
        // 闪的这 3 秒里，别的刷新别把琥珀色盖回主题色
        if (System.currentTimeMillis() < warnFlashUntil) return
        startBrandPulse(StatusColors.attr(this, R.attr.bnPrimary))
    }

    private val restorePrimaryGlow = Runnable {
        startBrandPulse(StatusColors.attr(this, R.attr.bnPrimary))
    }

    /**
     * 首页品牌光晕：96dp×54dp 的椭圆径向渐变，半径 27dp。
     *
     * 以前放在 res/drawable/bg_brand_halo.xml 里（startColor="?attr/bnPrimary"）。Android 8.0/API 26
     * 解析这种「主题色渐变」有个系统 bug，直接抛 Resources$NotFoundException：
     * `<gradient> tag requires 'gradientRadius' attribute with radial type`，导致首页一开就崩。
     * 现在改成代码生成，颜色仍取主题色，形状/半径/透明度与原来完全一致（另见 2026-11-20 测试台）。
     */
    private fun brandHaloDrawable(color: Int): android.graphics.drawable.GradientDrawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
        drawable.gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
        drawable.gradientRadius = 27f * resources.displayMetrics.density
        drawable.colors = intArrayOf(color, android.graphics.Color.TRANSPARENT)
        return drawable
    }

    /** 只有背景光晕在动：整团椭圆光晕的透明度/横向尺度 + 文字描边光一起呼吸。>_ 保持静止。 */
    private fun startBrandPulse(color: Int) {
        if (brandGlowAnimator?.isRunning == true && color == glowColorCurrent) return
        brandGlowAnimator?.cancel()
        val name = findViewById<TextView>(R.id.tvBrandName)
        val halo = findViewById<View>(R.id.brandHalo)
        val density = resources.displayMetrics.density
        val night = StatusColors.isNight(this)
        glowColorCurrent = color
        val glowColor = StatusColors.glow(this, color)
        halo.background?.setTint(glowColor)

        brandGlowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                // 字周围一层紧的光：深色掺白发光，浅色掺黑做投影感
                val radius = ((if (night) 5f else 4f) + (if (night) 6f else 5f) * f) * density
                val alpha = ((if (night) 90 else 60) + (if (night) 100 else 70) * f).toInt()
                name.setShadowLayer(
                    radius, 0f, 0f,
                    androidx.core.graphics.ColorUtils.setAlphaComponent(glowColor, alpha)
                )
                // 背后一团椭圆光晕，横向慢慢铺开
                halo.alpha = if (night) 0.14f + 0.20f * f else 0.08f + 0.14f * f
                halo.scaleX = 1.20f + 0.22f * f
                halo.scaleY = 0.94f + 0.08f * f
            }
            start()
        }
    }

    private fun stopBrandPulse() {
        brandGlowAnimator?.cancel()
        brandGlowAnimator = null
        glowHandler.removeCallbacks(restorePrimaryGlow)
        glowColorCurrent = 0
        lastGlowLevel = null
        findViewById<TextView>(R.id.tvBrandName).setShadowLayer(0f, 0f, 0f, 0)
        findViewById<View>(R.id.brandHalo).alpha = 0f
    }

    /** 点「同步」灯：把对账 / 补查 / 写失败三行摊开，能直接去对账。 */
    private fun showSyncDialog() {
        val lines = StatusLines.recent(this).joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("同步状态")
            .setMessage(lines)
            .setPositiveButton("知道了", null)
            .setNeutralButton("去对账") { _, _ ->
                startActivity(Intent(this, ReconcileActivity::class.java))
            }
            .show()
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
                        toast(if (ok) "已删除" else "删除失败")
                        refresh()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            closeDrawerFast()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_FOCUS_QUICK = "focus_quick"

        /** 抽屉开合：自己定 150/130ms（系统那套约 430ms）。 */
        private const val OPEN_MS = 150L
        private const val CLOSE_MS = 130L
        /** 按下瞬间先退掉这么多，让抽屉一露头就有反应。 */
        private const val OPEN_START_OFFSET = 0.34f
        /** 和 DrawerLayout 默认遮罩同一个值。 */
        private val SCRIM_FULL = 0x99000000.toInt()
        /** 打开 App 补查 ntfy 的最小间隔：服务没在跑时才用，别和服务的每分钟轮询互相顶。 */
        private const val CATCH_UP_MIN_GAP_MS = 3 * 60_000L
    }
}


