package com.bellnote.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.service.NtfyPollScheduler
import com.bellnote.util.ConfigCode
import com.bellnote.util.ThemeManager
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp

/**
 * 配置码：把「配色主题 + ntfy 主题」搬给另一台手机（没有 API Key，也没有待办数据）。
 *
 * 一个页面两种用法：
 * - 生成：读当前设置编成 `BN1.` 开头的一串字，复制或直接发给朋友；
 * - 导入：粘贴那串字，先给人话预览「会变成什么」，确认后才写进设置。
 *
 * 入口在设置 → 备份，和导出 .ics / 备份账本放一起（都是「把东西搬走」）。
 */
class ConfigCodeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_GENERATE = "generate"
        const val MODE_IMPORT = "import"

        fun intentFor(context: Context, mode: String): Intent =
            Intent(context, ConfigCodeActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    private var decoded: ConfigCode.Decoded? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_code)
        enableHomeUp()

        val generate = intent.getStringExtra(EXTRA_MODE) != MODE_IMPORT
        title = if (generate) "生成配置码" else "导入配置码"
        findViewById<View>(R.id.groupGenerate).visibility = if (generate) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupImport).visibility = if (generate) View.GONE else View.VISIBLE
        if (generate) setupGenerate() else setupImport()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    // ---------------- 生成 ----------------

    private fun setupGenerate() {
        val themeId = Prefs.theme(this)
        val topic = Prefs.ntfyTopic(this)
        val code = ConfigCode.encode(themeId, topic)
        findViewById<TextView>(R.id.tvIntro).text =
            "把下面这串发给朋友，她在「设置 → 备份 → 导入配置码」粘贴就能一次设好。" +
                "\n这次会带过去：配色「${ThemeManager.labelOf(themeId)}」" +
                if (topic.isBlank()) "（没填 ntfy 主题，所以只带配色）" else "、ntfy 主题「$topic」"
        findViewById<TextView>(R.id.tvCode).text = code

        findViewById<Button>(R.id.btnCopyCode).setOnClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Bellnote 配置码", code))
            toast("已复制，粘给朋友就行")
        }
        findViewById<Button>(R.id.btnShareCode).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "用 Bellnote 的「设置 → 备份 → 导入配置码」粘贴这串：\n$code")
            }
            startActivity(Intent.createChooser(send, "发给朋友"))
        }
    }

    // ---------------- 导入 ----------------

    private fun setupImport() {
        findViewById<TextView>(R.id.tvIntro).text =
            "粘贴朋友发来的那串配置码，确认下面预览的内容后再导入。" +
                "\n只会改配色和 ntfy 主题，不动你的待办、日历和 API Key。"
        val input = findViewById<EditText>(R.id.etCode)
        val preview = findViewById<TextView>(R.id.tvPreview)
        val importButton = findViewById<Button>(R.id.btnImport)

        fun render() {
            val result = ConfigCode.decode(input.text.toString())
            decoded = result
            when (result) {
                is ConfigCode.Decoded.Failed -> {
                    preview.text = result.reason
                    preview.setTextColor(colorOf(R.attr.bnTextSecondary))
                    setImportEnabled(importButton, false)
                }
                is ConfigCode.Decoded.Ok -> {
                    val palette = ThemeManager.palettes.firstOrNull { it.id == result.payload.theme }
                    if (palette == null) {
                        preview.text = "对方用的配色「${result.payload.theme}」这个版本还没有，先升级 Bellnote 再导入"
                        preview.setTextColor(colorOf(R.attr.bnDanger))
                        setImportEnabled(importButton, false)
                        return
                    }
                    val topic = result.payload.topic
                    preview.text = buildString {
                        append("导入后会变成：\n")
                        append("· 配色：${palette.label}\n")
                        append(
                            if (topic.isBlank()) {
                                "· ntfy 主题：不带（导完还是你现在填的）"
                            } else {
                                "· ntfy 主题：$topic（会打开 ntfy 模式）"
                            }
                        )
                    }
                    preview.setTextColor(colorOf(R.attr.bnTextPrimary))
                    setImportEnabled(importButton, true)
                }
            }
        }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = render()
        })
        importButton.setOnClickListener {
            val result = decoded as? ConfigCode.Decoded.Ok ?: return@setOnClickListener
            applyImport(result.payload)
        }
        render()
    }

    /** 只改配色和 ntfy 主题；主题换了把桌面图标也记上，等 App 退到后台再切。 */
    private fun applyImport(payload: ConfigCode.Payload) {
        Prefs.setTheme(this, payload.theme)
        ThemeManager.scheduleLauncherIcon()
        if (payload.topic.isNotBlank()) {
            Prefs.setNtfyTopic(this, payload.topic)
            Prefs.setNtfyEnabled(this, true)
            NtfyPollScheduler.reschedule(this)
        }
        val parts = mutableListOf(ThemeManager.labelOf(payload.theme))
        if (payload.topic.isNotBlank()) parts += "ntfy 主题"
        toast("已导入：" + parts.joinToString(" + "))
        finish()
    }

    /** 按钮样式在禁用状态不会自己变淡，这里手动压一点透明度，让「还不能点」看得出来。 */
    private fun setImportEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    private fun colorOf(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.data else android.graphics.Color.GRAY
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
