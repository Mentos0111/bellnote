package com.bellnote.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.bellnote.util.ThemeManager
import com.bellnote.R
import com.bellnote.ai.VisionExtractor
import com.bellnote.data.CalendarRepository
import com.bellnote.data.Prefs
import com.bellnote.data.SOURCE_SHOT
import com.bellnote.data.TodoSpec
import com.bellnote.data.TodoStore
import com.bellnote.util.ImageUtils
import com.bellnote.util.Permissions
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp
import com.bellnote.util.TimeFormat

/** 选一张聊天截图 → 压缩 → DeepSeek VLM 识别 → 带结果跳到编辑页确认。 */
class ImportActivity : AppCompatActivity() {

    private var imageUri: Uri? = null
    private lateinit var ivPreview: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnPick: Button
    private lateinit var btnRecognize: Button
    private lateinit var btnRestore: Button
    private lateinit var progress: ProgressBar

    private val ui = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var startedAt = 0L

    /** 上一次识别的模型原始输出（用来在进程被杀后恢复现场）。 */
    private var lastRaw = ""

    companion object {
        const val EXTRA_AUTO_RECOGNIZE = "auto_recognize"

        /** 聊天截图字小，1280 长边会把字压糊；2000 是识别质量与上传体积的折中。 */
        const val RECOGNIZE_MAX_SIDE = 2000
        const val RECOGNIZE_QUALITY = 85

        /** 识别结果保留 3 天，超过就当过期清掉。 */
        const val PENDING_MAX_AGE_MS = 3 * 24 * 3600_000L

        const val STATE_URI = "state_uri"
        const val STATE_RAW = "state_raw"

        /** 从「截图建待办」通知点进来：带图 + 自动开始识别。 */
        fun intentForScreenshot(context: android.content.Context, uri: Uri, auto: Boolean): Intent =
            Intent(context, ImportActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                putExtra(EXTRA_AUTO_RECOGNIZE, auto)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
    }

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 相册返回的是一次性授权，取不到持久化权限也能立刻读，忽略即可
            }
            // 换了新图，上次的结果就不再适用，别再让用户点到旧结果。
            resetPending()
            showImage(uri)
            // 选完图直接开始识别，省一次点击；没配 Key 时 recognize() 会自己提示并跳设置
            tvStatus.postDelayed({
                if (!isFinishing && !isDestroyed) recognize()
            }, 320)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)
        enableHomeUp()
        title = getString(R.string.title_import)

        ivPreview = findViewById(R.id.ivPreview)
        tvStatus = findViewById(R.id.tvStatus)
        btnPick = findViewById(R.id.btnPick)
        btnRecognize = findViewById(R.id.btnRecognize)
        btnRestore = findViewById(R.id.btnRestore)
        progress = findViewById(R.id.progress)

        btnPick.setOnClickListener { picker.launch(arrayOf("image/*")) }
        btnRecognize.setOnClickListener { recognize() }
        btnRestore.setOnClickListener { restorePending() }

        handleIncomingIntent(intent)

        val savedRaw = savedInstanceState?.getString(STATE_RAW).orEmpty()
        val savedUri = savedInstanceState?.getString(STATE_URI)
        if (savedRaw.isNotBlank()) {
            offerRestore(savedRaw, savedUri)
        } else if (imageUri == null) {
            // 从相册/分享带着图进来的，就别再把上一次的旧结果顶上来
            offerRestoreFromPrefs()
        } else {
            maybeAutoRecognize(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        maybeAutoRecognize(intent)
    }

    /** 截图通知点进来时直接开识别，省掉一次「点识别」。 */
    private fun maybeAutoRecognize(intent: Intent?) {
        val auto = intent?.getBooleanExtra(EXTRA_AUTO_RECOGNIZE, false) ?: false
        if (!auto || imageUri == null) return
        tvStatus.postDelayed({
            if (!isFinishing && !isDestroyed && imageUri != null) recognize()
        }, 400)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        imageUri?.let { outState.putString(STATE_URI, it.toString()) }
        if (lastRaw.isNotBlank()) outState.putString(STATE_RAW, lastRaw)
    }

    override fun onDestroy() {
        stopWaiting()
        super.onDestroy()
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

    /** 清掉「上次结果」，换图或重新识别时调用。 */
    private fun resetPending() {
        lastRaw = ""
        if (::btnRestore.isInitialized) btnRestore.visibility = View.GONE
        Prefs.clearPendingImport(this)
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

        btnRecognize.text = "识别这张图"
        btnRecognize.isEnabled = false
        btnPick.isEnabled = false
        btnRestore.visibility = View.GONE
        lastRaw = ""
        startWaiting()
        val model = Prefs.model(this)

        Thread {
            val base64 = ImageUtils.toJpegBase64(this, uri, RECOGNIZE_MAX_SIDE, RECOGNIZE_QUALITY)
            if (base64 == null) {
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    stopWaiting()
                    tvStatus.text = "图片压缩失败，换一张试试"
                    btnRecognize.isEnabled = true
                    btnPick.isEnabled = true
                }
                return@Thread
            }
            val kb = base64.length * 3 / 4 / 1024
            ui.post {
                if (isFinishing || isDestroyed) return@post
                tvStatus.text = "已上传（约 $kb KB），等模型返回…"
            }

            val outcome = VisionExtractor.extract(apiKey, model, base64)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                handleOutcome(outcome, uri)
            }
        }.start()
    }

    private fun handleOutcome(outcome: VisionExtractor.Outcome, uri: Uri) {
        stopWaiting()
        btnRecognize.isEnabled = true
        btnPick.isEnabled = true
        val specs = outcome.specs
        if (specs.isEmpty()) {
            tvStatus.text = "识别失败：${outcome.error}"
            btnRecognize.text = "重试识别"
            // 图还在，直接再点一次就行，不用重新选图
            btnRecognize.isEnabled = imageUri != null
            return
        }
        lastRaw = outcome.raw
        when (specs.size) {
            1 -> {
                // 单条走编辑页，编辑页自己带参数，不需要在设置里留现场
                val spec = specs.first()
                val extra = if (outcome.error.isNullOrBlank()) "" else "\n${outcome.error}"
                tvStatus.text =
                    "识别完成：${spec.title} / ${TimeFormat.full(spec.startMillis)}\n请在编辑页确认后再保存$extra"
                startActivity(EditActivity.intentForSpec(this, spec))
                // 识别页的活已经交给编辑页，关掉它，免得返回时还要多按一次
                finish()
            }
            else -> {
                // 多条要用户勾选，这一步最容易被来电/切后台打断，先把现场存下来
                if (outcome.raw.isNotBlank()) {
                    Prefs.savePendingImport(this, uri.toString(), outcome.raw)
                }
                tvStatus.text = "识别完成：${specs.size} 条待办，勾选后写入"
                confirmBatch(specs)
            }
        }
    }

    // ---------------- 上次结果恢复 ----------------

    private fun offerRestoreFromPrefs() {
        val pending = Prefs.pendingImport(this) ?: return
        if (System.currentTimeMillis() - pending.at > PENDING_MAX_AGE_MS) {
            Prefs.clearPendingImport(this)
            return
        }
        offerRestore(pending.raw, pending.uri.ifBlank { null })
    }

    private fun offerRestore(raw: String, uriText: String?) {
        val specs = try {
            VisionExtractor.parseSpecs(VisionExtractor.stripFences(raw), System.currentTimeMillis())
        } catch (e: Exception) {
            emptyList()
        }
        if (specs.isEmpty()) {
            Prefs.clearPendingImport(this)
            return
        }
        lastRaw = raw
        if (uriText != null && imageUri == null) {
            try {
                showImage(Uri.parse(uriText))
            } catch (e: Exception) {
                // 原图权限可能已经失效，恢复结果本身不受影响
            }
        }
        btnRestore.visibility = View.VISIBLE
        btnRestore.text = getString(R.string.btn_restore_last) + "（${specs.size} 条）"
        tvStatus.text = "上次识别到 ${specs.size} 条待办还没写入，可点上面按钮继续"
    }

    private fun restorePending() {
        val raw = lastRaw.ifBlank { Prefs.pendingImport(this)?.raw.orEmpty() }
        val specs = try {
            VisionExtractor.parseSpecs(VisionExtractor.stripFences(raw), System.currentTimeMillis())
        } catch (e: Exception) {
            emptyList()
        }
        if (specs.isEmpty()) {
            toast("上次结果已经失效，重新识别一次吧")
            btnRestore.visibility = View.GONE
            Prefs.clearPendingImport(this)
            return
        }
        btnRestore.visibility = View.GONE
        when (specs.size) {
            1 -> {
                startActivity(EditActivity.intentForSpec(this, specs.first()))
                finish()
            }
            else -> {
                tvStatus.text = "恢复：${specs.size} 条待办，勾选后写入"
                confirmBatch(specs)
            }
        }
    }

    // ---------------- 批量写入 ----------------

    /**
     * 一张截图识别出多条时，逐条勾选要写哪些。
     * 勾选前先跟日历里已有的比一遍：同标题同时间的标成「已存在」并默认不勾 ——
     * 同一张截图重复识别时，最容易把待办写成两份。
     */
    private fun confirmBatch(specs: List<TodoSpec>) {
        tvStatus.text = "正在核对是否重复…"
        Thread {
            val repo = CalendarRepository(this)
            val duplicate = BooleanArray(specs.size)
            for (i in specs.indices) {
                duplicate[i] = try {
                    repo.findOwnEvents(specs[i].title, specs[i].startMillis).isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                showBatchDialog(specs, duplicate)
            }
        }.start()
    }

    private fun showBatchDialog(specs: List<TodoSpec>, duplicate: BooleanArray) {
        if (duplicate.all { it }) {
            tvStatus.text = "这几条日历里都有了，没有重复写入"
            toast("这几条日历里都有了")
            return
        }
        val labels = specs.mapIndexed { index, spec ->
            val suffix = if (duplicate[index]) "（已在日历里）" else ""
            "${spec.title}｜${TimeFormat.monthDay(spec.startMillis)} · ${spec.durationMinutes} 分钟$suffix"
        }.toTypedArray()
        val checked = BooleanArray(specs.size) { !duplicate[it] }
        AlertDialog.Builder(this)
            .setTitle("识别到 ${specs.size} 条待办")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("写入选中") { _, _ ->
                val picked = specs.filterIndexed { index, _ -> checked[index] }
                if (picked.isEmpty()) {
                    toast("一条都没选，没有写入")
                } else {
                    saveAll(picked)
                }
            }
            .setNeutralButton("只看第一条") { _, _ ->
                startActivity(EditActivity.intentForSpec(this, specs.first()))
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveAll(specs: List<TodoSpec>) {
        if (!Permissions.hasCalendar(this)) {
            toast("先给日历权限才能写入")
            return
        }
        tvStatus.text = "正在写入 ${specs.size} 条…"
        Thread {
            val repository = CalendarRepository(this)
            val store = TodoStore(this)
            val calendarId = repository.defaultCalendarId()
            if (calendarId <= 0) {
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    toast("没有可写入的日历，先去设置里选一个")
                }
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
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (saved > 0) Prefs.clearPendingImport(this@ImportActivity)
                toast("已写入 $saved / ${specs.size} 条")
                tvStatus.text = "已写入 $saved 条，可在主页查看、点开修改或长按删除"
            }
        }.start()
    }

    // ---------------- 等待进度 ----------------

    private fun startWaiting() {
        startedAt = SystemClock.elapsedRealtime()
        progress.visibility = View.VISIBLE
        tvStatus.text = "正在压缩图片…"
        val runner = object : Runnable {
            override fun run() {
                val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                tvStatus.text = "识别中…已等 $seconds 秒（图越复杂越慢，最长约 2 分钟）"
                ui.postDelayed(this, 1000)
            }
        }
        ticker = runner
        ui.postDelayed(runner, 1500)
    }

    private fun stopWaiting() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
        if (::progress.isInitialized) progress.visibility = View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
