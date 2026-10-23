package com.agy.rewardsnode.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Quản trị WakeLock & WifiLock có thời hạn, đảm bảo giữ CPU và WiFi hoạt động xuyên suốt
 * cụm tìm kiếm (kể cả khoảng nghỉ jitter giữa các lượt) và nhả lock an toàn khi Cooldown/Stop.
 */
class WakeLockHelper(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val isHeld: Boolean
        @Synchronized get() = wakeLock?.isHeld == true

    @Synchronized
    fun acquire(tag: String = "ActiveBatch", timeoutMs: Long = 5 * 60 * 1000L) {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "RewardsNode::SearchWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            // Luôn gọi acquire(timeoutMs) để gia hạn lease 5 phút trên mỗi lượt tìm kiếm
            wakeLock?.acquire(timeoutMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (wifiLock == null) {
                val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(wifiMode, "RewardsNode::SearchWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        if (wifiLock?.isHeld == true) {
            try {
                wifiLock?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isWifiConnected(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }

    fun isBatterySafe(minPercent: Int = 15): Boolean {
        return try {
            val batteryStatus = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return true
            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (isCharging) return true

            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return true
            val batteryPct = (level * 100) / scale
            batteryPct >= minPercent
        } catch (_: Exception) {
            true
        }
    }
}
