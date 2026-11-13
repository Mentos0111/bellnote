package com.bellnote.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.bellnote.data.Prefs
import java.io.File

/**
 * 本地模型的下载与状态。
 *
 * 模型不进 APK（最小的 290 MB、最大的 657 MB），用时再下：
 * - 用系统 DownloadManager 下到 App 自己的外部目录 `files/models/`，通知栏能看到进度；
 * - 下载任务 id 存在 Prefs 里，页面重新进来能接上进度；
 * - 换模型会取消/清理上一个没下完的包，避免几个半截文件占着几百兆。
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    sealed class Status {
        data object Missing : Status()
        data class Downloading(val percent: Int, val bytes: Long) : Status()
        data object Ready : Status()
        data class Failed(val reason: String) : Status()
    }

    fun isDownloading(context: Context): Boolean = downloading(context) != null

    fun status(context: Context): Status {
        val model = LocalModels.selected(context)
        if (LocalModels.isReady(context, model)) return Status.Ready
        downloading(context)?.let { return Status.Downloading(it.first, it.second) }
        val file = LocalModels.file(context, model)
        if (file.isFile && file.length() > 0) {
            return Status.Failed(
                "上次没下完（${file.length() / 1024 / 1024} MB / ${model.sizeMb} MB）"
            )
        }
        return Status.Missing
    }

    /** 开始下载当前选中的模型；已经有任务就返回 false。 */
    fun enqueue(context: Context): Boolean {
        val model = LocalModels.selected(context)
        if (LocalModels.isReady(context, model) || isDownloading(context)) return false
        val dir = File(context.getExternalFilesDir(null), LocalLlm.MODEL_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "建模型目录失败：${dir.absolutePath}")
            return false
        }
        // 选中的模型残包先删，否则 DownloadManager 会因为目标文件已存在而失败
        LocalModels.file(context, model).takeIf { it.exists() }?.delete()

        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Bellnote 本地模型：${model.short}")
            .setDescription("${model.sizeMb} MB，下载一次就在手机上，之后断网也能识别")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, LocalLlm.MODEL_DIR, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        return try {
            val manager = context.getSystemService(DownloadManager::class.java) ?: return false
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
        if (id > 0L) {
            runCatching { context.getSystemService(DownloadManager::class.java)?.remove(id) }
        }
        Prefs.setModelDownloadId(context, -1L)
        val model = LocalModels.selected(context)
        val file = LocalModels.file(context, model)
        if (file.exists() && !LocalModels.isReady(context, model)) file.delete()
    }

    /** 删除当前选中的模型文件（释放空间）。 */
    fun deleteSelected(context: Context) {
        LocalLlm.release()
        LocalModels.file(context, LocalModels.selected(context)).delete()
    }

    /** 下载中返回（百分比, 已下载字节），否则 null。 */
    private fun downloading(context: Context): Pair<Int, Long>? {
        val id = Prefs.modelDownloadId(context)
        if (id <= 0L) return null
        val manager = context.getSystemService(DownloadManager::class.java) ?: return null
        val total = LocalModels.selected(context).bytes
        return try {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return null
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                val reported = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                ).takeIf { it > 0 } ?: total
                when (status) {
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_PAUSED ->
                        Pair((done * 100 / reported.coerceAtLeast(1)).toInt(), done)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
