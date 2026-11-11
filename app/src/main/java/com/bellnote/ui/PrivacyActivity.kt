package com.bellnote.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.util.PrivacyText
import com.bellnote.util.ThemeManager

/**
 * 隐私政策全文。
 *
 * 两处入口：① 首次启动的同意弹窗里点「查看全文」；② 设置 → 诊断与帮助 → 隐私政策。
 * 从同意弹窗过来时，底部按钮是「同意并继续」，点了就记下已同意。
 */
class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)

        findViewById<TextView>(R.id.tvBody).text = PrivacyText.BODY

        val fromGate = intent.getBooleanExtra(EXTRA_GATE, false)
        val btn = findViewById<Button>(R.id.btnAgree)
        btn.text = if (fromGate) "同意并继续" else "我知道了"
        btn.setOnClickListener {
            if (fromGate) Prefs.setPrivacyAccepted(this, true)
            finish()
        }
    }

    companion object {
        const val EXTRA_GATE = "gate"
    }
}
