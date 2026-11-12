package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.service.HealthInspector
import com.bellnote.util.DeviceVendor
import com.bellnote.util.Permissions
import com.bellnote.util.SystemPages
import com.bellnote.util.ThemeManager
import kotlin.math.abs

/**
 * 首次安装的引导，五页：
 * 1) 记法  2) 日历  3) 截图识别（可选）  4) 开关  5) 外观
 *
 * 可以左右滑动，也可以点「下一步」；换配色会重建页面，用 savedInstanceState 记住停在第几页。
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = 0
    private lateinit var deck: SwipeDeck
    private lateinit var steps: List<View>
    private lateinit var dots: List<View>
    private lateinit var btnNext: Button
    private lateinit var tvPermHint: TextView
    private lateinit var intro2: TextView
    private lateinit var body2: android.view.View
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    // 统一动效：进场 360ms 标准曲线，退场 280ms，元素之间错开 70ms
    private val easeStd = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    private val easeOutSoft = android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f)
    private val enterMs = 360L
    private val exitMs = 280L
    private val staggerMs = 70L

    private companion object {
        const val STAGE2_INTRO = "如何实现"
        const val STAGE2_TITLE = "先授权，再开始"
    }
    private var pendingStage: Runnable? = null

    private val repository by lazy { CalendarRepository(this) }
    private var calendars: List<CalendarRepository.CalendarInfo> = emptyList()
    private var suppressSelect = false

    private lateinit var spCalendar: Spinner
    private lateinit var tvCalendarHint: TextView
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadCalendars() }

    private val requestMedia = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionRows() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        step = savedInstanceState?.getInt("step", 0) ?: 0
        com.bellnote.util.PrivacyGate.ensure(this)

        deck = findViewById(R.id.deck)
        steps = listOf(
            findViewById(R.id.step1),
            findViewById(R.id.step2),
            findViewById(R.id.step3),
            findViewById(R.id.step4),
            findViewById(R.id.step5)
        )
        dots = listOf(
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3),
            findViewById(R.id.dot4),
            findViewById(R.id.dot5)
        )
        btnNext = findViewById(R.id.btnNext)
        tvPermHint = findViewById(R.id.tvPermHint)
        intro2 = findViewById(R.id.intro2)
        body2 = findViewById(R.id.body2)

        spCalendar = findViewById(R.id.spOnbCalendar)
        tvCalendarHint = findViewById(R.id.tvOnbCalendarHint)
        etApiKey = findViewById(R.id.etOnbApiKey)
        etModel = findViewById(R.id.etOnbModel)

        etApiKey.setText(Prefs.apiKey(this))
        etModel.setText(Prefs.model(this))

        btnNext.setOnClickListener {
            if (step == 1 && !hasBasicPermissions()) {
                toast("先把日历权限开了才能继续")
                return@setOnClickListener
            }
            if (step < steps.lastIndex) goTo(step + 1) else finishOnboarding()
        }

        findViewById<View>(R.id.btnHelpCalendar).setOnClickListener { showCalendarHelp() }

        spCalendar.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSelect) return
                if (position == calendars.size) {
                    suppressSelect = true
                    spCalendar.setSelection(0)
                    suppressSelect = false
                    createLocalCalendar()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<LinearLayout>(R.id.permCalendar).setOnClickListener {
            if (Permissions.hasCalendar(this)) toast("日历权限已经有了")
            else requestCalendar.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
        findViewById<LinearLayout>(R.id.permMedia).setOnClickListener {
            if (Permissions.hasMediaImages(this)) toast("相册权限已经有了")
            else requestMediaPermission()
        }
        findViewById<LinearLayout>(R.id.permA11y).setOnClickListener {
            if (a11yServiceEnabled()) toast("截屏服务已经开着")
            else if (!DeviceVendor.openAccessibility(this)) {
                toast("打不开无障碍页面，请手动到 设置 → 无障碍 → Bellnote 截屏")
            }
        }

        setupSwipe()

        // 初始状态：只显示当前页
        steps.forEachIndexed { index, view ->
            if (index == step) {
                view.visibility = View.VISIBLE
                view.alpha = 1f
                view.translationX = 0f
                view.scaleX = 1f
                view.scaleY = 1f
            } else {
                view.visibility = View.GONE
            }
        }
        renderDots()
        refreshPermissionRows()
        loadCalendars()
        if (savedInstanceState == null && step == 0) playIntro()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionRows()
        if (step == 3) loadCalendars()
    }

    private fun playIntro() {
        staggerContent(0)
        popHero(0)
    }

    // ---------------- 翻页 ----------------

    private fun goTo(target: Int) {
        if (target == step || target !in steps.indices) return
        val width = deck.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val dir = if (target > step) 1 else -1
        val old = steps[step]
        val next = steps[target]

        old.animate()
            .translationX(-dir * width * 0.25f)
            .alpha(0f)
            .setDuration(exitMs)
            .setInterpolator(easeOutSoft)
            .withEndAction {
                old.visibility = View.GONE
                resetView(old)
            }
            .start()

        next.visibility = View.VISIBLE
        next.translationX = dir * width * 0.25f
        next.alpha = 0f
        next.animate()
            .translationX(0f).alpha(1f)
            .setDuration(enterMs)
            .setInterpolator(easeStd)
            .start()

        afterPageChanged(target)
    }

    private fun afterPageChanged(target: Int) {
        step = target
        if (target == 1) playStage2() else {
            resetStage2()
            staggerContent(target)
        }
        popHero(target)
        renderDots()
        btnNext.text = if (step == steps.lastIndex) "开始使用" else "下一步"
        btnNext.alpha = 1f
        btnNext.isEnabled = true
        if (step == 1) updatePermGate()
        if (step == 3) loadCalendars()
    }

    /** 第二页：页头图标不动，标题先写「如何实现」，随后换成清单标题并展开内容。 */
    private fun playStage2() {
        val d = resources.displayMetrics.density
        pendingStage?.let { ui.removeCallbacks(it) }
        intro2.visibility = View.VISIBLE
        intro2.alpha = 0f
        intro2.translationY = 12f * d
        body2.visibility = View.INVISIBLE
        body2.alpha = 0f
        body2.translationY = 0f
        intro2.animate().alpha(1f).translationY(0f)
            .setDuration(enterMs).setInterpolator(easeStd).start()

        val runnable = Runnable {
            // 标题原地换词，下面的内容同时淡入，位置不跳
            intro2.animate().alpha(0f).translationY(-10f * d)
                .setDuration(160L).setInterpolator(easeOutSoft)
                .withEndAction {
                    intro2.text = STAGE2_TITLE
                    intro2.translationY = 10f * d
                    intro2.animate().alpha(1f).translationY(0f)
                        .setDuration(enterMs).setInterpolator(easeStd).start()
                }.start()

            body2.visibility = View.VISIBLE
            body2.animate().alpha(1f).setDuration(enterMs)
                .setStartDelay(140L).setInterpolator(easeStd).start()
        }
        pendingStage = runnable
        ui.postDelayed(runnable, 900L)
    }

    private fun resetStage2() {
        pendingStage?.let { ui.removeCallbacks(it) }
        pendingStage = null
        intro2.visibility = View.VISIBLE
        intro2.text = STAGE2_INTRO
        intro2.alpha = 1f
        intro2.translationY = 0f
        body2.visibility = View.INVISIBLE
        body2.alpha = 1f
        body2.translationY = 0f
    }

    /** 每页都是同一套：内容按 70ms 错开、向上 14dp 浮入。 */
    private fun staggerContent(index: Int) {
        val page = steps.getOrNull(index) ?: return
        val d = resources.displayMetrics.density
        val group = page as? android.view.ViewGroup ?: return
        val children = (0 until group.childCount).map { group.getChildAt(it) }
        children.forEachIndexed { i, child ->
            if (child === body2) return@forEachIndexed
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = 14f * d
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(80L + i * staggerMs)
                .setDuration(enterMs)
                .setInterpolator(easeStd)
                .start()
        }
    }

    private fun heroOf(index: Int): View = when (index) {
        1 -> findViewById(R.id.hero2)
        2 -> findViewById(R.id.hero3)
        3 -> findViewById(R.id.hero4)
        4 -> findViewById(R.id.hero5)
        else -> findViewById(R.id.hero1)
    }

    private fun popHero(index: Int) {
        val hero = heroOf(index)
        hero.animate().cancel()
        hero.scaleX = 0.94f
        hero.scaleY = 0.94f
        hero.alpha = 0.85f
        hero.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(60L).setDuration(enterMs)
            .setInterpolator(easeStd)
            .start()
    }

    /** 手指左右拖：SwipeDeck 负责判断是不是横向划，这里只管跟随和落点。 */
    private fun setupSwipe() {
        deck.onDrag = { dx -> dragTo(dx) }
        deck.onRelease = { dx ->
            val width = deck.width.toFloat().takeIf { it > 0f }
            if (width != null) {
                val target = if (dx < 0) step + 1 else step - 1
                if (abs(dx) > width * 0.22f && target in steps.indices) {
                    if (step == 1 && target == 2 && !hasBasicPermissions()) {
                        toast("先把日历权限开了才能继续")
                        snapBack()
                    } else {
                        settleTo(target)
                    }
                }
                else snapBack()
            }
        }
    }

    private fun dragTo(dx: Float) {
        val width = deck.width.toFloat().takeIf { it > 0f } ?: return
        val cur = steps[step]
        cur.translationX = dx
        cur.alpha = 1f - (abs(dx) / width) * 0.35f

        val target = if (dx < 0) step + 1 else step - 1
        if (target in steps.indices) {
            val nxt = steps[target]
            nxt.visibility = View.VISIBLE
            nxt.translationX = (if (dx < 0) width else -width) + dx * 0.55f
            nxt.alpha = (abs(dx) / width) * 0.9f
            nxt.scaleX = 1f
            nxt.scaleY = 1f
        }
    }

    private fun settleTo(target: Int) {
        val width = deck.width.toFloat().takeIf { it > 0f } ?: return
        val dir = if (target > step) 1 else -1
        val cur = steps[step]
        val nxt = steps[target]

        nxt.visibility = View.VISIBLE
        nxt.animate().translationX(0f).alpha(1f).setDuration(enterMs)
            .setInterpolator(easeStd).start()
        cur.animate().translationX(-dir * width).alpha(0f).setDuration(exitMs)
            .setInterpolator(easeOutSoft)
            .withEndAction {
                cur.visibility = View.GONE
                resetView(cur)
            }.start()

        afterPageChanged(target)
        popHero(target)
    }

    private fun snapBack() {
        val cur = steps[step]
        val width = deck.width.toFloat().takeIf { it > 0f } ?: return
        cur.animate().translationX(0f).alpha(1f).setDuration(exitMs).setInterpolator(easeStd).start()
        val other = if (cur.translationX < 0) step + 1 else step - 1
        if (other in steps.indices) {
            val nxt = steps[other]
            nxt.animate().translationX(if (cur.translationX < 0) width else -width).alpha(0f)
                .setDuration(exitMs).setInterpolator(easeOutSoft).withEndAction { nxt.visibility = View.GONE }.start()
        }
    }

    private fun resetView(view: View) {
        view.translationX = 0f
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun renderDots() {
        val d = resources.displayMetrics.density
        dots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(if (index <= step) R.drawable.bg_dot_active else R.drawable.bg_dot_idle)
            dot.alpha = if (index <= step) 1f else 0.35f
            (dot.layoutParams as LinearLayout.LayoutParams).width =
                ((if (index == step) 18 else 6) * d).toInt()
            dot.layoutParams = dot.layoutParams
        }
    }

    // ---------------- 配置与权限 ----------------

    private fun persistConfig() {
        val index = spCalendar.selectedItemPosition
        if (index in calendars.indices) Prefs.setCalendarId(this, calendars[index].id)
        val key = etApiKey.text.toString().trim()
        if (key.isNotEmpty()) Prefs.setApiKey(this, key)
        val model = etModel.text.toString().trim()
        if (model.isNotEmpty()) Prefs.setModel(this, model)
    }

    private fun showCalendarHelp() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("日历不止一个？")
            .setMessage(
                "手机里的日历可以有很多份：本地日历、各个邮箱账号同步来的日历、别的 App 自己建的日历。" +
                    "它们互相独立，Bellnote 只往你选中的那一份里写。\n\n" +
                    "想让待办出现在系统日历里、靠系统日历弹提醒：\n" +
                    "1. 选一个系统日历 App 会显示的日历（没账号时用列表最后的「＋ 新建本地日历…」）；\n" +
                    "2. 在系统日历 App 里确认这个日历是勾选显示的，并且允许它发通知；\n" +
                    "3. Bellnote 里的提醒时间会一起写进事件，系统日历到点弹提醒。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun loadCalendars() {
        if (!Permissions.hasCalendar(this)) {
            spCalendar.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item, listOf("（还没给日历权限）")
            )
            tvCalendarHint.visibility = View.VISIBLE
            tvCalendarHint.text = "下一步里点「日历读写」授权后，这里会自动列出来"
            return
        }
        Thread {
            val list = repository.listCalendars()
            runOnUiThread {
                calendars = list
                val names = if (list.isEmpty()) mutableListOf("（没有可写日历）")
                else list.map { it.displayName + if (it.accountName.isBlank()) "" else "（${it.accountName}）" }.toMutableList()
                names.add("＋ 新建本地日历…")
                suppressSelect = true
                spCalendar.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item, names
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                val saved = Prefs.calendarId(this)
                val index = list.indexOfFirst { it.id == saved }
                if (index >= 0) spCalendar.setSelection(index)
                suppressSelect = false
                tvCalendarHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                tvCalendarHint.text = "没有可写日历，选最后一项建一个"
            }
        }.start()
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
                    toast("已创建「待办」本地日历")
                    loadCalendars()
                } else {
                    toast("创建失败（系统拒绝了）：可装个支持本地日历的日历 App 再试")
                }
            }
        }.start()
    }

    private fun refreshPermissionRows() {
        setState(R.id.permCalendarState, Permissions.hasCalendar(this))
        val media = findViewById<TextView>(R.id.permMediaState)
        if (Permissions.hasMediaImages(this)) {
            media.text = "已开启"
            media.setTextColor(themeColor(R.attr.bnOk))
        } else {
            media.text = "建议开启"
            media.setTextColor(themeColor(R.attr.bnTextSecondary))
        }
        val a11y = findViewById<TextView>(R.id.permA11yState)
        if (a11yServiceEnabled()) {
            a11y.text = "已开启"
            a11y.setTextColor(themeColor(R.attr.bnOk))
        } else {
            a11y.text = "建议开启"
            a11y.setTextColor(themeColor(R.attr.bnTextSecondary))
        }
        updatePermGate()
    }

    /** 日历读写是「能跑起来」的最低要求，没它不让往下走；相册和无障碍都只是建议。 */
    private fun hasBasicPermissions(): Boolean = Permissions.hasCalendar(this)

    private fun updatePermGate() {
        val missing = mutableListOf<String>()
        if (!Permissions.hasCalendar(this)) missing.add("日历读写")

        tvPermHint.text = if (missing.isEmpty()) "" else "还要开：" + missing.joinToString("、")
        if (step == 1) {
            btnNext.isEnabled = missing.isEmpty()
            btnNext.alpha = if (missing.isEmpty()) 1f else 0.4f
        }
    }

    private fun requestMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestMedia.launch(permission)
    }

    private fun a11yServiceEnabled(): Boolean = try {
        android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
    } catch (e: Exception) {
        false
    }

    private fun setState(viewId: Int, ok: Boolean) {
        val view = findViewById<TextView>(viewId)
        view.text = if (ok) "已开启" else "未开启"
        view.setTextColor(themeColor(if (ok) R.attr.bnOk else R.attr.bnDanger))
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.data else android.graphics.Color.GRAY
    }

    private fun finishOnboarding() {
        persistConfig()
        Prefs.setOnboardingDone(this, true)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

