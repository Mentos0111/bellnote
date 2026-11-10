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
 * 注意：进程被系统冻结时收不到回调；App 每次启动会补查最近一张，
 * 所以漏掉的截图在下次打开 App 时仍会提醒（只提醒 15 分钟内的）。
 */
object ScreenshotWatcher {

    private const val TAG = "ScreenshotWatcher"
    private const val CHANNEL_ID = "screenshot_watch"
    private const val NOTIFY_ID = 4101
    private const val FRESH_WINDOW_MS = 15 * 60_000L

    private var registered = false

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
        if (shot.id <= Prefs.lastShotId(context)) return
        Prefs.setLastShotId(context, shot.id)
        if (shot.notifiedAt - shot.addedAt > FRESH_WINDOW_MS) return
        notify(context, shot)
    }

    private data class Shot(
        val id: Long,
        val uri: Uri,
        val addedAt: Long,
        val notifiedAt: Long,
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
                while (cursor.moveToNext() && checked < 8 && found == null) {
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
                        notifiedAt = System.currentTimeMillis(),
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

    private fun notify(context: Context, shot: Shot) {
        if (!Permissions.hasPostNotifications(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            NOTIFY_ID,
            ImportActivity.intentForScreenshot(context, shot.uri, auto = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("识别这张截图？")
            .setContentText("点一下，把截图里的安排变成日历待办")
            .setStyle(NotificationCompat.BigTextStyle().bigText("点一下，把截图里的安排变成日历待办（${shot.name}）"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "没有通知权限，跳过截图提醒", e)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "截图建待办",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "截完图后提醒你把它识别成待办"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
