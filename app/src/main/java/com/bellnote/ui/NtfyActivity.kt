package com.bellnote.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.service.NtfyFetcher
import com.bellnote.service.NtfySender
import com.bellnote.util.ThemeManager
import com.bellnote.util.TimeFormat
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp

/**
 * 「第三方推送」单独一页：主题（当前提供方是 ntfy）、补查、测试推送、推送怎么处理、没写进日历的推送。
 * 设置首页只留一个入口，不把 ntfy 的细节摊在外面。
 */
class NtfyActivity : AppCompatActivity() {

    private lateinit var etTopic: EditText
    private lateinit var cbAutoImport: CheckBox
    private lateinit var cbStrictParse: CheckBox
    private lateinit var tvIgnored: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ntfy)
        enableHomeUp()
        title = "第三方推送"

        etTopic = findViewById(R.id.etNtfyTopic)
        cbAutoImport = findViewById(R.id.cbAutoImport)
        cbStrictParse = findViewById(R.id.cbStrictParse)
        tvIgnored = findViewById(R.id.tvIgnored)

        etTopic.setText(Prefs.ntfyTopic(this))
        cbAutoImport.isChecked = Prefs.autoImport(this)
        cbStrictParse.isChecked = Prefs.strictParse(this)

        findViewById<Button>(R.id.btnWhatIsNtfy).setOnClickListener { showWhatIsNtfy() }
        findViewById<Button>(R.id.btnCatchUpNow).setOnClickListener { catchUpNow() }
        findViewById<Button>(R.id.btnSendTest).setOnClickListener { sendTestPush() }
        findViewById<Button>(R.id.btnClearIgnored).setOnClickListener {
            Prefs.clearIgnoredNotices(this)
            renderIgnored()
            toast("已清空")
        }
        findViewById<Button>(R.id.btnSaveNtfy).setOnClickListener {
            persist()
            toast("已保存")
            finish()
        }
        renderIgnored()
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    /** 解释 ntfy 的用途：它是一条「电脑 → 手机」的通道，不是 Bellnote 自己的服务。 */
    private fun showWhatIsNtfy() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ntfy 是干什么的")
            .setMessage(
                "ntfy 是一条「电脑 → 手机」的推送通道，Bellnote 借它把电脑上生成的待办送到手机日历。\n\n" +
                    "· 电脑上：往一个主题（topic）发一条消息，内容是标题 + 时间，例如\n" +
                    "  curl -d '{\"title\":\"备课\",\"time\":\"2026-11-13 20:00\"}' ntfy.sh/你的主题\n\n" +
                    "· 手机上：Bellnote 读 ntfy 弹出的通知，按标题 + 时间写进日历。\n\n" +
                    "· 留空主题：就只靠分享 / 截图 / 手动添加，不接电脑。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun persist() {
        Prefs.setNtfyTopic(this, etTopic.text.toString())
        Prefs.setAutoImport(this, cbAutoImport.isChecked)
        Prefs.setStrictParse(this, cbStrictParse.isChecked)
    }

    /** 把「最近没写进日历的推送」和上次补查结果摊开，出问题不用连 adb 也能看。 */
    private fun renderIgnored() {
        val entries = Prefs.ignoredNotices(this)
        val lastAt = Prefs.lastCatchUpAt(this)
        val summary = Prefs.lastCatchUpSummary(this)
        val builder = StringBuilder()
        if (lastAt > 0L) {
            builder.append("上次补查：${TimeFormat.full(lastAt)}")
            if (summary.isNotBlank()) builder.append("　$summary")
            builder.append("\n")
        }
        if (entries.isEmpty()) {
            if (builder.isEmpty()) builder.append(getString(R.string.hint_ignored_empty))
        } else {
            builder.append("共 ${entries.size} 条（最新在上）：\n")
            for (entry in entries) {
                builder.append("· ${TimeFormat.monthDay(entry.at)}　${entry.reason}\n")
                builder.append("　${entry.text}\n")
            }
        }
        tvIgnored.text = builder.toString().trim()
    }

    /** 立即补查一次：先把输入框里的主题存下来，再联网拉，结果直接回显。 */
    private fun catchUpNow() {
        persist()
        if (Prefs.ntfyTopic(this).isBlank()) {
            toast("先填主题（例如 todo-xxxx），再点补查")
            return
        }
        val button = findViewById<Button>(R.id.btnCatchUpNow)
        button.isEnabled = false
        toast("正在补查…")
        Thread {
            val result = NtfyFetcher.catchUp(this)
            runOnUiThread {
                button.isEnabled = true
                renderIgnored()
                toast(result.summary())
            }
        }.start()
    }

    /** 从手机这头发一条测试推送，不用电脑也能自检整条链路。 */
    private fun sendTestPush() {
        persist()
        if (Prefs.ntfyTopic(this).isBlank()) {
            toast("先填主题（例如 todo-xxxx）")
            return
        }
        val button = findViewById<Button>(R.id.btnSendTest)
        button.isEnabled = false
        Thread {
            val result = NtfySender.sendTest(this)
            runOnUiThread {
                button.isEnabled = true
                toast(if (result.ok) result.detail else "发不出去：${result.detail}")
            }
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
