package com.bellnote.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.net.UpdateChecker
import java.util.concurrent.Executors

/**
 * 版本相关的三件事都在这里：
 *   1. 每次启动检查有没有新版，有就弹提示（带简略说明）；
 *   2. 刚更新完（版本号变了）那次启动，弹一次更新日志；
 *   3. 设置页里「检查更新」的手动检查。
 *
 * 原则：检查失败一律安静（不弹错误、不阻塞启动）；提示弹过一次、用户点「以后再说」就不再骚扰。
 */
object UpdateCenter {

    private val io = Executors.newSingleThreadExecutor()

    /** 同一时间只允许一个版本弹窗，避免启动时两个框叠在一起。 */
    private var dialogShowing = false

    fun onAppStart(activity: Activity) {
        maybeShowChangelog(activity)
        checkInBackground(activity) { info ->
            if (info != null) maybePromptNewVersion(activity, info)
        }
    }

    /**
     * 刚更新完：上次运行的版本号和这次不一样。
     * 第一次安装（上次是 0）不弹，只有真的「从旧版升上来」才弹。
     */
    private fun maybeShowChangelog(activity: Activity) {
        val current = UpdateChecker.currentVersionCode(activity)
        val last = Prefs.runVersionCode(activity)
        Prefs.setRunVersionCode(activity, current)
        if (last == 0 || current <= last) return

        val versionName = UpdateChecker.currentVersionName(activity)
        val changelog = UpdateChecker.bundledChangelog(activity)
        var body = UpdateChecker.sectionOf(changelog, versionName)
        if (body.isBlank()) return
        body = prettify(body)

        if (activity.isFinishing || dialogShowing) return
        dialogShowing = true
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.version_updated_title, versionName))
            .setMessage(body)
            .setPositiveButton(R.string.version_updated_ok, null)
            .setOnDismissListener { dialogShowing = false }
            .show()
    }

    private fun maybePromptNewVersion(activity: Activity, info: UpdateChecker.Info) {
        if (activity.isFinishing || dialogShowing) return
        val current = UpdateChecker.currentVersionCode(activity)
        if (info.versionCode <= current) return
        if (Prefs.updateDismissedCode(activity) == info.versionCode) return

        val notes = prettify(info.notes).ifBlank {
            activity.getString(R.string.version_new_no_notes)
        }
        val message = activity.getString(
            R.string.version_new_message,
            info.versionName,
            UpdateChecker.currentVersionName(activity),
            notes,
        )
        dialogShowing = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.version_new_title)
            .setMessage(message)
            .setPositiveButton(R.string.version_go_download) { _, _ ->
                openDownload(activity, info.downloadUrl)
            }
            .setNegativeButton(R.string.version_later) { _, _ ->
                Prefs.setUpdateDismissedCode(activity, info.versionCode)
            }
            .setOnDismissListener { dialogShowing = false }
            .show()
    }

    /** 设置页里手动点「检查更新」：有结果给一句 toast，没结果也说一声（用户是主动点的，别静默）。 */
    fun manualCheck(activity: Activity, onResult: (String) -> Unit) {
        onResult(activity.getString(R.string.version_checking))
        io.execute {
            val info = UpdateChecker.fetch(activity)
            activity.runOnUiThread {
                when {
                    info == null -> onResult(activity.getString(R.string.version_check_failed))
                    info.versionCode <= UpdateChecker.currentVersionCode(activity) ->
                        onResult(activity.getString(R.string.version_is_latest, UpdateChecker.currentVersionName(activity)))
                    else -> {
                        Prefs.setUpdateResult(
                            activity, info.versionCode, info.versionName, info.notes, info.downloadUrl,
                        )
                        onResult("")
                        maybePromptNewVersion(activity, info)
                    }
                }
            }
        }
    }

    private fun checkInBackground(activity: Activity, onDone: (UpdateChecker.Info?) -> Unit) {
        io.execute {
            val info = UpdateChecker.fetch(activity)
            if (info != null) {
                Prefs.setUpdateResult(
                    activity, info.versionCode, info.versionName, info.notes, info.downloadUrl,
                )
            }
            activity.runOnUiThread { onDone(info) }
        }
    }

    private fun openDownload(activity: Activity, url: String) {
        val target = url.ifBlank { "https://gitee.com/Mentos0111/bellnote/releases/download/latest/bellnote-latest.apk" }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
    }

    /** 见 UpdateChecker.prettify：纯字符串逻辑放在那边，方便单测。 */
    private fun prettify(markdown: String): String = UpdateChecker.prettify(markdown)
}

