package com.bellnote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.ui.MainActivity
import android.util.Log

/**
 * 「秒级」后台接收：常驻前台服务，60 秒拉一次 ntfy，收到就入库。
 *
 * 为什么用前台服务：JobScheduler 在国产 ROM 上会被无限期延后，精确闹钟最短也就 10 分钟级。
 * 代价是通知栏常驻一条低优先级通知（不响不震），这是 Android 的硬规定，躲不开。
 */
class NtfyReceiveService : Service() {

    private var worker: Thread? = null

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.ntfyEnabled(this) || Prefs.ntfyTopic(this).isBlank()) {
            // 开关关了/主题清空了：别留一个空转的常驻通知
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            // Android 12+ 后台起前台服务会抛 ForegroundServiceStartNotAllowedException，
            // 或者通知权限被收回时某些 ROM 直接抛；这种情况下安静退出，等下次进 App 再起
            Log.w(TAG, "上线前台服务失败", e)
            stopSelf()
            return START_NOT_STICKY
        }
        isAlive = true
        if (worker == null) {
            running = true
            worker = Thread({ pollLoop() }, "bellnote-ntfy-bg").also { it.start() }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun pollLoop() {
        while (running) {
            try {
                if (Prefs.ntfyEnabled(this) && Prefs.ntfyTopic(this).isNotBlank()) {
                    val result = NtfyFetcher.catchUp(this)
                    Log.i(TAG, "常驻补查：${result.summary()}")
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "常驻补查失败", e)
            }
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(this, 0, intent, flags)
        val topic = Prefs.ntfyTopic(this).ifBlank { "（还没填主题）" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Bellnote 正在接收待办")
            .setContentText("主题 $topic · 每分钟检查一次")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台接收 ntfy",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "开启「秒级」后台接收时显示的常驻通知，不响铃、不亮屏"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        isAlive = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App 时，服务应该继续跑（这正是它存在的意义）
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "NtfyReceiveService"
        private const val CHANNEL_ID = "ntfy_background"
        private const val NOTIFICATION_ID = 1004
        private const val INTERVAL_MS = 60 * 1000L

        @Volatile
        var isAlive: Boolean = false
    }
}
