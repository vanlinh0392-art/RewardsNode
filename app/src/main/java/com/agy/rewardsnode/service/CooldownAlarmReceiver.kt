package com.agy.rewardsnode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agy.rewardsnode.core.VlogManager
import com.agy.rewardsnode.data.EncryptedPreferences

/**
 * BroadcastReceiver đón tín hiệu báo thức từ AlarmManager sau chu kỳ Cooldown Deep Sleep (>= 900s).
 * Cho phép thiết bị Xiaomi HyperOS / Android 14 giải phóng 100% WakeLock và đưa CPU vào Deep Sleep,
 * sau đó tự động thức giấc đúng giờ để đánh thức RewardsForegroundService tiếp tục chu kỳ cày điểm.
 */
class CooldownAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == ACTION_COOLDOWN_WAKEUP) {
            val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
                ?: intent.getStringExtra(RewardsForegroundService.EXTRA_TARGET_ACCOUNT_ID)
            val prefs = EncryptedPreferences(context)
            val vlogManager = VlogManager.getInstance(context)

            vlogManager.logCooldown(
                "[ALARM-WAKEUP] Nhận tín hiệu thức giấc từ AlarmManager sau chu kỳ Deep Sleep",
                "Đánh thức RewardsForegroundService (Account ID: ${accountId ?: "All"})"
            )

            val isDailyReset = intent.getBooleanExtra("is_daily_reset", false)
            if (prefs.isNodeServiceRunning || (isDailyReset && prefs.isAutoDailySchedule)) {
                val targetAction = if (isDailyReset) RewardsForegroundService.ACTION_START else RewardsForegroundService.ACTION_ALARM_WAKEUP
                prefs.isNodeServiceRunning = true
                if (isDailyReset) {
                    RewardsForegroundService.scheduleDailyAlarm(context)
                }
                val serviceIntent = Intent(context, RewardsForegroundService::class.java).apply {
                    this.action = targetAction
                    putExtra("is_scheduled_check", isDailyReset)
                    if (!accountId.isNullOrBlank()) {
                        putExtra(EXTRA_ACCOUNT_ID, accountId)
                        putExtra(RewardsForegroundService.EXTRA_TARGET_ACCOUNT_ID, accountId)
                    }
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    if (isDailyReset) {
                        vlogManager.logSystem("🌅 Đã tự động đánh thức Node chạy chu kỳ ngày mới theo lịch hẹn!")
                    }
                } catch (e: Exception) {
                    vlogManager.logError("Lỗi khởi động RewardsForegroundService từ CooldownAlarmReceiver", e.message)
                }
            }
        }
    }

    companion object {
        const val ACTION_COOLDOWN_WAKEUP = "com.agy.rewardsnode.ACTION_COOLDOWN_WAKEUP"
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
    }
}
