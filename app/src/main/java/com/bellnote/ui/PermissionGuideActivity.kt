package com.bellnote.ui

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.util.Permissions
import com.bellnote.util.SystemPages

/** realme UI 6.0 四项权限的一键跳转引导。 */
class PermissionGuideActivity : AppCompatActivity() {

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        toast(if (result.values.all { it }) "日历权限已授权" else "还要在弹窗里选「允许」")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)
        title = getString(R.string.title_permission)

        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            if (!SystemPages.openNotificationAccess(this)) {
                toast("手动路径：设置→应用→特殊应用权限→通知使用权")
            }
        }
        findViewById<Button>(R.id.btnCalendarPerm).setOnClickListener {
            if (Permissions.hasCalendar(this)) toast("已经有日历权限了")
            else requestCalendar.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener {
            if (!SystemPages.openAutostart(this)) {
                toast("手动路径：设置→应用→自启动管理")
            }
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            if (!SystemPages.openBattery(this)) {
                toast("手动路径：设置→电池→应用耗电管理→允许后台运行")
            }
        }
        findViewById<Button>(R.id.btnAppDetail).setOnClickListener {
            SystemPages.openAppDetails(this)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
