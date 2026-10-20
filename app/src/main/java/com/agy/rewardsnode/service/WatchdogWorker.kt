package com.agy.rewardsnode.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agy.rewardsnode.data.EncryptedPreferences

/**
 * Worker chạy định kỳ để giám sát và phục hồi RewardsForegroundService nếu bị hệ điều hành tắt ngầm.
 */
class WatchdogWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = EncryptedPreferences(context)
        if (prefs.isNodeServiceRunning) {
            val intent = Intent(context, RewardsForegroundService::class.java).apply {
                action = RewardsForegroundService.ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Trên Android 12+, nếu app đang chạy ngầm hoàn toàn và không được miễn trừ, có thể ném ForegroundServiceStartNotAllowedException
                e.printStackTrace()
            }
        }
        return Result.success()
    }
}
