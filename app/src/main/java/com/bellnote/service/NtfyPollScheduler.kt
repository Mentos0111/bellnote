package com.bellnote.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.bellnote.data.Prefs

/**
 * 给「定时拉 ntfy」排班：填了主题就每 15 分钟拉一次（系统允许的最短周期），
 * 没填就取消，保证不配 ntfy 的人完全不联网。
 */
object NtfyPollScheduler {

    private const val TAG = "NtfyPollScheduler"
    private const val JOB_ID = 4201
    private const val INTERVAL_MS = 15 * 60 * 1000L

    fun sync(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return

        if (!Prefs.ntfyEnabled(app) || Prefs.ntfyTopic(app).isBlank()) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return

        val job = JobInfo.Builder(JOB_ID, ComponentName(app, NtfyPollJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(INTERVAL_MS)
            .setPersisted(true)
            .build()
        val result = scheduler.schedule(job)
        Log.i(TAG, if (result == JobScheduler.RESULT_SUCCESS) "定时补查已排班" else "定时补查排班失败：$result")
    }

    /** 改了主题 / 关掉 ntfy 时立刻生效。 */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        sync(app)
    }
}
