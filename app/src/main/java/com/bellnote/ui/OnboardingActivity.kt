package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
 * 首次安装的引导：四页。
 * 1) 三种记法（一句话 / 截图 / 分享·侧边栏）
 * 2) 首次配置：选目标日历 + 填识别用的 Key（都可跳过）
 * 3) 要开的开关（通知、日历、电池、自启动、相册）
 * 4) 外观与运行体检
 *
 * 页面之间做横向滑入淡出；只在第一次打开时出现，「跳过」也算看过。
 * 设置 → 诊断与帮助 → 再看一次新手引导 可以重看。
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = 0
    private lateinit var stepViews: List<View>
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

        stepViews = listOf(
            findViewById(R.id.step1),
            findViewById(R.id.step2),
            findViewById(R.id.step3),
            findViewById(R.id.step4)
        )
        dots = listOf(
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3),
            findViewById(R.id.dot4)
        )
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        spCalendar = findViewById(R.id.spOnbCalendar)
        tvCalendarHint = findViewById(R.id.tvOnbCalendarHint)
        etApiKey = findViewById(R.id.etOnbApiKey)
        etApiKey.setText(Prefs.apiKey(this))

        btnNext.setOnClickListener {
            if (step < stepViews.lastIndex) {
                goTo(step + 1)
            } else {
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

        render(animate = false)
        refreshPermissionRows()
        loadCalendars()

        // 首屏内容轻轻浮上来，别硬邦邦地出现
        stepViews[0].apply {
            alpha = 0f
            translationY = 24f * resources.displayMetrics.density
            animate().alpha(1f).translationY(0f).setDuration(320L).start()
        }
        dots.forEachIndexed { index, dot ->
            dot.alpha = if (index == 0) 1f else 0.4f
            dot.scaleX = if (index == 0) 1.25f else 1f
            dot.scaleY = dot.scaleX
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionRows()
        if (step == 1) loadCalendars()
    }

    /** 翻页：老页面往左滑出并淡掉，新页面从右边滑进来。 */
    private fun goTo(target: Int) {
        if (target == step || target !in stepViews.indices) return
        val forward = target > step
        val old = stepViews[step]
        val next = stepViews[target]
        val shift = 48f * resources.displayMetrics.density

        next.visibility = View.VISIBLE
        next.alpha = 0f
        next.translationX = if (forward) shift else -shift
        next.animate().alpha(1f).translationX(0f)
            .setDuration(240L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        old.animate().alpha(0f).translationX(if (forward) -shift else shift)
            .setDuration(180L)
            .withEndAction {
                old.visibility = View.GONE
                old.alpha = 1f
                old.translationX = 0f
            }
            .start()

        step = target
        if (step == 1) loadCalendars()
        render(animate = true)
    }

    private fun render(animate: Boolean) {
        if (!animate) {
            stepViews.forEachIndexed { index, view ->
                view.visibility = if (index == step) View.VISIBLE else View.GONE
            }
        }
        dots.forEachIndexed { index, dot ->
            val active = index <= step
            dot.setBackgroundResource(if (active) R.drawable.dot_ok else R.drawable.dot_idle)
            dot.animate().alpha(if (active) 1f else 0.4f).setDuration(180L).start()
            val scale = if (index == step) 1.35f else 1f
            dot.animate().scaleX(scale).scaleY(scale).setDuration(180L).start()
        }
        btnNext.text = if (step == stepViews.lastIndex) "开始使用" else "下一步"
        btnSkip.visibility = if (step == stepViews.lastIndex) View.GONE else View.VISIBLE
    }

    private fun persistConfig() {
        val index = spCalendar.selectedItemPosition
        if (index in calendars.indices) {
            Prefs.setCalendarId(this, calendars[index].id)
        }
        val key = etApiKey.text.toString().trim()
        if (key.isNotEmpty()) Prefs.setApiKey(this, key)
    }

    private fun loadCalendars() {
        if (!Permissions.hasCalendar(this)) {
            spCalendar.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item, listOf("（先授权日历权限）")
            )
            tvCalendarHint.text = "下面第 3 步里点「日历读写」也能授权"
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
                    "没有可写日历：点下面「创建本地日历」"
                } else {
                    "共 ${list.size} 个可写日历，待办写到选中的这个"
                }
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
            text = "去开启 ›"
            setTextColor(themeColor(R.attr.bnTextSecondary))
        }
    }

    private fun setState(viewId: Int, ok: Boolean) {
        val view = findViewById<TextView>(viewId)
        view.text = if (ok) "已开启" else "去开启 ›"
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
