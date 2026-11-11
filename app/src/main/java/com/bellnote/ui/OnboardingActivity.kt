package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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

/**
 * 首次安装的引导，三页：
 * 1) 记法（一句话 / 截图 / 分享）
 * 2) 首次配置：目标日历 + 识别 Key
 * 3) 要开的开关
 *
 * 翻页是整层平移 + 缩放淡入，像卡片推过去；进度条当期拉长成一条。
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = 0
    private lateinit var deck: FrameLayout
    private lateinit var steps: List<View>
    private lateinit var dots: List<View>
    private lateinit var btnNext: Button
    private lateinit var btnSkip: Button

    private val repository by lazy { CalendarRepository(this) }
    private var calendars: List<CalendarRepository.CalendarInfo> = emptyList()

    private lateinit var spCalendar: Spinner
    private lateinit var tvCalendarHint: TextView
    private lateinit var etApiKey: EditText

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadCalendars() }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionRows() }

    private val requestMedia = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionRows() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        deck = findViewById(R.id.deck)
        steps = listOf(findViewById(R.id.step1), findViewById(R.id.step2), findViewById(R.id.step3))
        dots = listOf(findViewById(R.id.dot1), findViewById(R.id.dot2), findViewById(R.id.dot3))
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        spCalendar = findViewById(R.id.spOnbCalendar)
        tvCalendarHint = findViewById(R.id.tvOnbCalendarHint)
        etApiKey = findViewById(R.id.etOnbApiKey)
        etApiKey.setText(Prefs.apiKey(this))

        btnNext.setOnClickListener {
            if (step < steps.lastIndex) goTo(step + 1) else {
                persistConfig()
                finishOnboarding()
            }
        }
        btnSkip.setOnClickListener {
            persistConfig()
            finishOnboarding()
        }

        findViewById<Button>(R.id.btnOnbCreateCalendar).setOnClickListener { createLocalCalendar() }

        findViewById<LinearLayout>(R.id.permNotification).setOnClickListener {
            if (!SystemPages.openNotificationAccess(this)) {
                toast("打不开通知使用权页面，请手动到 设置 → 通知 → 通知使用权")
            }
        }
        findViewById<LinearLayout>(R.id.permCalendar).setOnClickListener {
            if (Permissions.hasCalendar(this)) toast("日历权限已经有了")
            else requestCalendar.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
        findViewById<LinearLayout>(R.id.permBattery).setOnClickListener {
            if (HealthInspector.isIgnoringBatteryOptimizations(this)) {
                toast("已经在白名单里了")
            } else if (!DeviceVendor.openBattery(this)) {
                toast("打不开电池页面，请手动到 设置 → 电池 → 应用耗电管理")
            }
        }
        findViewById<LinearLayout>(R.id.permAutostart).setOnClickListener {
            if (!DeviceVendor.openAutostart(this)) {
                toast("打不开自启动页面，去系统设置里搜「自启动」")
            }
        }
        findViewById<LinearLayout>(R.id.permMedia).setOnClickListener {
            if (Permissions.hasMediaImages(this)) toast("相册权限已经有了")
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestMedia.launch(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                requestMedia.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // 初始只显示第 1 页，其余两页先挪到右边待命
        steps.forEachIndexed { index, view ->
            view.visibility = if (index == 0) View.VISIBLE else View.GONE
            view.translationX = 0f
        }
        renderDots()
        refreshPermissionRows()
        loadCalendars()

        // 首屏：整体从下方轻轻浮起
        val d = resources.displayMetrics.density
        steps[0].apply {
            alpha = 0f
            translationY = 26f * d
            animate().alpha(1f).translationY(0f).setDuration(360L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        findViewById<View>(R.id.hero1).apply {
            scaleX = 0.8f
            scaleY = 0.8f
            animate().scaleX(1f).scaleY(1f).setStartDelay(120L).setDuration(420L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionRows()
        if (step == 1) loadCalendars()
    }

    /** 卡片式翻页：旧页往左退、缩小淡掉；新页从右侧带着 1.04 的缩放滑进来。 */
    private fun goTo(target: Int) {
        if (target == step || target !in steps.indices) return
        val width = deck.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val forward = target > step
        val dir = if (forward) 1 else -1
        val old = steps[step]
        val next = steps[target]

        old.animate()
            .translationX(-dir * width * 0.35f)
            .alpha(0f)
            .scaleX(0.94f).scaleY(0.94f)
            .setDuration(240L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                old.visibility = View.GONE
                old.translationX = 0f
                old.alpha = 1f
                old.scaleX = 1f
                old.scaleY = 1f
            }
            .start()

        // 主视觉比页面慢半拍，做一点点视差
        val hero = heroOf(target)
        hero.translationX = dir * width * 0.18f
        hero.animate().translationX(0f).setDuration(360L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        next.visibility = View.VISIBLE
        next.translationX = dir * width * 0.45f
        next.alpha = 0f
        next.scaleX = 1.04f
        next.scaleY = 1.04f
        next.animate()
            .translationX(0f)
            .alpha(1f)
            .scaleX(1f).scaleY(1f)
            .setDuration(300L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        step = target
        popHero(step)
        if (step == 1) loadCalendars()
        renderDots()
        btnNext.text = if (step == steps.lastIndex) "开始使用" else "下一步"
        btnSkip.animate().alpha(if (step == steps.lastIndex) 0f else 1f).setDuration(150L).start()
        btnSkip.isEnabled = step != steps.lastIndex
    }

    private fun heroOf(index: Int): View = when (index) {
        1 -> findViewById(R.id.hero2)
        2 -> findViewById(R.id.hero3)
        else -> findViewById(R.id.hero1)
    }

    private fun popHero(index: Int) {
        val hero = heroOf(index)
        hero.scaleX = 0.85f
        hero.scaleY = 0.85f
        hero.alpha = 0.6f
        hero.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(80L).setDuration(380L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f))
            .start()
    }

    /** 进度条：当期那颗从圆点拉成一小条。 */
    private fun renderDots() {
        val d = resources.displayMetrics.density
        dots.forEachIndexed { index, dot ->
            val active = index == step
            dot.setBackgroundResource(if (index <= step) R.drawable.bg_dot_active else R.drawable.bg_dot_idle)
            dot.alpha = if (index <= step) 1f else 0.35f
            (dot.layoutParams as LinearLayout.LayoutParams).width =
                ((if (active) 18 else 6) * d).toInt()
            dot.layoutParams = dot.layoutParams
            dot.animate().alpha(if (index <= step) 1f else 0.35f).setDuration(180L).start()
        }
    }

    private fun persistConfig() {
        val index = spCalendar.selectedItemPosition
        if (index in calendars.indices) Prefs.setCalendarId(this, calendars[index].id)
        val key = etApiKey.text.toString().trim()
        if (key.isNotEmpty()) Prefs.setApiKey(this, key)
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
                val names = if (list.isEmpty()) listOf("（没有可写日历）")
                else list.map { it.displayName + if (it.accountName.isBlank()) "" else "（${it.accountName}）" }
                spCalendar.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item, names
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                val saved = Prefs.calendarId(this)
                val index = list.indexOfFirst { it.id == saved }
                if (index >= 0) spCalendar.setSelection(index)
                tvCalendarHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                tvCalendarHint.text = "没有可写日历，点下面那个按钮建一个"
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
        setState(R.id.permNotificationState, Permissions.hasNotificationAccess(this))
        setState(R.id.permCalendarState, Permissions.hasCalendar(this))
        setState(R.id.permBatteryState, HealthInspector.isIgnoringBatteryOptimizations(this))
        setState(R.id.permMediaState, Permissions.hasMediaImages(this))

        findViewById<TextView>(R.id.permAutostartState).apply {
            text = "未开启"
            setTextColor(themeColor(R.attr.bnDanger))
        }
    }

    private fun setState(viewId: Int, ok: Boolean) {
        val view = findViewById<TextView>(viewId)
        view.text = if (ok) "已开启" else "未开启"
        view.setTextColor(themeColor(if (ok) R.attr.bnPrimary else R.attr.bnDanger))
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.data else android.graphics.Color.GRAY
    }

    private fun finishOnboarding() {
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
