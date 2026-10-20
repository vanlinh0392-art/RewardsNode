package com.agy.rewardsnode.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Quản lý cấu hình ứng dụng được mã hóa phần cứng (AES-256-GCM) qua Android Keystore.
 */
class EncryptedPreferences(context: Context) {

    private val sharedPreferences: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        sharedPreferences = try {
            EncryptedSharedPreferences.create(
                context,
                "rewards_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback sang SharedPreferences thường nếu thiết bị không hỗ trợ Keystore đầy đủ
            context.getSharedPreferences("rewards_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    var cooldownMinutes: Int
        get() = sharedPreferences.getInt(KEY_COOLDOWN_MINUTES, 18)
        set(value) = sharedPreferences.edit().putInt(KEY_COOLDOWN_MINUTES, value).apply()

    var batchSize: Int
        get() = sharedPreferences.getInt(KEY_BATCH_SIZE, 3)
        set(value) = sharedPreferences.edit().putInt(KEY_BATCH_SIZE, value).apply()

    var telegramBotToken: String
        get() = sharedPreferences.getString(KEY_TELEGRAM_TOKEN, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_TELEGRAM_TOKEN, value).apply()

    var telegramChatId: String
        get() = sharedPreferences.getString(KEY_TELEGRAM_CHAT_ID, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_TELEGRAM_CHAT_ID, value).apply()

    var isWifiOnly: Boolean
        get() = sharedPreferences.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var isBatteryGuardEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_BATTERY_GUARD, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_BATTERY_GUARD, value).apply()

    var isNodeServiceRunning: Boolean
        get() = sharedPreferences.getBoolean(KEY_NODE_RUNNING, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_NODE_RUNNING, value).apply()

    companion object {
        private const val KEY_COOLDOWN_MINUTES = "cooldown_minutes"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_TELEGRAM_TOKEN = "telegram_token"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_BATTERY_GUARD = "battery_guard"
        private const val KEY_NODE_RUNNING = "node_running"
    }
}
