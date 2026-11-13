package com.bellnote.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.bellnote.R
import com.bellnote.ai.LocalLlm
import com.bellnote.ai.LocalModels
import com.bellnote.ai.ModelDownloader
import com.bellnote.data.Prefs
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp
import com.bellnote.util.ThemeManager

/**
 * 本地模型页：开关 + 下载 / 取消 / 试跑 / 删除。
 *
 * 从设置里的「识别方式」流程图点「本地模型」卡片进来。模型 657 MB，下载走系统
 * DownloadManager；没装好或没认出结果时，识别会退回本地规则。
 */
class LocalModelActivity : AppCompatActivity() {

    private lateinit var swLocal: SwitchCompat
    private lateinit var tvState: TextView
    private lateinit var btnAction: Button
    private lateinit var btnDelete: Button

    private val ui = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_model)
        title = getString(R.string.local_model_title)
        enableHomeUp()

        swLocal = findViewById(R.id.swLocal)
        tvState = findViewById(R.id.tvLocalState)
        btnAction = findViewById(R.id.btnLocalAction)
        btnDelete = findViewById(R.id.btnLocalDelete)

        swLocal.isChecked = Prefs.localModelEnabled(this)
        swLocal.setOnCheckedChangeListener { _, on -> onSwitch(on) }
        btnAction.setOnClickListener { onAction() }
        btnDelete.setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.btnPickModel).setOnClickListener { pickModel() }
        render()
    }

    private fun onSwitch(on: Boolean) {
        Prefs.setLocalModelEnabled(this, on)
        render()
        if (on && !LocalLlm.isReady(this)) {
            // 开了但没模型：直接问要不要下，省得用户再去找
            AlertDialog.Builder(this)
                .setTitle("还要下载模型")
                .setMessage("本地模型约 657 MB。不下载的话这一路会被跳过，识别照样走云端或本地规则。")
                .setPositiveButton("开始下载") { _, _ -> startDownload() }
                .setNegativeButton("先不下", null)
                .show()
        }
    }

    private fun onAction() {
        when (ModelDownloader.status(this)) {
            ModelDownloader.Status.Ready -> runProbe()
            is ModelDownloader.Status.Downloading -> confirmCancel()
            else -> startDownload()
        }
    }

    private fun startDownload() {
        if (ModelDownloader.enqueue(this)) {
            toast("开始下载，通知栏也能看进度")
        } else {
            toast("下载没起来，检查一下网络")
        }
        render()
    }

    private fun confirmCancel() {
        AlertDialog.Builder(this)
            .setTitle("取消下载？")
            .setMessage("已经下了一部分，取消后下次要重新开始。")
            .setPositiveButton("取消下载") { _, _ ->
                ModelDownloader.cancel(this)
                render()
            }
            .setNegativeButton("继续下", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除模型？")
            .setMessage("会释放 657 MB。删除后「本地模型」这一路自动跳过，识别改走云端或本地规则。")
            .setPositiveButton("删除") { _, _ ->
                ModelDownloader.deleteSelected(this)
                Prefs.setLocalModelEnabled(this, false)
                swLocal.isChecked = false
                render()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runProbe() {
        val sample = "化学课\n11月14日 周六\n20:30\n把第三章错题重做一遍\n明天 9:00 交实验报告"
        toast("正在跑本地模型，第一次要几秒…")
        Thread {
            val probe = LocalLlm.probe(this, sample)
            runOnUiThread {
                val lines = buildString {
                    appendLine("耗时：${probe.millis} ms")
                    appendLine("认出：${probe.specs.size} 条")
                    probe.specs.forEach {
                        appendLine(
                            "· ${it.title} | ${com.bellnote.util.TimeFormat.full(it.startMillis)}" +
                                " | ${it.durationMinutes} 分钟"
                        )
                    }
                    probe.error?.let { appendLine("提示：$it") }
                    appendLine()
                    appendLine("原始输出：")
                    appendLine(probe.raw.take(1200))
                }
                AlertDialog.Builder(this)
                    .setTitle("本地模型试跑")
                    .setMessage(lines)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }.start()
    }

    /** 三个模型里挑一个：体积/速度/质量三选二，选中后下面的按钮跟着换。 */
    private fun pickModel() {
        val models = LocalModels.all
        val labels = models.map { model ->
            val mark = if (LocalModels.isReady(this, model)) "（已下载）" else ""
            "${model.name}\n${model.desc}$mark"
        }.toTypedArray()
        val current = models.indexOfFirst { it.id == Prefs.localModelId(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选本地模型")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setLocalModelId(this, models[which].id)
                dialog.dismiss()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun render() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
        val state = ModelDownloader.status(this)
        val model = LocalModels.selected(this)
        val totalMb = model.sizeMb
        tvState.text = when (state) {
            ModelDownloader.Status.Ready ->
                "已下载：${model.name}（$totalMb MB）。识别时在手机上跑，不联网。"
            is ModelDownloader.Status.Downloading ->
                "正在下载：${state.bytes / 1024 / 1024} / $totalMb MB（${state.percent}%）"
            is ModelDownloader.Status.Failed -> "${state.reason}，点下面重新下载。"
            ModelDownloader.Status.Missing -> "当前选的是 ${model.name}，还没下载（$totalMb MB）。"
        }
        btnAction.text = when (state) {
            ModelDownloader.Status.Ready -> "试跑一次"
            is ModelDownloader.Status.Downloading -> "取消下载"
            is ModelDownloader.Status.Failed -> "重新下载（$totalMb MB）"
            ModelDownloader.Status.Missing -> "下载模型（$totalMb MB）"
        }
        btnDelete.visibility =
            if (LocalLlm.modelSize(this) > 0) android.view.View.VISIBLE else android.view.View.GONE
        if (state is ModelDownloader.Status.Downloading) {
            val tick = Runnable { render() }
            ticker = tick
            ui.postDelayed(tick, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
