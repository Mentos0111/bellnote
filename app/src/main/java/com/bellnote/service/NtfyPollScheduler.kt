package com.bellnote.service

import android.content.Context

/**
 * 兼容层：以前所有调用点都走这里排「15 分钟 JobScheduler」。
 *
 * 现在后台接收有三种模式（[com.bellnote.data.Prefs.ntfyBackgroundMode]），真正的
 * 调度逻辑挪到了 [NtfyBackground]；这里保留同样的入口，免得把老调用点全改一遍。
 */
object NtfyPollScheduler {

    /** 按当前模式把后台接收对齐（App 启动、改设置、开机后都调）。 */
    fun sync(context: Context) {
        NtfyBackground.sync(context)
    }

    /** 改了主题 / 开关后立刻生效。 */
    fun reschedule(context: Context) {
        NtfyBackground.reschedule(context)
    }
}
