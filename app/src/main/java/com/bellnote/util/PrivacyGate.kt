package com.bellnote.util

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.ui.PrivacyActivity

/**
 * 首次启动的隐私同意。
 *
 * 规矩（也是商店检测会看的点）：
 * 1. 必须在任何功能使用前弹，且关闭弹窗不算同意；
 * 2. 「不同意」要能真的退出，不能只关弹窗继续用；
 * 3. 提供查看全文的入口；
 * 4. 同意状态记在本地，之后不再骚扰（设置里可随时回看全文）。
 */
object PrivacyGate {

    private var current: AlertDialog? = null

    private const val SUMMARY =
        "Bellnote 是本地待办工具。待办、设置、API Key 都只存在这台手机里；" +
            "只有你主动点「识别这张图」时，截图会发给你自己配置的 DeepSeek 接口解析；" +
            "可选填 ntfy 主题时，App 每 15 分钟向该服务器拉一次消息。不收集位置、通讯录、设备标识，没有广告和统计 SDK。\n\n" +
            "点「同意」开始使用，点「查看全文」可看完整隐私政策。"

    /** @return true 表示已同意，可以继续；false 表示已弹出弹窗（用户还没决定）。 */
    fun ensure(activity: Activity, onAccepted: (() -> Unit)? = null): Boolean {
        if (Prefs.privacyAccepted(activity)) return true
        // onCreate 和 onResume 会连着调两次，别叠两个弹窗
        if (current?.isShowing == true) return false

        var dialog: AlertDialog? = null
        val builder = AlertDialog.Builder(activity)
            .setTitle("隐私与数据")
            .setMessage(SUMMARY)
            .setCancelable(false)
            .setPositiveButton("同意") { _, _ ->
                Prefs.setPrivacyAccepted(activity, true)
                onAccepted?.invoke()
            }
            .setNeutralButton("查看全文") { _, _ ->
                activity.startActivity(
                    Intent(activity, PrivacyActivity::class.java)
                        .putExtra(PrivacyActivity.EXTRA_GATE, true)
                )
            }
            .setNegativeButton("不同意") { _, _ ->
                activity.finishAffinity()
            }
        dialog = builder.create()
        current = dialog
        dialog.setOnDismissListener {
            if (current === dialog) current = null
        }
        dialog.setOnShowListener {
            // 看一眼全文回来还没同意，就再弹一次（不能靠关掉弹窗混过去）
            activity.window.decorView.postDelayed({
                if (!Prefs.privacyAccepted(activity) && !activity.isFinishing) {
                    dialog?.show()
                }
            }, 100L)
        }
        dialog.show()
        return false
    }

    /** 设置里的「隐私政策」入口。 */
    fun openFullText(activity: Activity) {
        activity.startActivity(Intent(activity, PrivacyActivity::class.java))
    }

    fun bindEntry(activity: Activity, view: View) {
        view.setOnClickListener { openFullText(activity) }
    }
}
