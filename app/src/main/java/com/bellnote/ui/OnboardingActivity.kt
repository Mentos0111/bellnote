package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.service.HealthInspector
import com.bellnote.util.DeviceVendor
import com.bellnote.util.Permissions
import com.bellnote.util.SystemPages
import com.bellnote.util.ThemeManager

/**
 * 首次安装的引导：三页。
 * 1) 这是什么、三种记法
 * 2) 必开的三个开关（能点、能看状态）
 * 3) 开始用 + 两个进阶提示
 *
 * 只在第一次打开时出现，看过就记进 Prefs；「跳过」也算看过。
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = 0
    private lateinit var stepViews: List<View>
    private lateinit var dots: List<View>
    private lateinit var btnNext: Button

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionRows() }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionRows() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        stepViews = listOf(
            findViewById(R.id.step1),
            findViewById(R.id.step2),
            findViewById(R.id.step3)
        )
        dots = listOf(findViewById(R.id.dot1), findViewById(R.id.dot2), findViewById(R.id.dot3))
        btnNext = findViewById(R.id.btnNext)

        btnNext.setOnClickListener {
            if (step < stepViews.lastIndex) {
                step++
                render()
            } else {
                finishOnboarding()
            }
        }
        findViewById<Button>(R.id.btnSkip).setOnClickListener { finishOnboarding() }

        findViewById<LinearLayout>(R.id.permNotification).setOnClickListener {
            if (!SystemPages.openNotificationAccess(this)) {
                toast("打不开通知使用权页面，请手动到 设置 → 通知 → 通知使用权")
            }
        }
        findViewById<LinearLayout>(R.id.permCalendar).setOnClickListener {
            requestCalendar.launch(
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

        render()
        refreshPermissionRows()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionRows()
    }

    private fun render() {
        stepViews.forEachIndexed { index, view ->
            view.visibility = if (index == step) View.VISIBLE else View.GONE
        }
        dots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(
                if (index <= step) R.drawable.dot_ok else R.drawable.dot_idle
            )
        }
        btnNext.text = if (step == stepViews.lastIndex) {
            getString(R.string.onboarding_start)
        } else {
            getString(R.string.onboarding_next)
        }
    }

    private fun refreshPermissionRows() {
        val notifications = Permissions.hasNotificationAccess(this)
        setState(R.id.permNotificationState, notifications)
        val calendar = Permissions.hasCalendar(this)
        setState(R.id.permCalendarState, calendar)
        val battery = HealthInspector.isIgnoringBatteryOptimizations(this)
        setState(R.id.permBatteryState, battery)
    }

    private fun setState(viewId: Int, ok: Boolean) {
        val view = findViewById<TextView>(viewId)
        view.text = if (ok) "已开启" else "去开启 ›"
        view.setTextColor(themeColor(if (ok) R.attr.bnPrimary else R.attr.bnDanger))
    }

    /** 取当前主题里的颜色（跟着配色主题走）。 */
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
