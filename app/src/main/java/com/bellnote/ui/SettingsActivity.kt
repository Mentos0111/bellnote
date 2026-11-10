package com.bellnote.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.service.ReconcileScheduler
import com.bellnote.util.Permissions
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
    private var intervalValues: List<Int> = emptyList()

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadCalendars() }

    override fun onCreate(savedInstanceState: Bundle?) {
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

        etRemind.setText(Prefs.remindMinutes(this).ifEmpty { listOf(1440, 60) }.joinToString(","))
        etApiKey.setText(Prefs.apiKey(this))
        etModel.setText(Prefs.model(this))
        cbAutoImport.isChecked = Prefs.autoImport(this)
        cbStrictParse.isChecked = Prefs.strictParse(this)
        cbReconcile.isChecked = Prefs.reconcileEnabled(this)
        cbReconcileNotify.isChecked = Prefs.reconcileNotify(this)
        setupIntervalSpinner()
        findViewById<TextView>(R.id.tvVersion).text = versionLabel()

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
