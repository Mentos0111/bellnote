package com.bellnote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bellnote.ai.OfflineOcr
import java.io.File

/**
 * 诊断用：adb 发一条广播，把当前所有窗口的文字倒进 files/probe_screen.txt。
 *
 *   adb shell am broadcast -a com.bellnote.PROBE_SCREEN
 *
 * 为什么用它：验证「微信界面到底有没有无障碍节点」时，不改 UI、不切前台，
 * 广播进来直接在进程里 dump，拿到的就是当时前台那个 App 的窗口。
 */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        when (intent?.action) {
            "com.bellnote.PROBE_OCR" -> runOcr(ctx)
            else -> dumpScreen(ctx)
        }
    }

    /** 截当前屏 → 离线 OCR → 落盘，验证「无 GMS 上 ML Kit 能不能跑」。 */
    private fun runOcr(ctx: Context) {
        ScreenCaptureService.capture { bitmap ->
            if (bitmap == null) {
                Log.w("ProbeOcr", "截图失败")
                write(ctx, "OCR 失败：截图没拿到（无障碍没开或系统版本低）")
                return@capture
            }
            val started = System.currentTimeMillis()
            OfflineOcr.recognize(bitmap) { text ->
                val cost = System.currentTimeMillis() - started
                Log.i("ProbeOcr", "完成 ${text.length} 字，${cost}ms")
                write(ctx, "耗时=${cost}ms 字数=${text.length}\n---\n$text")
            }
        }
    }

    private fun dumpScreen(ctx: Context) {
        val text = ScreenCaptureService.dumpAllWindows()
        write(ctx, text)
        Log.i("ProbeScreen", "已导出 ${text.length} 字符")
    }

    private fun write(ctx: Context, text: String) {
        try {
            File(ctx.filesDir, "probe_screen.txt").writeText(text)
        } catch (e: Exception) {
            Log.w("Probe", "写文件失败", e)
        }
    }
}
