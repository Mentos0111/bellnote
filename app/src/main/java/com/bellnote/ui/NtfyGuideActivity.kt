package com.bellnote.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.service.NtfyBackground
import com.bellnote.util.DeviceVendor
import com.bellnote.util.Permissions
import com.bellnote.util.ThemeManager
import com.bellnote.util.enableHomeUp

/**
 * ntfy 模式的单独引导（3 页）：
 * ① 填主题 → ② 选后台接收方式 → ③ 放行权限。
 *
 * 为什么不并在主引导里：主引导要管所有用户，ntfy 是可选功能；把「闹钟与提醒」
 * 「电池白名单」塞进主引导只会让不用 ntfy 的人多背两个权限。
 */
class NtfyGuideActivity : AppCompatActivity() {

    private var page = 0
    private lateinit var pages: List<View>
    private lateinit var btnNext: Button
    private lateinit var etTopic: EditText
    private lateinit var rbAlarm: RadioButton
    private lateinit var rbService: RadioButton
    private lateinit var rbOff: RadioButton
    private lateinit var tvBgHint: TextView
    private lateinit var tvPermState: TextView
    private lateinit var btnNotify: Button
    private lateinit var btnExact: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ntfy_guide)
        enableHomeUp()
        title = getString(R.string.ntfy_guide_title)

        page = savedInstanceState?.getInt(KEY_PAGE, 0) ?: 0
        pages = listOf(
            findViewById(R.id.g1),
            findViewById(R.id.g2),
            findViewById(R.id.g3)
        )
        btnNext = findViewById(R.id.btnGuideNext)
        etTopic = findViewById(R.id.etGuideTopic)
        rbAlarm = findViewById(R.id.rbGuideAlarm)
        rbService = findViewById(R.id.rbGuideService)
        rbOff = findViewById(R.id.rbGuideOff)
        tvBgHint = findViewById(R.id.tvGuideBgHint)
        tvPermState = findViewById(R.id.tvGuidePermState)
        btnNotify = findViewById(R.id.btnGuideNotify)
        btnExact = findViewById(R.id.btnGuideExact)

        etTopic.setText(Prefs.ntfyTopic(this))
        when (Prefs.ntfyBackgroundMode(this)) {
            Prefs.BG_SERVICE -> rbService.isChecked = true
            Prefs.BG_OFF -> rbOff.isChecked = true
            else -> rbAlarm.isChecked = true
        }
        renderBgHint()

        rbAlarm.setOnCheckedChangeListener { _, c -> if (c) setMode(Prefs.BG_ALARM) }
        rbService.setOnCheckedChangeListener { _, c -> if (c) setMode(Prefs.BG_SERVICE) }
        rbOff.setOnCheckedChangeListener { _, c -> if (c) setMode(Prefs.BG_OFF) }

        btnNotify.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            } else {
                toast("去系统设置里允许 Bellnote 的通知")
            }
        }
        btnExact.setOnClickListener { openExactAlarmSettings() }
        findViewById<Button>(R.id.btnGuideBattery).setOnClickListener {
            DeviceVendor.openBattery(this)
        }
        findViewById<Button>(R.id.btnGuideSkip).setOnClickListener { finish() }
        btnNext.setOnClickListener {
            if (page < pages.lastIndex) {
                persistPage()
                goTo(page + 1)
            } else {
                persistPage()
                Prefs.setNtfyGuideDone(this, true)
                NtfyBackground.sync(this)
                toast("好了，之后在 ntfy 页面里都能改")
                finish()
            }
        }
        goTo(page)
    }

    private fun setMode(mode: String) {
        Prefs.setNtfyBackgroundMode(this, mode)
        renderBgHint()
    }

    private fun renderBgHint() {
        tvBgHint.setText(
            when (Prefs.ntfyBackgroundMode(this)) {
                Prefs.BG_OFF -> R.string.ntfy_bg_hint_off
                Prefs.BG_SERVICE -> R.string.ntfy_bg_hint_service
                else -> R.string.ntfy_bg_hint_alarm
            }
        )
    }

    private fun goTo(target: Int) {
        page = target.coerceIn(0, pages.lastIndex)
        pages.forEachIndexed { index, view ->
            view.visibility = if (index == page) View.VISIBLE else View.GONE
        }
        btnNext.setText(
            if (page == pages.lastIndex) R.string.ntfy_guide_done else R.string.ntfy_guide_next
        )
        if (page == pages.lastIndex) renderPermState()
    }

    private fun persistPage() {
        if (page == 0) {
            Prefs.setNtfyTopic(this, etTopic.text.toString().trim())
            if (Prefs.ntfyTopic(this).isNotBlank()) Prefs.setNtfyEnabled(this, true)
        }
        NtfyBackground.sync(this)
    }

    private fun renderPermState() {
        val notifyOk = Permissions.hasPostNotifications(this)
        val exactOk = NtfyBackground.canUseExactAlarm(this)
        val batteryOk = isBatteryIgnored()
        tvPermState.text = buildString {
            append(if (notifyOk) "通知：已允许" else "通知：未允许")
            append(" · ")
            append(if (exactOk) "闹钟：可用" else "闹钟：未开")
            append(" · ")
            append(if (batteryOk) "白名单：已加" else "白名单：未加")
        }
        btnNotify.visibility = if (notifyOk) View.GONE else View.VISIBLE
        btnExact.visibility =
            if (Prefs.ntfyBackgroundMode(this) == Prefs.BG_ALARM && !exactOk) View.VISIBLE else View.GONE
    }

    private fun isBatteryIgnored(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openExactAlarmSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            android.net.Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("打不开闹钟权限页，去 设置 → 应用 → 特殊权限 里找")
        }
    }

    override fun onResume() {
        super.onResume()
        if (page == pages.lastIndex) renderPermState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, page)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) renderPermState()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_PAGE = "guide_page"
        private const val REQ_NOTIFY = 7101
    }
}
