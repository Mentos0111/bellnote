package com.bellnote.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
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
        val body = UpdateChecker.prettify(
            UpdateChecker.sectionOf(UpdateChecker.bundledChangelog(activity), versionName)
        )
        if (body.isBlank()) return

        if (activity.isFinishing || dialogShowing) return
        dialogShowing = true
        showVersionDialog(
            activity = activity,
            title = activity.getString(R.string.version_updated_title, versionName),
            subtitle = activity.getString(R.string.version_updated_subtitle),
            body = body,
            primaryText = activity.getString(R.string.version_updated_ok),
            primaryAction = { },
            secondaryText = null,
            secondaryAction = null,
        )
    }

    private fun maybePromptNewVersion(activity: Activity, info: UpdateChecker.Info) {
        if (activity.isFinishing || dialogShowing) return
        val current = UpdateChecker.currentVersionCode(activity)
        if (info.versionCode <= current) return
        if (Prefs.updateDismissedCode(activity) == info.versionCode) return

        val body = UpdateChecker.prettify(info.notes).ifBlank {
            activity.getString(R.string.version_new_no_notes)
        }
        dialogShowing = true
        showVersionDialog(
            activity = activity,
            title = activity.getString(R.string.version_new_title),
            subtitle = activity.getString(
                R.string.version_new_subtitle,
                UpdateChecker.currentVersionName(activity),
                info.versionName,
            ),
            body = body,
            primaryText = activity.getString(R.string.version_go_download),
            primaryAction = { openDownload(activity, info.downloadUrl) },
            secondaryText = activity.getString(R.string.version_later),
            secondaryAction = { Prefs.setUpdateDismissedCode(activity, info.versionCode) },
        )
    }

    /**
     * 版本弹窗的统一外观：圆角卡片、版本号一行、可滚动的改动说明、右下两颗按钮。
     * 说明太长时正文最高约占屏幕 38%，超出就滚动，弹窗不会顶到屏幕外。
     */
    private fun showVersionDialog(
        activity: Activity,
        title: String,
        subtitle: String,
        body: String,
        primaryText: String,
        primaryAction: () -> Unit,
        secondaryText: String?,
        secondaryAction: (() -> Unit)?,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = title
        view.findViewById<TextView>(R.id.tvDialogSubtitle).text = subtitle
        view.findViewById<TextView>(R.id.tvDialogBody).text = body
        val scroll = view.findViewById<ScrollView>(R.id.svDialogBody)
        val primary = view.findViewById<TextView>(R.id.btnDialogPrimary)
        val secondary = view.findViewById<TextView>(R.id.btnDialogSecondary)

        primary.text = primaryText
        if (secondaryText.isNullOrBlank()) {
            secondary.visibility = View.GONE
        } else {
            secondary.text = secondaryText
            secondary.setOnClickListener {
                secondaryAction?.invoke()
                dialogRef?.dismiss()
            }
        }

        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialogRef = dialog
        dialog.setOnDismissListener { dialogShowing = false }
        primary.setOnClickListener {
            primaryAction()
            dialog.dismiss()
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            val metrics = activity.resources.displayMetrics
            dialog.window?.setLayout(
                (metrics.widthPixels * 0.86f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val maxBody = (metrics.heightPixels * 0.38f).toInt()
            scroll.post {
                if (scroll.height > maxBody) {
                    val lp = scroll.layoutParams
                    lp.height = maxBody
                    scroll.layoutParams = lp
                }
            }
        }
        dialog.show()
    }

    private var dialogRef: AlertDialog? = null

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
        val target = url.ifBlank {
            "https://gitee.com/Mentos0111/bellnote/releases/download/latest/bellnote-latest.apk"
        }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
    }
}
