package com.agy.rewardsnode.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Quản trị cấu hình ứng dụng được mã hóa phần cứng (AES-256-GCM) qua Android KeyStore native.
 * 100% Native Android SDK, không phụ thuộc thư viện ngoài, bảo mật Zero-Trust.
 */
class EncryptedPreferences(context: Context) {

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("rewards_node_secure_prefs", Context.MODE_PRIVATE)
    private val keyAlias = "RewardsNodeMasterKey"

    init {
        ensureKeyStoreKey()
    }

    private fun ensureKeyStoreKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.getKey(keyAlias, null) as? SecretKey
        } catch (e: Exception) {
            null
        }
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""
        val secretKey = getSecretKey() ?: return plainText
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            plainText
        }
    }

    private fun decrypt(cipherText: String): String {
        if (cipherText.isBlank()) return ""
        val secretKey = getSecretKey() ?: return cipherText
        return try {
            val combined = Base64.decode(cipherText, Base64.NO_WRAP)
            if (combined.size < 12) return cipherText
            val iv = ByteArray(12)
            val encryptedBytes = ByteArray(combined.size - 12)
            System.arraycopy(combined, 0, iv, 0, 12)
            System.arraycopy(combined, 12, encryptedBytes, 0, encryptedBytes.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            cipherText // Fallback nếu dữ liệu lưu dạng plain trước đó
        }
    }

    var cooldownMinutes: Int
        get() = sharedPreferences.getInt(KEY_COOLDOWN_MINUTES, 18)
        set(value) = sharedPreferences.edit().putInt(KEY_COOLDOWN_MINUTES, value).apply()

    var cooldownMin: Int
        get() = sharedPreferences.getInt(KEY_COOLDOWN_MIN, 15)
        set(value) = sharedPreferences.edit().putInt(KEY_COOLDOWN_MIN, value).apply()

    var cooldownMax: Int
        get() = sharedPreferences.getInt(KEY_COOLDOWN_MAX, 22)
        set(value) = sharedPreferences.edit().putInt(KEY_COOLDOWN_MAX, value).apply()

    var globalProxy: String
        get() = decrypt(sharedPreferences.getString(KEY_GLOBAL_PROXY, "") ?: "")
        set(value) = sharedPreferences.edit().putString(KEY_GLOBAL_PROXY, encrypt(value)).apply()

    var userAgent: String
        get() = sharedPreferences.getString(KEY_USER_AGENT, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_USER_AGENT, value).apply()

    var isDarkMode: Boolean
        get() = sharedPreferences.getBoolean(KEY_DARK_MODE, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_DARK_MODE, value).apply()

    var batchSize: Int
        get() = sharedPreferences.getInt(KEY_BATCH_SIZE, 3)
        set(value) = sharedPreferences.edit().putInt(KEY_BATCH_SIZE, value).apply()

    var defaultTargetSearches: Int
        get() = sharedPreferences.getInt(KEY_DEFAULT_TARGET_SEARCHES, 35).coerceIn(10, 60)
        set(value) = sharedPreferences.edit().putInt(KEY_DEFAULT_TARGET_SEARCHES, value.coerceIn(10, 60)).apply()

    var telegramBotToken: String
        get() = decrypt(sharedPreferences.getString(KEY_TELEGRAM_TOKEN, "") ?: "")
        set(value) = sharedPreferences.edit().putString(KEY_TELEGRAM_TOKEN, encrypt(value)).apply()

    var telegramChatId: String
        get() = decrypt(sharedPreferences.getString(KEY_TELEGRAM_CHAT_ID, "") ?: "")
        set(value) = sharedPreferences.edit().putString(KEY_TELEGRAM_CHAT_ID, encrypt(value)).apply()

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
        private const val KEY_COOLDOWN_MIN = "cooldown_min"
        private const val KEY_COOLDOWN_MAX = "cooldown_max"
        private const val KEY_GLOBAL_PROXY = "global_proxy_enc"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_DEFAULT_TARGET_SEARCHES = "default_target_searches"
        private const val KEY_TELEGRAM_TOKEN = "telegram_token_enc"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id_enc"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_BATTERY_GUARD = "battery_guard"
        private const val KEY_NODE_RUNNING = "node_running"
    }
}
