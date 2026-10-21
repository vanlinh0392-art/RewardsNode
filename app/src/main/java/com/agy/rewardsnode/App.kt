package com.agy.rewardsnode

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Rewards Node Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kênh thông báo trạng thái hoạt động của Rewards Node chạy ngầm"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "rewards_node_service_channel"
        const val NOTIFICATION_ID = 1001
    }
}
