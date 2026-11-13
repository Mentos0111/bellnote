package com.bellnote.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bellnote.data.Prefs
import java.io.File

/**
 * 离线大模型的下载与状态。
 *
 * 为什么不在 APK 里塞模型：Gemma 3 1B（int4）约 657MB，塞进包会让每次安装都变成一次
 * 半个 G 的下载，绝大多数人用不到。所以走「用时再下」：
 *
 * - 用系统 DownloadManager 下到 App 自己的外部目录 `files/models/`，通知栏能看到进度，
 *   断网/重启后也能续；
 * - 下载任务 id 存在 Prefs 里，页面重新进来能接上进度；
 * - 下完先校验大小再允许加载（残包直接加载会崩）。
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    /** 国内直连更稳的镜像（ModelScope 的 litert-community 镜像）。 */
    const val URL =
        "https://www.modelscope.cn/models/litert-community/Gemma3-1B-IT/resolve/master/" +
            "Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv1280.task"

    /** 期望大小（字节），用来判断「下完了没有」。 */
    const val EXPECTED_BYTES = 689_297_398L

    sealed class Status {
        data object Missing : Status()
        data class Downloading(val percent: Int, val bytes: Long) : Status()
        data object Ready : Status()
        data class Failed(val reason: String) : Status()
    }

    /** 是否已经有下载任务在跑（页面用这个决定按钮文案）。 */
    fun isDownloading(context: Context): Boolean = downloadPercent(context) != null

    fun status(context: Context): Status {
        if (LocalLlm.isReady(context)) return Status.Ready
        val percent = downloadPercent(context)
        if (percent != null) {
            return Status.Downloading(percent.first, percent.second)
        }
        val file = LocalLlm.modelFile(context)
        if (file.isFile && file.length() > 0) {
            // 下了一半的残包：别当已就绪
            return Status.Failed("上次没下完（${file.length() / 1024 / 1024} MB / ${EXPECTED_BYTES / 1024 / 1024} MB）")
        }
        return Status.Missing
    }

    /** 开始下载；已经有任务就返回 false。 */
    fun enqueue(context: Context): Boolean {
        if (LocalLlm.isReady(context) || isDownloading(context)) return false
        val dir = File(context.getExternalFilesDir(null), LocalLlm.MODEL_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "建模型目录失败：${dir.absolutePath}")
            return false
        }
        // 残包先删掉，否则 DownloadManager 会因为目标文件已存在而失败
        val target = LocalLlm.modelFile(context)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Bellnote 离线模型")
            .setDescription("Gemma 3 1B（约 657 MB），只用一次，之后断网也能识别")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, LocalLlm.MODEL_DIR, LocalLlm.MODEL_NAME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        return try {
            val manager = context.getSystemService(DownloadManager::class.java)
                ?: return false
            val id = manager.enqueue(request)
            Prefs.setModelDownloadId(context, id)
            true
        } catch (e: Exception) {
            Log.w(TAG, "发起下载失败", e)
            false
        }
    }

    fun cancel(context: Context) {
        val id = Prefs.modelDownloadId(context)
        if (id <= 0L) return
        runCatching {
            context.getSystemService(DownloadManager::class.java)?.remove(id)
        }
        Prefs.setModelDownloadId(context, -1L)
        val file = LocalLlm.modelFile(context)
        if (file.exists() && !LocalLlm.isReady(context)) file.delete()
    }

    /** 下载中返回（百分比, 已下载字节），否则 null。 */
    private fun downloadPercent(context: Context): Pair<Int, Long>? {
        val id = Prefs.modelDownloadId(context)
        if (id <= 0L) return null
        val manager = context.getSystemService(DownloadManager::class.java) ?: return null
        return try {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return null
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                val done = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                ).takeIf { it > 0 } ?: EXPECTED_BYTES
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> null
                    DownloadManager.STATUS_FAILED -> null
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING -> Pair((done * 100 / total).toInt(), done)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 页面用：每隔一会儿问一次下载进度。返回的 Runnable 记得在 onDestroy 里 remove。
     */
    fun watch(context: Context, intervalMs: Long = 1200, onTick: (Status) -> Unit): Runnable {
        val handler = Handler(Looper.getMainLooper())
        lateinit var runnable: Runnable
        runnable = Runnable {
            onTick(status(context))
            handler.postDelayed(runnable, intervalMs)
        }
        handler.post(runnable)
        return runnable
    }
}