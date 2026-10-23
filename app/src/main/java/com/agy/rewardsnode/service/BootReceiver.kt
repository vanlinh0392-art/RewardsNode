package com.agy.rewardsnode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agy.rewardsnode.data.EncryptedPreferences

/**
 * Tự động kích hoạt lại Node khi điện thoại khởi động lại.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = EncryptedPreferences(context)
            if (prefs.isNodeServiceRunning) {
                val serviceIntent = Intent(context, RewardsForegroundService::class.java).apply {
                    action = RewardsForegroundService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else if (prefs.isAutoDailySchedule) {
                RewardsForegroundService.scheduleDailyAlarm(context)
            }
        }
    }
}
