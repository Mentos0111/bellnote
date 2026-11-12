package com.bellnote.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.util.ThemeManager
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp

/**
 * 「第三方通道」：放所有可选的第三方输入源。
 *
 * 现在只有一个 ntfy，做成子入口单独一页；以后加别的通道（别的推送服务 / 自建接口）就往这里加一行。
 * 不打开任何通道时，App 完全不联网。
 */
class ThirdPartyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_third_party)
        enableHomeUp()
        title = getString(R.string.third_party_title)

        findViewById<android.view.View>(R.id.rowNtfy).setOnClickListener {
            startActivity(Intent(this, NtfyActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderNtfySummary()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun renderNtfySummary() {
        val line = findViewById<TextView>(R.id.tvNtfySummary)
        val on = Prefs.ntfyEnabled(this)
        val topic = Prefs.ntfyTopic(this)
        line.text = when {
            !on -> "未开启"
            topic.isBlank() -> "已开启，还没填主题"
            else -> "已开启 · $topic"
        }
    }
}
