package com.bellnote.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.bellnote.R
import com.bellnote.ai.VisionExtractor
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.data.SOURCE_SHOT
import com.bellnote.data.TodoSpec
import com.bellnote.data.TodoStore
import com.bellnote.util.ImageUtils
import com.bellnote.util.Permissions
import com.bellnote.util.TimeFormat

/** 选一张聊天截图 → 压缩 → DeepSeek VLM 识别 → 带结果跳到编辑页确认。 */
class ImportActivity : AppCompatActivity() {

    private var imageUri: Uri? = null
    private lateinit var ivPreview: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnRecognize: Button

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 相册返回的是一次性授权，取不到持久化权限也能立刻读，忽略即可
            }
            showImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)
        title = getString(R.string.title_import)

        ivPreview = findViewById(R.id.ivPreview)
        tvStatus = findViewById(R.id.tvStatus)
        btnRecognize = findViewById(R.id.btnRecognize)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            picker.launch(arrayOf("image/*"))
        }
        btnRecognize.setOnClickListener { recognize() }

        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val target = intent ?: return
        val uri = when (target.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                target, Intent.EXTRA_STREAM, Uri::class.java
            )
            Intent.ACTION_VIEW -> target.data
            else -> null
        }
        if (uri != null) showImage(uri)
    }

    private fun showImage(uri: Uri) {
        imageUri = uri
        try {
            val bitmap = ImageUtils.loadBitmap(this, uri, 1200)
            if (bitmap == null) {
                tvStatus.text = "这张图读不出来，换一张试试"
            } else {
                ivPreview.setImageBitmap(bitmap)
                tvStatus.text = "已选好截图，点「调用 DeepSeek 识别」"
            }
        } catch (e: Exception) {
            tvStatus.text = "读图失败：${e.message}"
        }
    }

    private fun recognize() {
        val uri = imageUri
        if (uri == null) {
            toast("先选一张截图")
            return
        }
        val apiKey = Prefs.apiKey(this)
        if (apiKey.isBlank()) {
            toast("还没配置 DeepSeek API Key")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        btnRecognize.isEnabled = false
        tvStatus.text = "正在压缩图片…"
        val model = Prefs.model(this)

        Thread {
            val base64 = ImageUtils.toJpegBase64(this, uri, 1280, 80)
            if (base64 == null) {
                runOnUiThread {
                    tvStatus.text = "图片压缩失败，换一张试试"
                    btnRecognize.isEnabled = true
                }
                return@Thread
            }
            runOnUiThread { tvStatus.text = "已上传（约 ${base64.length / 1024} KB），等待模型返回…" }

            val outcome = VisionExtractor.extract(apiKey, model, base64)
            runOnUiThread {
                btnRecognize.isEnabled = true
                val specs = outcome.specs
                when {
                    specs.isEmpty() -> tvStatus.text = "识别失败：${outcome.error}"
                    specs.size == 1 -> {
                        val spec = specs.first()
                        val extra = if (outcome.error.isNullOrBlank()) "" else "\n${outcome.error}"
                        tvStatus.text =
                            "识别完成：${spec.title} / ${TimeFormat.full(spec.startMillis)}\n请在编辑页确认后再保存$extra"
                        startActivity(EditActivity.intentForSpec(this, spec))
                    }
                    else -> {
                        tvStatus.text = "识别完成：${specs.size} 条待办，确认后一起写入"
                        confirmBatch(specs)
                    }
                }
            }
        }.start()
    }

    /** 一张截图识别出多条时，先让用户确认，再批量写入。 */
    private fun confirmBatch(specs: List<TodoSpec>) {
        val body = specs.joinToString("\n") {
            "· ${it.title}　${TimeFormat.monthDay(it.startMillis)} · ${it.durationMinutes} 分钟"
        }
        AlertDialog.Builder(this)
            .setTitle("识别到 ${specs.size} 条待办")
            .setMessage("$body\n\n全部写进日历？写进去之后还能在主页点开修改或长按删除。")
            .setPositiveButton("全部写入") { _, _ -> saveAll(specs) }
            .setNegativeButton("只看第一条") { _, _ ->
                startActivity(EditActivity.intentForSpec(this, specs.first()))
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun saveAll(specs: List<TodoSpec>) {
        if (!Permissions.hasCalendar(this)) {
            toast("先给日历权限才能写入")
            return
        }
        Thread {
            val repository = CalendarRepository(this)
            val store = TodoStore(this)
            val calendarId = repository.defaultCalendarId()
            if (calendarId <= 0) {
                runOnUiThread { toast("没有可写入的日历，先去设置里选一个") }
                return@Thread
            }
            var done = 0
            for (spec in specs) {
                val eventId = repository.insertEvent(spec, calendarId)
                if (eventId > 0) {
                    store.record(spec, eventId, SOURCE_SHOT)
                    done++
                }
            }
            val saved = done
            runOnUiThread {
                toast("已写入 $saved / ${specs.size} 条")
                tvStatus.text = "已写入 $saved 条，可在主页查看、点开修改或长按删除"
                btnRecognize.isEnabled = true
            }
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
