package com.bellnote.util

import android.app.Activity
import android.view.View
import androidx.appcompat.app.AlertDialog

/**
 * 功能说明统一收进控件旁边那个「?」里，不要再往页面里塞大段说明文字。
 *
 * 用法：布局里放一个 style="@style/SettingsHelpButton" 的 ImageButton，
 * 页面 onCreate 里 Help.attach(this, R.id.btnHelpXxx, "标题", "说明")。
 */
object Help {

    fun attach(activity: Activity, viewId: Int, title: String, message: String) {
        activity.findViewById<View>(viewId).setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        }
    }
}
