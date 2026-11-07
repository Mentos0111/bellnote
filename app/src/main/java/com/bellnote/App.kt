package com.bellnote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_EVENTS,
                "ntfy 待办日历",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "ntfy 通知写入日历的结果反馈"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_EVENTS = "ntfy_calendar_events"
    }
}
