package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import com.bellnote.util.ThemeManager
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.MANUAL_DEFAULT_DURATION_MINUTES
import com.bellnote.data.HealthCheck
import com.bellnote.data.Prefs
import com.bellnote.data.StatusLines
import com.bellnote.data.TodoItem
import com.bellnote.data.TodoSpec
import com.bellnote.data.TodoStore
import com.bellnote.parser.QuickParse
import com.bellnote.service.HealthInspector
import com.bellnote.service.NtfyFetcher
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
    private var pendingCameraUri: Uri? = null
    /** 抽屉还在滑回时要执行的动作（见 afterDrawerClosed）。 */
    private var pendingDrawerAction: (() -> Unit)? = null
    /** 抽屉位移/遮罩动画的当前值，自己控时长时用。 */
    private var drawerScrimCurrent: Int = android.graphics.Color.TRANSPARENT

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

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
        DeviceVendor.log(this)
        maybePlaySplash()
        com.bellnote.util.PrivacyGate.ensure(this)

        emptyView = findViewById(R.id.emptyView)
        drawer = findViewById(R.id.drawer)
        etQuick = findViewById(R.id.etQuick)

        adapter = TodoAdapter(
            onClick = { item, source -> openEditWithSharedTitle(item, source) },
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

    /**
     * 点卡片进编辑页：卡片上的标题当共享元素，直接长成编辑页的标题。
     * 转场名必须两边一致，所以把名字放进 intent，由编辑页给它的标题设同一个名字。
     */
    private fun openEditWithSharedTitle(item: TodoItem, source: View) {
        val name = "todoTitle-${item.id}"
        source.transitionName = name
        val intent = EditActivity.intentForItem(this, item)
            .putExtra(EditActivity.EXTRA_SHARED_NAME, name)
        val options = androidx.core.app.ActivityOptionsCompat
            .makeSceneTransitionAnimation(this, source, name)
        try {
            startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            // 转场起不来也不能把人挡在外面
            android.util.Log.w("MainActivity", "共享元素转场启动失败，退回普通跳转", e)
            startActivity(intent)
        }
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
                DeviceVendor.sidebarGuide() + "\n\n" +
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
            if (worst.status == HealthCheck.Status.OK) "正常" else "${HealthCheck.pending(items).size} 项可优化"
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
     * 这里顺手补查一次 ntfy（3 分钟内刚查过就跳过）。
     */
    private fun runCatchUpIfDue() {
        if (!Prefs.ntfyEnabled(this)) return
        if (Prefs.ntfyTopic(this).isBlank()) return
        if (System.currentTimeMillis() - Prefs.lastCatchUpAt(this) < 3 * 60_000L) return
        Thread {
            try {
                NtfyFetcher.catchUp(this)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "补查 ntfy 失败", e)
            }
            runOnUiThread { refresh() }
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
    }
}


