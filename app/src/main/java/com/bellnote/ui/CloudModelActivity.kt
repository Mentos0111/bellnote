package com.bellnote.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.bellnote.R
import com.bellnote.ai.CloudModels
import com.bellnote.data.Prefs
import com.bellnote.util.ThemeManager
import com.bellnote.util.enableHomeUp
import com.bellnote.util.handleHomeUp

/**
 * 云端模型页：开关 + 服务地址 + Key + 模型名。
 *
 * 服务地址接受任何 OpenAI 兼容接口；「读取模型列表」会去 {base}/models 拉清单，
 * 并标出哪些模型看得到图。选到纯文本模型时允许保存，但这里会红字提醒。
 */
class CloudModelActivity : AppCompatActivity() {

    private lateinit var swCloud: SwitchCompat
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var tvState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_model)
        title = getString(R.string.cloud_model_title)
        enableHomeUp()

        swCloud = findViewById(R.id.swCloud)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        tvState = findViewById(R.id.tvCloudState)

        swCloud.isChecked = Prefs.cloudModelEnabled(this)
        etBaseUrl.setText(Prefs.cloudBaseUrl(this))
        etApiKey.setText(Prefs.apiKey(this))
        etModel.setText(Prefs.model(this))

        swCloud.setOnCheckedChangeListener { _, on ->
            Prefs.setCloudModelEnabled(this, on)
            renderState()
        }
        etModel.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = renderState()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        findViewById<Button>(R.id.btnSaveCloud).setOnClickListener { save() }
        findViewById<Button>(R.id.btnListModels).setOnClickListener { loadModels() }
        renderState()
    }

    private fun save() {
        Prefs.setCloudBaseUrl(this, etBaseUrl.text.toString())
        Prefs.setApiKey(this, etApiKey.text.toString())
        Prefs.setModel(this, etModel.text.toString())
        toast("已保存")
        renderState()
    }

    /** 读 /models：能看图的排前面，纯文本的标出来但仍可选。 */
    private fun loadModels() {
        val base = etBaseUrl.text.toString()
        val key = etApiKey.text.toString()
        if (key.isBlank()) {
            toast("先填 API Key")
            return
        }
        toast("正在读模型列表…")
        Thread {
            val result = CloudModels.list(base, key)
            runOnUiThread {
                if (!result.ok) {
                    AlertDialog.Builder(this)
                        .setTitle("读不到模型列表")
                        .setMessage(result.error ?: "未知原因")
                        .setPositiveButton("知道了", null)
                        .show()
                    return@runOnUiThread
                }
                val labels = result.models.map { model ->
                    val flag = if (model.vision) "✓" else "⚠"
                    "$flag ${model.id}\n    ${model.reason}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("选一个能看图的模型")
                    .setItems(labels) { _, which ->
                        etModel.setText(result.models[which].id)
                        if (!result.models[which].vision) {
                            toast("这个看着像纯文本模型，识别截图可能失败")
                        }
                        renderState()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    private fun renderState() {
        val hasKey = Prefs.apiKey(this).isNotBlank() || etApiKey.text.isNotBlank()
        val model = etModel.text.toString().trim().ifBlank { Prefs.model(this) }
        val vision = CloudModels.inspect(model)
        val warning = if (!vision.vision) "⚠ ${vision.reason}\n" else ""
        tvState.text = when {
            !swCloud.isChecked -> "云端已关：识别时直接走本地模型或本地规则。"
            !hasKey -> "已开，但还没填 API Key，现在不会调用云端。"
            else -> warning + "已开：有网时优先用 $model。"
        }
        tvState.setTextColor(
            if (warning.isNotEmpty()) themeColor(R.attr.bnDanger) else themeColor(R.attr.bnTextSecondary)
        )
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.data else android.graphics.Color.GRAY
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (handleHomeUp(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
