package com.bellnote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.util.Diagnostics
import com.bellnote.util.FeedbackBundle
import com.bellnote.util.ThemeManager

/**
 * 反馈与日志：一屏看完要发什么，一键打包发出去。
 *
 * 设计取舍：
 * - 先给预览再给按钮，不让人闭着眼睛把日志发走；
 * - 主按钮走系统分享面板（微信 / QQ / 邮件都能接），邮件和「仅保存」是备选；
 * - 打包在后台线程做，按钮期间禁用，避免连点生成一堆包。
 */
class FeedbackActivity : AppCompatActivity() {

    private lateinit var tvPreview: TextView
    private lateinit var buttons: List<Button>

    private var cachedText: String? = null
    private var cachedZip: java.io.File? = null
    private var pendingSave: java.io.File? = null
    private var busy = false

    private val saveDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val file = pendingSave
        if (uri == null || file == null) return@registerForActivityResult
        Thread {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: false
                true
            }.getOrDefault(false)
            runOnUiThread {
                toast(
                    if (ok) getString(R.string.feedback_saved, file.name)
                    else getString(R.string.feedback_save_failed)
                )
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)
        title = getString(R.string.feedback_title)

        tvPreview = findViewById(R.id.tvPreview)
        buttons = listOf(
            findViewById(R.id.btnSend),
            findViewById(R.id.btnMail),
            findViewById(R.id.btnSave),
            findViewById(R.id.btnCopy)
        )

        loadPreview()

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            ensureBundle { bundle ->
                val subject = getString(R.string.feedback_subject, versionName())
                if (!FeedbackBundle.share(this, bundle.file, subject)) {
                    toast(getString(R.string.feedback_send_failed))
                }
            }
        }

        findViewById<Button>(R.id.btnMail).setOnClickListener {
            ensureBundle { bundle ->
                val subject = getString(R.string.feedback_subject, versionName())
                val address = getString(R.string.dev_mail)
                if (!FeedbackBundle.mail(this, bundle.file, subject, address)) {
                    toast(getString(R.string.feedback_send_failed))
                }
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            ensureBundle { bundle ->
                pendingSave = bundle.file
                saveDocument.launch(bundle.file.name)
            }
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = cachedText
            if (text == null) {
                toast(getString(R.string.feedback_building))
                loadPreview()
                return@setOnClickListener
            }
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Bellnote 诊断", text))
            toast(getString(R.string.feedback_copied))
        }
    }

    /** 预览只为「看一眼」，所以允许失败：失败就显示一句空状态，不影响下面按钮。 */
    private fun loadPreview() {
        Thread {
            val text = runCatching { FeedbackBundle.preview(applicationContext) }.getOrNull()
            runOnUiThread {
                if (text == null) {
                    tvPreview.text = getString(R.string.feedback_loading_failed)
                } else {
                    cachedText = text
                    tvPreview.text = Diagnostics.previewOf(text)
                }
            }
        }.start()
    }

    private fun ensureBundle(onReady: (FeedbackBundle.BundleFile) -> Unit) {
        val zip = cachedZip
        val text = cachedText
        if (zip != null && text != null && zip.exists()) {
            onReady(FeedbackBundle.BundleFile(zip, text))
            return
        }
        if (busy) return
        busy = true
        setBusy(true)

        Thread {
            val result = runCatching { FeedbackBundle.build(applicationContext) }.getOrNull()
            runOnUiThread {
                busy = false
                setBusy(false)
                if (result == null) {
                    toast(getString(R.string.feedback_save_failed))
                    return@runOnUiThread
                }
                cachedZip = result.file
                cachedText = result.text
                tvPreview.text = Diagnostics.previewOf(result.text)
                onReady(result)
            }
        }.start()
    }

    private fun setBusy(on: Boolean) {
        buttons.forEach { it.isEnabled = !on }
        if (on) toast(getString(R.string.feedback_building))
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "-"

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
