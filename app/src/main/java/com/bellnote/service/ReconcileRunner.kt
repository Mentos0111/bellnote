package com.bellnote.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bellnote.App
import com.bellnote.R
import com.bellnote.data.Prefs
import com.bellnote.data.ReconcileReport
import com.bellnote.data.Reconciler
import com.bellnote.ui.ReconcileActivity
import com.bellnote.util.Permissions

/** 跑一次对账 + 记下时间 + 有差异时发通知。定时任务和手动按钮共用。 */
object ReconcileRunner {

    private const val TAG = "ReconcileRunner"
    private const val NOTIFY_ID = 1002

    fun check(context: Context, notify: Boolean = false): ReconcileReport {
        val app = context.applicationContext
        val report = Reconciler.run(app)
        Prefs.setLastReconcileAt(app, report.checkedAt)
        Prefs.setLastReconcileSummary(app, report.summary())
        if (!report.consistent && notify) notifyDiff(app, report)
        return report
    }

    private fun notifyDiff(context: Context, report: ReconcileReport) {
        if (!Permissions.hasPostNotifications(context)) return
        val intent = Intent(context, ReconcileActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lines = report.diffs.take(5).joinToString("\n") { diff ->
            "〔${diff.kind.badge}〕${diff.title}　${diff.detail}"
        }
        val body = buildString {
            append("清单 ${report.localCount} 条 · 日历 ${report.calendarCount} 条")
            append("\n")
            append(lines)
            val rest = report.diffs.size - 5
            if (rest > 0) append("\n…还有 $rest 条，点开看全部")
            append("\n点这里去补齐或清理")
        }
        val notification = NotificationCompat.Builder(context, App.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("日历和待办清单对不上：${report.summary()}")
            .setContentText(report.summary())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发对账通知失败", e)
        }
    }
}
