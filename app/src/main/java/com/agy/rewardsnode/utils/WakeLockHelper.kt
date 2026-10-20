package com.agy.rewardsnode.utils

import android.content.Context
import android.os.PowerManager

/**
 * Quản trị WakeLock có thời hạn, đảm bảo nhả lock an toàn (try/finally) để không hao pin.
 */
class WakeLockHelper(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(tag: String, timeoutMs: Long = 3 * 60 * 1000L) { // Tối đa 3 phút cho 1 cụm tìm kiếm
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RewardsNode::$tag")
            wakeLock?.setReferenceCounted(false)
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(timeoutMs)
        }
    }

    @Synchronized
    fun release() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
