package com.bellnote.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * 定时从 ntfy 服务器拉取消息（不再依赖通知使用权）。
 *
 * 为什么改成主动拉：读别人 App 的通知要「通知使用权」这个敏感权限，上架审核过不去，
 * 而且 ntfy App 被冻、被杀、断网时通知根本不来 —— 主动拉一次能同时解决合规和丢消息。
 * 走的是和原来完全相同的 [TodoIngestor] 入库路径，规则（严格模式/删除/重复）不变。
 */
class NtfyPollJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                val result = NtfyFetcher.catchUp(this)
                Log.i(TAG, "定时补查：${result.summary()}")
            } catch (e: Exception) {
                Log.w(TAG, "定时补查失败", e)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "NtfyPollJob"
    }
}
