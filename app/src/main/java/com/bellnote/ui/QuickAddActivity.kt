package com.bellnote.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.data.MANUAL_DEFAULT_DURATION_MINUTES
import com.bellnote.data.TodoSpec
import com.bellnote.parser.QuickParse

/**
 * 「分享一段文字给 Bellnote」和「选中文字 → Bellnote 记一条」的落点。
 *
 * 没有界面：收到文字就地用本地规则解析（明天下午三点交作业），
 * 能认出时间就直接把编辑页填好给你看一眼；认不出就只填标题、让你点时间。
 */
class QuickAddActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = incomingText()
        if (text.isBlank()) {
            finish()
            return
        }
        val now = System.currentTimeMillis()
        val parsed = QuickParse.parseFirstLine(text, now)
        val intent = if (parsed != null) {
            Toast.makeText(
                this,
                "已认出：${parsed.title}",
                Toast.LENGTH_SHORT
            ).show()
            EditActivity.intentForSpec(
                this,
                TodoSpec(parsed.title, parsed.startMillis, parsed.durationMinutes)
            )
        } else {
            EditActivity.intentForSpec(
                this,
                TodoSpec(
                    title = firstLine(text).take(40),
                    startMillis = alignedNextHour(),
                    durationMinutes = MANUAL_DEFAULT_DURATION_MINUTES
                )
            )
        }
        startActivity(intent)
        finish()
    }

    private fun incomingText(): String {
        val fromSend = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (fromSend.isNotBlank()) return fromSend.trim()
        // 「选中文字 → 处理文字」给的是 EXTRA_PROCESS_TEXT
        return intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
    }

    private fun firstLine(text: String): String =
        text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** 认不出时间时给个「下一个整点」，用户在编辑页调一下就行。 */
    private fun alignedNextHour(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.HOUR_OF_DAY, 1)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
