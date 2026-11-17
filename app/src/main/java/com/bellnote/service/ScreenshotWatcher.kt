package com.bellnote.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bellnote.App
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.ui.ImportActivity
import com.bellnote.util.Permissions
import com.bellnote.util.ScreenshotRules

/**
 * 截图监听：手机里新出现一张截图时，弹一条「识别这张截图？」的通知，
 * 点一下直接进识别页（带图，自动开始识别）。
 *
 * 进程被 realme 冻结/杀掉时收不到回调，所以分三层兜底：
 * ① 常驻的 [ScreenCaptureService]（无障碍服务）连上时就注册 observer，进程不容易被收走；
 * ② 每次进程起来（App.onCreate）注册并补查一次；
 * ③ 回到首页（MainActivity.onResume）再补查一次。
 * 补查认「4 小时内、最近 30 张图里的截图」，冻结期间或隔着照片拍的截图都不会漏。
 */
object ScreenshotWatcher {

    private const val TAG = "ScreenshotWatcher"
    // 通道建好后重要性改不了，所以「默认弹窗」只能用新 id 重建：v1 是 IMPORTANCE_DEFAULT（不弹）
    private const val CHANNEL_ID = "screenshot_watch_v2"
    private const val LEGACY_CHANNEL_ID = "screenshot_watch"
    private const val NOTIFY_ID = 4101
    private const val REQ_OPEN = 4102
    // 单独成组、只让子通知自己弹：否则 App 里有别的通知时，系统会弹「1 条通知」的组摘要，
    // 看不到「识别这张截图？」和按钮（2026-11-17 真机实测）。
    private const val GROUP_SCREENSHOT = "bellnote_screenshot"
    /**
     * 补查认多新的截图：4 小时。
     * 原来只认 15 分钟，手机被冻结半天/在别的 App 里截完很久才回首页，就一律不提醒了。
     */
    const val FRESH_WINDOW_MS = 4 * 60 * 60_000L
    /**
     * 往回翻多少张图找截图：30。
     * 原来只翻 8 张，中间夹了几张相机/微信图片就把截图挡在窗口外（2026-11-17 真机复现）。
     */
    const val SCAN_DEPTH = 30

    private var registered = false

    /** 这张截图该不该弹：比上次提醒过的新，而且在新鲜窗口内。纯函数，已单测。 */
    fun shouldNotify(id: Long, lastShotId: Long, addedAt: Long, now: Long): Boolean {
        if (id <= lastShotId) return false
        if (addedAt <= 0L || addedAt > now) return false
        return now - addedAt <= FRESH_WINDOW_MS
    }


    /** 在有相册权限的前提下开始监听；重复调用无副作用。 */
    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        if (registered) return
        if (!Prefs.shotWatchEnabled(app)) return
        if (!Permissions.hasMediaImages(app)) return
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    checkLatest(app)
                }
            }
            app.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            registered = true
            // 进程刚起来时补查一次：冻结期间截的图别漏掉
            checkLatest(app)
        } catch (e: Exception) {
            Log.w(TAG, "注册截图监听失败", e)
        }
    }

    /** 查最近一张截图，比上次提醒过的新就发通知。 */
    fun checkLatest(context: Context) {
        if (!Prefs.shotWatchEnabled(context)) return
        if (!Permissions.hasMediaImages(context)) return
        val shot = latestScreenshot(context) ?: return
        val now = System.currentTimeMillis()
        if (!shouldNotify(shot.id, Prefs.lastShotId(context), shot.addedAt, now)) return
        Prefs.setLastShotId(context, shot.id)
        notify(context, shot)
    }

    private data class Shot(
        val id: Long,
        val uri: Uri,
        val addedAt: Long,
        val name: String
    )

    private fun latestScreenshot(context: Context): Shot? {
        val idCol = MediaStore.Images.Media._ID
        val nameCol = MediaStore.Images.Media.DISPLAY_NAME
        val addedCol = MediaStore.Images.Media.DATE_ADDED
        val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else null
        val projection = buildList {
            add(idCol); add(nameCol); add(addedCol)
            if (pathCol != null) add(pathCol)
        }.toTypedArray()
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "$idCol DESC"
            )?.use { cursor ->
                var checked = 0
                var found: Shot? = null
                while (cursor.moveToNext() && checked < SCAN_DEPTH && found == null) {
                    checked++
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(nameCol))
                    val path = if (pathCol != null) cursor.getString(cursor.getColumnIndexOrThrow(pathCol)) else null
                    if (!ScreenshotRules.looksLikeScreenshot(name, path)) continue
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(idCol))
                    val addedAt = cursor.getLong(cursor.getColumnIndexOrThrow(addedCol)) * 1000L
                    found = Shot(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        addedAt = addedAt,
                        name = name.orEmpty()
                    )
                }
                found
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询最新截图失败", e)
            null
        }
    }

    /** 进程启动时先把通道建好（高重要性 + 清掉旧的），不用等第一张截图。 */
    fun prepareChannel(context: Context) = ensureChannel(context.applicationContext)

    private fun notify(context: Context, shot: Shot) {
        if (!Permissions.hasPostNotifications(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            REQ_OPEN,
            ImportActivity.intentForScreenshot(context, shot.uri, auto = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("识别这张截图？")
            .setContentText("点一下，把截图里的安排变成日历待办")
            .setStyle(NotificationCompat.BigTextStyle().bigText("点一下，把截图里的安排变成日历待办（${shot.name}）"))
            // 弹窗提醒：高优先级 + 提醒类，系统才会把它做成悬浮横幅
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(GROUP_SCREENSHOT)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(open)
            // 快捷入口：不用点通知本体，直接按这个按钮进截图识别
            .addAction(R.drawable.ic_notif_recognize, "识别这张", open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "没有通知权限，跳过截图提醒", e)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // 旧通道是默认重要性（不弹窗），建好后改不了，只能弃用；顺手删掉，免得设置里出现两个同名通道
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "截图建待办",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "截完图后弹窗提醒你把它识别成待办"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }
}
