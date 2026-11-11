package com.bellnote.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bellnote.service.ScreenCaptureService
import com.bellnote.util.DeviceVendor
import java.io.File

/**
 * 「Bellnote 截屏记」——放在系统侧边栏（智能侧边栏）里的入口。
 *
 * 用法：在别的 App 页面里划出侧边栏，点它 → 这里截下当前屏幕（本页是透明的，不挡内容）
 * → 把图交给截图识别页自动开始识别 → 出待办给你确认。
 *
 * 前提是开了无障碍服务（系统侧边栏没法塞自定义动作，只能靠这个入口去截）。
 * 没开无障碍就退化成「告诉你怎么开 / 先手动截个图」。
 */
class SidebarCaptureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ScreenCaptureService.isConnected()) {
            askToEnable()
            return
        }
        Toast.makeText(this, "正在截图…", Toast.LENGTH_SHORT).show()
        ScreenCaptureService.capture { bitmap ->
            runOnUiThread { handleBitmap(bitmap) }
        }
    }

    private fun handleBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            Toast.makeText(this, "截图失败：可以去设置里关掉再打开「截屏服务」，或先手动截个图", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val uri = saveToCache(bitmap)
        if (uri == null) {
            Toast.makeText(this, "截图存不下来", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        startActivity(ImportActivity.intentForScreenshot(this, uri, auto = true))
        finish()
    }

    private fun saveToCache(bitmap: Bitmap): Uri? = try {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (e: Exception) {
        Log.w("SidebarCapture", "保存截图失败", e)
        null
    }

    private fun askToEnable() {
        val vendor = DeviceVendor.current()
        AlertDialog.Builder(this)
            .setTitle("先开一次「截屏服务」")
            .setMessage(
                "从侧边栏点一下就要截到别的 App 的界面，需要无障碍权限（只用来截图，不读输入内容）。\n\n" +
                    "开法：设置 → 无障碍（${vendor.label} 里可能叫「辅助功能」）→ 已下载的服务 → Bellnote 截屏 → 打开。"
            )
            .setPositiveButton("去开启") { _, _ ->
                DeviceVendor.openAccessibility(this)
                finish()
            }
            .setNeutralButton("先手动截图") { _, _ ->
                // 退路：用户自己截屏（三指下滑 / 侧边栏的「截屏」），稍后 Bellnote 会弹「识别这张截图？」
                Toast.makeText(this, "截图后 Bellnote 会弹「识别这张截图？」", Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton("算了", null)
            .setOnDismissListener { if (!isFinishing) finish() }
            .show()
    }
}
