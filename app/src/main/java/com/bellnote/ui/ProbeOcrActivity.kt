package com.bellnote.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.ai.OfflineOcr
import com.bellnote.service.ScreenCaptureService
import java.io.File

/**
 * 诊断用透明页面：起来就截当前屏（自己透明，截到的是底下那个 App）→ 离线 OCR → 落盘 → 自杀。
 *
 *   adb shell am start -n com.bellnote/.ui.ProbeOcrActivity
 *
 * 它走的是「侧边栏点一下」同一条路径，所以能真实验证：截图能不能拿到、OCR 认不认得出来。
 */
class ProbeOcrActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenCaptureService.awaitConnection(2500L) { ready ->
            runOnUiThread {
                if (!ready) {
                    write("无障碍服务未连接")
                    finish()
                    return@runOnUiThread
                }
                ScreenCaptureService.capture { bitmap ->
                    onCaptured(bitmap)
                }
            }
        }
    }

    private fun onCaptured(bitmap: Bitmap?) {
        if (bitmap == null) {
            write("截图失败")
            finish()
            return
        }
        val started = System.currentTimeMillis()
        OfflineOcr.recognize(bitmap) { text ->
            val cost = System.currentTimeMillis() - started
            Log.i("ProbeOcr", "OCR ${text.length} 字 ${cost}ms")
            write("耗时=${cost}ms 字数=${text.length}\n---\n$text")
            runOnUiThread { finish() }
        }
    }

    private fun write(text: String) {
        try {
            File(filesDir, "probe_screen.txt").writeText(text)
        } catch (e: Exception) {
            Log.w("ProbeOcr", "写文件失败", e)
        }
    }
}
