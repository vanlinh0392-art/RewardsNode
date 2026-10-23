package com.agy.rewardsnode.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Locale
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
    private var lastOfferPruneDate: String? = null

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
            if (combined.size < 28) return cipherText
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
        get() = sharedPreferences.getInt(KEY_BATCH_SIZE, 3).coerceIn(1, 10)
        set(value) = sharedPreferences.edit().putInt(KEY_BATCH_SIZE, value.coerceIn(1, 10)).apply()

    var defaultTargetSearches: Int
        get() = sharedPreferences.getInt(KEY_DEFAULT_TARGET_SEARCHES, 35).coerceIn(10, 60)
        set(value) = sharedPreferences.edit().putInt(KEY_DEFAULT_TARGET_SEARCHES, value.coerceIn(10, 60)).apply()

    var dailyScheduleHour: Int
        get() = sharedPreferences.getInt(KEY_DAILY_SCHEDULE_HOUR, 5).coerceIn(0, 23)
        set(value) = sharedPreferences.edit().putInt(KEY_DAILY_SCHEDULE_HOUR, value.coerceIn(0, 23)).apply()

    var dailyScheduleMinute: Int
        get() = sharedPreferences.getInt(KEY_DAILY_SCHEDULE_MINUTE, 0).coerceIn(0, 59)
        set(value) = sharedPreferences.edit().putInt(KEY_DAILY_SCHEDULE_MINUTE, value.coerceIn(0, 59)).apply()

    var dailyScheduleSlot1: String
        get() {
            val saved = sharedPreferences.getString(KEY_DAILY_SLOT_1, null)
            return if (!saved.isNullOrBlank()) saved else String.format(Locale.US, "%02d:%02d", dailyScheduleHour, dailyScheduleMinute)
        }
        set(value) {
            val trimmed = value.trim()
            sharedPreferences.edit().putString(KEY_DAILY_SLOT_1, trimmed).apply()
            parseTimeSlot(trimmed)?.let { (h, m) ->
                dailyScheduleHour = h
                dailyScheduleMinute = m
            }
        }

    var dailyScheduleSlot2: String
        get() = sharedPreferences.getString(KEY_DAILY_SLOT_2, "12:30") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_DAILY_SLOT_2, value.trim()).apply()

    var dailyScheduleSlot3: String
        get() = sharedPreferences.getString(KEY_DAILY_SLOT_3, "20:00") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_DAILY_SLOT_3, value.trim()).apply()

    var isScheduleSlot1Enabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DAILY_SLOT_1_ENABLED, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_DAILY_SLOT_1_ENABLED, value).apply()

    var isScheduleSlot2Enabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DAILY_SLOT_2_ENABLED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_DAILY_SLOT_2_ENABLED, value).apply()

    var isScheduleSlot3Enabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DAILY_SLOT_3_ENABLED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_DAILY_SLOT_3_ENABLED, value).apply()

    fun getEnabledScheduleSlots(): List<Pair<Int, Int>> {
        return resolveEnabledScheduleSlots(
            dailyScheduleSlot1,
            isScheduleSlot1Enabled,
            dailyScheduleSlot2,
            isScheduleSlot2Enabled,
            dailyScheduleSlot3,
            isScheduleSlot3Enabled
        )
    }

    var searchDelayMinSec: Int
        get() = sharedPreferences.getInt(KEY_SEARCH_DELAY_MIN_SEC, 18).coerceIn(5, 180)
        set(value) = sharedPreferences.edit().putInt(KEY_SEARCH_DELAY_MIN_SEC, value.coerceIn(5, 180)).apply()

    var searchDelayMaxSec: Int
        get() = sharedPreferences.getInt(KEY_SEARCH_DELAY_MAX_SEC, 45).coerceIn(10, 300)
        set(value) = sharedPreferences.edit().putInt(KEY_SEARCH_DELAY_MAX_SEC, value.coerceIn(10, 300)).apply()

    var maxClaimRetries: Int
        get() = sharedPreferences.getInt(KEY_MAX_CLAIM_RETRIES, 2).coerceIn(1, 10)
        set(value) = sharedPreferences.edit().putInt(KEY_MAX_CLAIM_RETRIES, value.coerceIn(1, 10)).apply()

    var dapiMaxRetries: Int
        get() = sharedPreferences.getInt(KEY_DAPI_MAX_RETRIES, 2).coerceIn(1, 10)
        set(value) = sharedPreferences.edit().putInt(KEY_DAPI_MAX_RETRIES, value.coerceIn(1, 10)).apply()

    var cooldownTriggerBatches: Int
        get() = sharedPreferences.getInt(KEY_COOLDOWN_TRIGGER_BATCHES, 2).coerceIn(1, 6)
        set(value) = sharedPreferences.edit().putInt(KEY_COOLDOWN_TRIGGER_BATCHES, value.coerceIn(1, 6)).apply()

    var frozenStopBatches: Int
        get() = sharedPreferences.getInt(KEY_FROZEN_STOP_BATCHES, 3).coerceIn(2, 10)
        set(value) = sharedPreferences.edit().putInt(KEY_FROZEN_STOP_BATCHES, value.coerceIn(2, 10)).apply()

    var maxReconciliationPasses: Int
        get() = sharedPreferences.getInt(KEY_MAX_RECONCILIATION_PASSES, 2).coerceIn(0, 5)
        set(value) = sharedPreferences.edit().putInt(KEY_MAX_RECONCILIATION_PASSES, value.coerceIn(0, 5)).apply()

    var proxyCircuitThreshold: Int
        get() = sharedPreferences.getInt(KEY_PROXY_CIRCUIT_THRESHOLD, 3).coerceIn(1, 10)
        set(value) = sharedPreferences.edit().putInt(KEY_PROXY_CIRCUIT_THRESHOLD, value.coerceIn(1, 10)).apply()

    var telegramBotToken: String
        get() {
            val decrypted = decrypt(sharedPreferences.getString(KEY_TELEGRAM_TOKEN, "") ?: "")
            return if (decrypted.isNotBlank()) decrypted else DEFAULT_TELEGRAM_BOT_TOKEN
        }
        set(value) = sharedPreferences.edit().putString(KEY_TELEGRAM_TOKEN, encrypt(value)).apply()

    var telegramChatId: String
        get() {
            val decrypted = decrypt(sharedPreferences.getString(KEY_TELEGRAM_CHAT_ID, "") ?: "")
            return if (decrypted.isNotBlank()) decrypted else DEFAULT_TELEGRAM_CHAT_ID
        }
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

    var isAutoDailySchedule: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_DAILY_SCHEDULE, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_AUTO_DAILY_SCHEDULE, value).apply()

    var isAutoStopOnComplete: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_STOP_ON_COMPLETE, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_AUTO_STOP_ON_COMPLETE, value).apply()

    var lastCompletionSummaryDate: String?
        get() = sharedPreferences.getString(KEY_LAST_COMPLETION_SUMMARY_DATE, null)
        set(value) {
            if (value == null) {
                sharedPreferences.edit().remove(KEY_LAST_COMPLETION_SUMMARY_DATE).apply()
            } else {
                sharedPreferences.edit().putString(KEY_LAST_COMPLETION_SUMMARY_DATE, value).apply()
            }
        }

    @Synchronized
    fun isSearchExhaustedToday(accountId: String, todayStr: String): Boolean {
        if (accountId.isBlank() || todayStr.isBlank()) return false
        return sharedPreferences.getString("$KEY_PREFIX_SEARCH_EXHAUSTED$accountId", null) == todayStr
    }

    @Synchronized
    fun markSearchExhaustedToday(accountId: String, todayStr: String) {
        if (accountId.isBlank() || todayStr.isBlank()) return
        sharedPreferences.edit().putString("$KEY_PREFIX_SEARCH_EXHAUSTED$accountId", todayStr).apply()
    }

    @Synchronized
    fun getDapiAttemptsToday(accountId: String, todayStr: String): Int {
        if (accountId.isBlank() || todayStr.isBlank()) return 0
        val raw = sharedPreferences.getString("$KEY_PREFIX_DAPI_ATTEMPTS$accountId", null) ?: return 0
        val prefix = "$todayStr:"
        if (!raw.startsWith(prefix)) return 0
        return raw.substring(prefix.length).toIntOrNull() ?: 0
    }

    @Synchronized
    fun incrementDapiAttemptsToday(accountId: String, todayStr: String): Int {
        if (accountId.isBlank() || todayStr.isBlank()) return 0
        val next = getDapiAttemptsToday(accountId, todayStr) + 1
        sharedPreferences.edit().putString("$KEY_PREFIX_DAPI_ATTEMPTS$accountId", "$todayStr:$next").apply()
        return next
    }

    @Synchronized
    fun isPhase05DoneToday(accountId: String, todayStr: String): Boolean {
        if (accountId.isBlank() || todayStr.isBlank()) return false
        return sharedPreferences.getString("$KEY_PREFIX_PHASE05_DONE$accountId", null) == todayStr
    }

    @Synchronized
    fun markPhase05DoneToday(accountId: String, todayStr: String) {
        if (accountId.isBlank() || todayStr.isBlank()) return
        sharedPreferences.edit().putString("$KEY_PREFIX_PHASE05_DONE$accountId", todayStr).apply()
    }

    @Synchronized
    fun isCheckInDoneToday(accountId: String, todayStr: String): Boolean {
        if (accountId.isBlank() || todayStr.isBlank()) return false
        return sharedPreferences.getString("$KEY_PREFIX_CHECKIN_DONE$accountId", null) == todayStr
    }

    @Synchronized
    fun markCheckInDoneToday(accountId: String, todayStr: String) {
        if (accountId.isBlank() || todayStr.isBlank()) return
        sharedPreferences.edit().putString("$KEY_PREFIX_CHECKIN_DONE$accountId", todayStr).apply()
    }

    @Synchronized
    fun getOfferTriedCountToday(accountId: String, offerId: String, todayStr: String): Int {
        if (accountId.isBlank() || offerId.isBlank() || todayStr.isBlank()) return 0
        val raw = sharedPreferences.getString("${KEY_PREFIX_OFFER_TRIED}${accountId.length}:$accountId:$offerId", null) ?: return 0
        val prefix = "$todayStr:"
        if (!raw.startsWith(prefix)) return 0
        return raw.substring(prefix.length).toIntOrNull() ?: 0
    }

    @Synchronized
    fun incrementOfferTriedCountToday(accountId: String, offerId: String, todayStr: String): Int {
        if (accountId.isBlank() || offerId.isBlank() || todayStr.isBlank()) return 0
        val next = getOfferTriedCountToday(accountId, offerId, todayStr) + 1
        val editor = sharedPreferences.edit()
        if (lastOfferPruneDate != todayStr) {
            lastOfferPruneDate = todayStr
            val allEntries = sharedPreferences.all
            if (allEntries != null && allEntries.size > 500) {
                val todayPrefix = "$todayStr:"
                for ((k, v) in allEntries) {
                    if (k.startsWith(KEY_PREFIX_OFFER_TRIED) && (v !is String || !v.startsWith(todayPrefix))) {
                        editor.remove(k)
                    }
                }
            }
        }
        editor.putString("${KEY_PREFIX_OFFER_TRIED}${accountId.length}:$accountId:$offerId", "$todayStr:$next").apply()
        return next
    }

    companion object {
        const val DEFAULT_TELEGRAM_BOT_TOKEN = ""
        const val DEFAULT_TELEGRAM_CHAT_ID = ""
        private const val KEY_PREFIX_SEARCH_EXHAUSTED = "search_exhausted_"
        private const val KEY_PREFIX_DAPI_ATTEMPTS = "dapi_attempts_"
        private const val KEY_PREFIX_PHASE05_DONE = "phase05_done_"
        private const val KEY_PREFIX_CHECKIN_DONE = "checkin_done_"
        private const val KEY_PREFIX_OFFER_TRIED = "offer_tried_"
        private const val KEY_DAILY_SCHEDULE_HOUR = "daily_schedule_hour"
        private const val KEY_DAILY_SCHEDULE_MINUTE = "daily_schedule_minute"
        private const val KEY_DAILY_SLOT_1 = "daily_schedule_slot_1"
        private const val KEY_DAILY_SLOT_2 = "daily_schedule_slot_2"
        private const val KEY_DAILY_SLOT_3 = "daily_schedule_slot_3"
        private const val KEY_DAILY_SLOT_1_ENABLED = "daily_schedule_slot_1_enabled"
        private const val KEY_DAILY_SLOT_2_ENABLED = "daily_schedule_slot_2_enabled"
        private const val KEY_DAILY_SLOT_3_ENABLED = "daily_schedule_slot_3_enabled"
        private const val KEY_SEARCH_DELAY_MIN_SEC = "search_delay_min_sec"
        private const val KEY_SEARCH_DELAY_MAX_SEC = "search_delay_max_sec"
        private const val KEY_MAX_CLAIM_RETRIES = "max_claim_retries"
        private const val KEY_DAPI_MAX_RETRIES = "dapi_max_retries"
        private const val KEY_COOLDOWN_TRIGGER_BATCHES = "cooldown_trigger_batches"
        private const val KEY_FROZEN_STOP_BATCHES = "frozen_stop_batches"
        private const val KEY_MAX_RECONCILIATION_PASSES = "max_reconciliation_passes"
        private const val KEY_PROXY_CIRCUIT_THRESHOLD = "proxy_circuit_threshold"
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
        private const val KEY_AUTO_DAILY_SCHEDULE = "auto_daily_schedule"
        private const val KEY_AUTO_STOP_ON_COMPLETE = "auto_stop_on_complete"
        private const val KEY_LAST_COMPLETION_SUMMARY_DATE = "last_completion_summary_date"

        fun parseTimeSlot(raw: String?): Pair<Int, Int>? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.trim().split(":")
            if (parts.size != 2) return null
            val h = parts[0].trim().toIntOrNull() ?: return null
            val m = parts[1].trim().toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return Pair(h, m)
        }

        fun resolveEnabledScheduleSlots(
            slot1: String?,
            en1: Boolean,
            slot2: String?,
            en2: Boolean,
            slot3: String?,
            en3: Boolean
        ): List<Pair<Int, Int>> {
            val rawSlots = buildList {
                if (en1) add(slot1)
                if (en2) add(slot2)
                if (en3) add(slot3)
            }
            return rawSlots.mapNotNull { parseTimeSlot(it) }
                .distinct()
                .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        }
    }
}
