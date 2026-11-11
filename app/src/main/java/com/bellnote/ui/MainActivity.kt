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
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.MANUAL_DEFAULT_DURATION_MINUTES
import com.bellnote.data.Prefs
import com.bellnote.data.TodoItem
import com.bellnote.data.TodoSpec
import com.bellnote.data.TodoStore
import com.bellnote.parser.QuickParse
import com.bellnote.service.NtfyFetcher
import com.bellnote.service.ReconcileRunner
import com.bellnote.service.ScreenshotWatcher
import com.bellnote.util.DeviceVendor
import com.bellnote.util.Permissions
import com.bellnote.util.TimeFormat
import java.io.File

class MainActivity : AppCompatActivity() {

    private val repository by lazy { CalendarRepository(this) }
    private lateinit var adapter: TodoAdapter
    private lateinit var emptyView: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var etQuick: EditText
    private var pendingCameraUri: Uri? = null

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) {
            startActivity(ImportActivity.intentForScreenshot(this, uri, auto = true))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DeviceVendor.log(this)

        emptyView = findViewById(R.id.emptyView)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        drawer = findViewById(R.id.drawer)
        etQuick = findViewById(R.id.etQuick)

        adapter = TodoAdapter(
            onClick = { item -> startActivity(EditActivity.intentForItem(this, item)) },
            onLongClick = { item -> confirmDelete(item) }
        )

        val recycler: RecyclerView = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        setupQuickAdd()
        setupDrawer()

        findViewById<View>(R.id.btnShotBig).setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        findViewById<View>(R.id.statusCard).setOnClickListener {
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }
        findViewById<TextView>(R.id.tvVendorHint).text =
            "${DeviceVendor.current().label} · Android ${Build.VERSION.RELEASE}"
    }

    /** 首页顶部「一句话记」：敲完回车 / 点「记」→ 本地解析 → 填好编辑页给你确认。 */
    private fun setupQuickAdd() {
        findViewById<View>(R.id.btnQuickGo).setOnClickListener { submitQuick() }
        etQuick.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitQuick()
                true
            } else {
                false
            }
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
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.navAddManual).setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, EditActivity::class.java))
        }
        findViewById<View>(R.id.navQuick).setOnClickListener {
            closeDrawer()
            focusQuick(getString(R.string.quick_hint))
        }
        findViewById<View>(R.id.navCamera).setOnClickListener {
            closeDrawer()
            takePhoto()
        }
        findViewById<View>(R.id.navAllEvents).setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, AllEventsActivity::class.java))
        }
        findViewById<View>(R.id.navReconcile).setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, ReconcileActivity::class.java))
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.navPermission).setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }
        findViewById<View>(R.id.navSidebar).setOnClickListener {
            closeDrawer()
            showSidebarGuide()
        }
        findViewById<View>(R.id.navTile).setOnClickListener {
            closeDrawer()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.nav_tile))
                .setMessage(getString(R.string.tile_guide))
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun closeDrawer() {
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START)
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

    /** 子页面用 CLEAR_TOP 回到这里时会走 onNewIntent，顺手刷新一下列表。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refresh()
        if (intent.getBooleanExtra(EXTRA_FOCUS_QUICK, false)) {
            focusQuick(getString(R.string.quick_hint))
        }
    }

    override fun onResume() {
        super.onResume()
        // 相册权限给过了就顺手挂上截图监听（没权限时内部直接返回）
        ScreenshotWatcher.ensureStarted(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Permissions.hasPostNotifications(this) && !askedNotifications
        ) {
            askedNotifications = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        if (!Permissions.hasNotificationAccess(this)) {
            problems.add("· 通知使用权没开，收不到 ntfy 通知")
        } else {
            // 授权开着但很久没连上：多半是被系统回收了，提示关一下再打开
            val connectedAt = Prefs.listenerConnectedAt(this)
            val stale = connectedAt > 0 && System.currentTimeMillis() - connectedAt > 24 * 60 * 60 * 1000L
            if (stale) {
                problems.add("· 通知监听可能被系统回收了（最近连接：${TimeFormat.full(connectedAt)}）")
            }
        }
        if (Prefs.calendarId(this) <= 0) problems.add("· 还没选目标日历")

        if (problems.isEmpty()) {
            findViewById<View>(R.id.statusDot).setBackgroundResource(R.drawable.dot_ok)
            statusTitle.text = getString(R.string.main_status_ok)
            val lastAt = Prefs.lastReconcileAt(this)
            val summary = Prefs.lastReconcileSummary(this)
            val reconcileLine = if (lastAt > 0) {
                "对账：$summary（${TimeFormat.full(lastAt)}）"
            } else {
                "还没跑过对账，点下方「对账」看清单和日历差在哪"
            }
            val catchUpLine = if (Prefs.ntfyTopic(this).isBlank()) {
                "补查：没开（设置里填 ntfy 主题后，ntfy 没弹通知也能补回来）"
            } else {
                val catchAt = Prefs.lastCatchUpAt(this)
                val catchSummary = Prefs.lastCatchUpSummary(this)
                when {
                    catchAt <= 0 -> "补查：开了，还没跑过"
                    // summary 本身已经带「补查：」前缀，别再叠一层
                    catchSummary.startsWith("补查") -> catchSummary
                    else -> "补查：$catchSummary"
                }
            }
            val ignored = Prefs.ignoredNotices(this).size
            val ignoredLine = if (ignored > 0) {
                "有 $ignored 条推送没写进日历（设置页可看原因）"
            } else {
                "最近没有写失败的推送"
            }
            statusDetail.text = "$reconcileLine\n$catchUpLine\n$ignoredLine"
        } else {
            findViewById<View>(R.id.statusDot).setBackgroundResource(R.drawable.dot_warn)
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
            drawer.closeDrawer(GravityCompat.START)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_FOCUS_QUICK = "focus_quick"
        private var askedNotifications = false
    }
}
