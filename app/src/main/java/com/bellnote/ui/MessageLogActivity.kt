package com.bellnote.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bellnote.R
import com.bellnote.data.MessageLog
import com.bellnote.service.TodoIngestor
import com.bellnote.util.Help
import com.bellnote.util.ThemeManager
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp

/**
 * 「ntfy 模式」里的消息收件箱：每条推送的原文 + 处理结果。
 *
 * 它回答的是「我说发了，为什么日历里没有」——以前只能看一行汇总，现在能逐条看原因。
 */
class MessageLogActivity : AppCompatActivity() {

    private lateinit var adapter: MessageLogAdapter
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_log)
        enableHomeUp()
        title = getString(R.string.ntfy_log_title)

        tvCount = findViewById(R.id.tvLogCount)
        tvEmpty = findViewById(R.id.tvLogEmpty)
        val recycler = findViewById<RecyclerView>(R.id.rvMessages)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MessageLogAdapter { entry -> retry(entry) }
        recycler.adapter = adapter

        findViewById<Button>(R.id.btnClearLog).setOnClickListener { confirmClear() }
        Help.attach(
            this,
            R.id.btnHelpLog,
            getString(R.string.help_log_title),
            getString(R.string.ntfy_log_hint)
        )
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun reload() {
        val app = applicationContext
        Thread {
            val entries = MessageLog.recent(app)
            runOnUiThread {
                adapter.submit(entries)
                tvCount.text = "共 ${entries.size} 条"
                tvEmpty.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }.start()
    }

    /** 只重试「收到且没写进日历」的；结果会以一条新记录出现在最上面。 */
    private fun retry(entry: MessageLog.Entry) {
        Thread {
            try {
                TodoIngestor.handle(
                    this,
                    entry.title,
                    entry.body,
                    source = "重试"
                )
            } catch (e: Exception) {
                android.util.Log.w("MessageLogActivity", "重试失败", e)
            }
            runOnUiThread {
                reload()
                toast(getString(R.string.ntfy_log_retry_done))
            }
        }.start()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空消息记录？")
            .setMessage("只清这里的历史和处理结果，日历和待办不受影响。首页那颗「N 条未写入」的提示也会一起清掉。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                MessageLog.clear(this)
                // 「没写进日历」的老提示就是这批记录的摘要，记录清了摘要也该清
                com.bellnote.data.Prefs.clearIgnoredNotices(this)
                reload()
                toast(getString(R.string.ntfy_log_cleared))
            }
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
