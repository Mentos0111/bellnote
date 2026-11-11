package com.bellnote.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.util.ThemeManager

/**
 * 「关于」页：按主流 App 的样子来 ——
 * 应用图标 + 版本号 + 一张信息表（版本 / 开发者 / 邮箱 / 主页 / 协议）+ 隐私说明 + 版权 + 两个动作按钮。
 */
class AboutActivity : AppCompatActivity() {

    private val developer = "Mentos"
    private val email = "1904656703@qq.com"
    private val repo = "https://github.com/Mentos0111/bellnote"
    private val license = "MIT License"

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        title = getString(R.string.about_entry)

        val fullVersion = "$versionName ($versionCode)"
        findViewById<TextView>(R.id.tvAboutVersion).text = "版本 $fullVersion"
        findViewById<TextView>(R.id.rowVersion).text = fullVersion
        findViewById<TextView>(R.id.rowDeveloper).text = developer
        findViewById<TextView>(R.id.rowEmail).text = email
        findViewById<TextView>(R.id.rowRepo).text = "github.com/Mentos0111/bellnote"
        findViewById<TextView>(R.id.rowLicense).text = license
        findViewById<TextView>(R.id.tvCopyright).text =
            "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} $developer · 保留所有权利"

        findViewById<android.view.View>(R.id.rowMail).setOnClickListener { mailDeveloper() }
        findViewById<android.view.View>(R.id.rowGithub).setOnClickListener { openRepo() }
        findViewById<Button>(R.id.btnMailDev).setOnClickListener { mailDeveloper() }
        findViewById<Button>(R.id.btnOpenRepo).setOnClickListener { openRepo() }
    }

    private val versionName: String
        get() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "-"

    private val versionCode: Long
        get() = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)

    private fun mailDeveloper() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_SUBJECT, "Bellnote 反馈（v$versionName）")
            putExtra(
                Intent.EXTRA_TEXT,
                "\n\n——\n版本 $versionName ($versionCode)\n" +
                    "${com.bellnote.util.DeviceVendor.current().label} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                    "主题：${ThemeManager.labelOf(Prefs.theme(this@AboutActivity))}"
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            copyEmail()
        }
    }

    private fun openRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, repo, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun copyEmail() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("email", email))
        android.widget.Toast.makeText(
            this,
            "没装邮件 App，地址已复制：$email",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
