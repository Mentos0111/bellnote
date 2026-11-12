package com.bellnote.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.bellnote.R
import com.bellnote.data.MessageLog
import com.bellnote.data.Prefs
import com.bellnote.service.NtfyFetcher
import com.bellnote.service.NtfyPollScheduler
import com.bellnote.service.NtfySender
import com.bellnote.util.Help
import com.bellnote.util.NtfyEndpoint
import com.bellnote.util.ThemeManager
import com.bellnote.util.TimeFormat
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp

/**
 * 「ntfy 模式」：把原来散在「第三方推送」里的东西收在一页。
 *
 * 页面结构：模式开关 → 消息从哪来（服务器 / 主题）→ 消息记录 → 处理规则。
 * 说明文字一律挂控件旁边的「?」，页面上只留状态和操作。
 * 关掉开关 = 完全不联网；填了主题才会开始定时拉取。
 */
class NtfyActivity : AppCompatActivity() {

    private lateinit var swMode: SwitchCompat
    private lateinit var tvStatus: TextView
    private lateinit var spChannel: Spinner
    private lateinit var groupServer: View
    private lateinit var etServer: EditText
    private lateinit var etTopic: EditText
    private lateinit var cbAutoImport: CheckBox
    private lateinit var cbStrictParse: CheckBox
    private lateinit var btnCatchUp: Button
    private lateinit var btnSendTest: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ntfy)
        enableHomeUp()
        title = getString(R.string.ntfy_mode_title)

        swMode = findViewById(R.id.swNtfyMode)
        tvStatus = findViewById(R.id.tvNtfyStatus)
        spChannel = findViewById(R.id.spNtfyChannel)
        groupServer = findViewById(R.id.groupServer)
        etServer = findViewById(R.id.etNtfyServer)
        etTopic = findViewById(R.id.etNtfyTopic)
        cbAutoImport = findViewById(R.id.cbAutoImport)
        cbStrictParse = findViewById(R.id.cbStrictParse)
        btnCatchUp = findViewById(R.id.btnCatchUpNow)
        btnSendTest = findViewById(R.id.btnSendTest)

        etTopic.setText(Prefs.ntfyTopic(this))
        cbAutoImport.isChecked = Prefs.autoImport(this)
        cbStrictParse.isChecked = Prefs.strictParse(this)
        swMode.isChecked = Prefs.ntfyEnabled(this)
        setupChannelSpinner()
        bindActions()
        attachHelp()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderLogEntry()
    }

    override fun onPause() {
        super.onPause()
        // 用户可能只是按返回键走的：能存的先存，别让改了一半的设置丢掉
        persistLenient()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun bindActions() {
        swMode.setOnCheckedChangeListener { _, on ->
            Prefs.setNtfyEnabled(this, on)
            NtfyPollScheduler.reschedule(this)
            if (on && Prefs.ntfyTopic(this).isBlank()) etTopic.requestFocus()
            renderStatus()
        }

        findViewById<Button>(R.id.btnMessageLog).setOnClickListener {
            startActivity(Intent(this, MessageLogActivity::class.java))
        }
        btnCatchUp.setOnClickListener { catchUpNow() }
        btnSendTest.setOnClickListener { sendTestPush() }
        findViewById<Button>(R.id.btnSaveNtfy).setOnClickListener { saveAndFinish() }
    }

    /** 只有不好一句话说清的才挂「?」；简单的用下面一行批注。 */
    private fun attachHelp() {
        Help.attach(this, R.id.btnHelpChannel, getString(R.string.help_channel_title), getString(R.string.help_channel_body))
        Help.attach(this, R.id.btnHelpRules, getString(R.string.help_rules_title), getString(R.string.help_rules_body))
    }

    /** 通道只有两种：官方 ntfy.sh（免配置）和自己搭的服务器。 */
    private fun setupChannelSpinner() {
        val labels = listOf(
            getString(R.string.ntfy_channel_cloud),
            getString(R.string.ntfy_channel_custom)
        )
        spChannel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val server = Prefs.ntfyServer(this)
        val custom = server.trimEnd('/') != NtfyEndpoint.DEFAULT
        spChannel.setSelection(if (custom) 1 else 0)
        if (custom) etServer.setText(server)

        var initial = true
        spChannel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val showServer = position == 1
                groupServer.visibility = if (showServer) View.VISIBLE else View.GONE
                if (showServer && etServer.text.isBlank()) etServer.setText(NtfyEndpoint.DEFAULT)
                if (initial) {
                    initial = false
                    return
                }
                renderStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun renderStatus() {
        val on = Prefs.ntfyEnabled(this)
        val topic = Prefs.ntfyTopic(this)
        val server = Prefs.ntfyServer(this)
        val host = runCatching { java.net.URI(server).host }.getOrNull() ?: server
        val lastAt = Prefs.lastCatchUpAt(this)
        val lastSummary = Prefs.lastCatchUpSummary(this)

        tvStatus.text = when {
            !on -> "已关闭：不联网、不拉取。上面「一句话 / 截图 / 分享」照常可用。"
            topic.isBlank() -> "已开启，但还没填主题：填完保存才会开始拉取。"
            lastAt <= 0 -> "已开启 · $host · $topic\n还没拉过，点「立即补查」试一次。"
            else -> {
                val brief = lastSummary.removePrefix("补查：").ifBlank { "没有结果" }
                "已开启 · $host · $topic\n上次：$brief（${TimeFormat.compact(lastAt)}）"
            }
        }

        val ready = on && topic.isNotBlank()
        btnCatchUp.isEnabled = ready
        btnSendTest.isEnabled = ready
    }

    private fun renderLogEntry() {
        val count = MessageLog.count(this)
        findViewById<Button>(R.id.btnMessageLog).text = if (count > 0) {
            "${getString(R.string.ntfy_log_entry)}（$count 条）"
        } else {
            getString(R.string.ntfy_log_entry)
        }
    }

    private fun saveAndFinish() {
        val check = validateServer()
        if (check == null) return
        persist(check)
        toast("已保存")
        finish()
    }

    /** 校验服务器地址；不合法时提示并返回 null。 */
    private fun validateServer(): NtfyEndpoint.Check? {
        val custom = spChannel.selectedItemPosition == 1
        val raw = if (custom) etServer.text.toString() else NtfyEndpoint.DEFAULT
        val check = NtfyEndpoint.check(raw)
        if (!check.ok) {
            toast(check.reason ?: "服务器地址不对")
            if (custom) etServer.requestFocus()
            return null
        }
        return check
    }

    private fun persist(check: NtfyEndpoint.Check) {
        val topic = etTopic.text.toString().trim()
        val serverChanged = check.normalized != Prefs.ntfyServer(this)
        val topicChanged = topic != Prefs.ntfyTopic(this)
        if (serverChanged || topicChanged) {
            // 换服务器 / 换主题后，旧的 since 属于另一条通道，不能拿来当起点
            Prefs.setNtfySince(this, 0L)
        }
        Prefs.setNtfyTopic(this, topic)
        Prefs.setNtfyServer(this, check.normalized)
        Prefs.setAutoImport(this, cbAutoImport.isChecked)
        Prefs.setStrictParse(this, cbStrictParse.isChecked)
        NtfyPollScheduler.reschedule(this)
    }

    /** 返回键/切走时保存：地址不合法就不动服务器，别把能用的配置写坏。 */
    private fun persistLenient() {
        Prefs.setNtfyTopic(this, etTopic.text.toString())
        Prefs.setAutoImport(this, cbAutoImport.isChecked)
        Prefs.setStrictParse(this, cbStrictParse.isChecked)
        validateServerQuiet()?.let { Prefs.setNtfyServer(this, it.normalized) }
        NtfyPollScheduler.reschedule(this)
    }

    private fun validateServerQuiet(): NtfyEndpoint.Check? {
        val custom = spChannel.selectedItemPosition == 1
        val raw = if (custom) etServer.text.toString() else NtfyEndpoint.DEFAULT
        val check = NtfyEndpoint.check(raw)
        return if (check.ok) check else null
    }

    /** 立即补查一次：先把输入框里的值存下来，再联网拉，结果直接回显。 */
    private fun catchUpNow() {
        val check = validateServer()
        if (check == null) return
        persist(check)
        if (!Prefs.ntfyEnabled(this)) {
            toast("先打开 ntfy 模式")
            return
        }
        if (Prefs.ntfyTopic(this).isBlank()) {
            toast("先填主题（例如 todo-xxxx），再点补查")
            return
        }
        btnCatchUp.isEnabled = false
        toast("正在补查…")
        Thread {
            val result = NtfyFetcher.catchUp(this)
            runOnUiThread {
                renderStatus()
                renderLogEntry()
                toast(result.summary())
            }
        }.start()
    }

    /** 从手机这头发一条测试推送，不用电脑也能自检整条链路。 */
    private fun sendTestPush() {
        val check = validateServer()
        if (check == null) return
        persist(check)
        if (!Prefs.ntfyEnabled(this)) {
            toast("先打开 ntfy 模式")
            return
        }
        if (Prefs.ntfyTopic(this).isBlank()) {
            toast("先填主题（例如 todo-xxxx）")
            return
        }
        btnSendTest.isEnabled = false
        Thread {
            val result = NtfySender.sendTest(this)
            runOnUiThread {
                renderStatus()
                renderLogEntry()
                toast(if (result.ok) result.detail else "发不出去：${result.detail}")
            }
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
