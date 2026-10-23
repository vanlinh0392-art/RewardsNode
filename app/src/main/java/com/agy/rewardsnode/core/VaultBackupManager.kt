package com.agy.rewardsnode.core

import android.content.Context
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Trình quản lý Sao lưu & Phục hồi Hồ sơ Encrypted Vault (.rbkp) - Day 12.
 * Tiêu chuẩn bảo mật quân sự:
 * - Dẫn xuất khóa: PBKDF2WithHmacSHA256, 100,000 rounds, 16 bytes SecureRandom Salt.
 * - Mã hóa dòng: AES-256-GCM (12-byte IV, 128-bit Authentication Tag).
 * - Cấu trúc file: [4 bytes Magic "RBKP"][1 byte Version][4 bytes Iterations][1 byte SaltLen][Salt][1 byte IvLen][IV][Ciphertext + Tag]
 */
class VaultBackupManager(private val context: Context) {

    companion object {
        private val MAGIC_BYTES = byteArrayOf(0x52, 0x42, 0x4B, 0x50) // "RBKP"
        private const val CURRENT_VERSION: Byte = 0x01
        private const val PBKDF2_ROUNDS = 100_000
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val KEY_BIT_LENGTH = 256

        fun encryptPayloadBytes(payloadBytes: ByteArray, password: CharArray): ByteArray {
            if (password.isEmpty()) {
                throw IllegalArgumentException("Mật khẩu sao lưu không được để trống!")
            }

            val random = SecureRandom()
            val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
            val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }

            val spec = PBEKeySpec(password, salt, PBKDF2_ROUNDS, KEY_BIT_LENGTH)
            var keyBytes: ByteArray? = null
            try {
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                keyBytes = factory.generateSecret(spec).encoded
                val secretKey = SecretKeySpec(keyBytes, "AES")

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                val ciphertext = cipher.doFinal(payloadBytes)

                return ByteArrayOutputStream(11 + salt.size + iv.size + ciphertext.size).use { bos ->
                    bos.write(MAGIC_BYTES) // 4 bytes: RBKP
                    bos.write(CURRENT_VERSION.toInt()) // 1 byte
                    // 4 bytes: rounds (Big Endian)
                    bos.write((PBKDF2_ROUNDS shr 24) and 0xFF)
                    bos.write((PBKDF2_ROUNDS shr 16) and 0xFF)
                    bos.write((PBKDF2_ROUNDS shr 8) and 0xFF)
                    bos.write(PBKDF2_ROUNDS and 0xFF)

                    bos.write(salt.size) // 1 byte salt len
                    bos.write(salt)

                    bos.write(iv.size) // 1 byte iv len
                    bos.write(iv)

                    bos.write(ciphertext)
                    bos.toByteArray()
                }
            } finally {
                spec.clearPassword()
                keyBytes?.fill(0)
            }
        }

        fun decryptPayloadBytes(fileBytes: ByteArray, password: CharArray): ByteArray {
            if (password.isEmpty()) {
                throw IllegalArgumentException("Mật khẩu giải mã không được để trống!")
            }
            if (fileBytes.size < 36) {
                throw IllegalArgumentException("File sao lưu không tồn tại hoặc bị lỗi kích thước!")
            }

            // 1. Kiểm tra Magic Bytes
            if (fileBytes[0] != MAGIC_BYTES[0] || fileBytes[1] != MAGIC_BYTES[1] ||
                fileBytes[2] != MAGIC_BYTES[2] || fileBytes[3] != MAGIC_BYTES[3]
            ) {
                throw SecurityException("Định dạng file không đúng chuẩn .rbkp của RewardsNode!")
            }

            var offset = 4
            val version = fileBytes[offset++]
            if (version > CURRENT_VERSION) {
                throw UnsupportedOperationException("Phiên bản backup ($version) mới hơn ứng dụng hiện tại!")
            }

            val rounds = ((fileBytes[offset++].toInt() and 0xFF) shl 24) or
                    ((fileBytes[offset++].toInt() and 0xFF) shl 16) or
                    ((fileBytes[offset++].toInt() and 0xFF) shl 8) or
                    (fileBytes[offset++].toInt() and 0xFF)
            if (rounds !in 10_000..1_000_000) {
                throw SecurityException("Số vòng lặp PBKDF2 ($rounds) trong file .rbkp không hợp lệ!")
            }

            val saltLen = fileBytes[offset++].toInt() and 0xFF
            if (saltLen <= 0 || offset + saltLen >= fileBytes.size) {
                throw SecurityException("Cấu trúc Salt trong file .rbkp không hợp lệ!")
            }
            val salt = ByteArray(saltLen)
            System.arraycopy(fileBytes, offset, salt, 0, saltLen)
            offset += saltLen

            val ivLen = fileBytes[offset++].toInt() and 0xFF
            if (ivLen <= 0 || offset + ivLen >= fileBytes.size) {
                throw SecurityException("Cấu trúc IV trong file .rbkp không hợp lệ!")
            }
            val iv = ByteArray(ivLen)
            System.arraycopy(fileBytes, offset, iv, 0, ivLen)
            offset += ivLen

            val cipherLen = fileBytes.size - offset
            if (cipherLen < GCM_TAG_LENGTH / 8) {
                throw SecurityException("Dữ liệu mã hóa trong file .rbkp bị thiếu hoặc hỏng!")
            }
            val ciphertext = ByteArray(cipherLen)
            System.arraycopy(fileBytes, offset, ciphertext, 0, cipherLen)

            val spec = PBEKeySpec(password, salt, rounds, KEY_BIT_LENGTH)
            var keyBytes: ByteArray? = null
            try {
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                keyBytes = factory.generateSecret(spec).encoded
                val secretKey = SecretKeySpec(keyBytes, "AES")

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                return try {
                    cipher.doFinal(ciphertext)
                } catch (e: Exception) {
                    throw SecurityException("Mật khẩu giải mã không chính xác hoặc dữ liệu sao lưu bị thay đổi!", e)
                }
            } finally {
                spec.clearPassword()
                keyBytes?.fill(0)
            }
        }

        fun serializeProfileToJson(profile: AccountProfile): JSONObject {
            val sanitizedCookies = profile.cookiesSnapshot?.let { raw ->
                ProfileManager.sanitizeCookieMap(raw).ifBlank { raw }
            } ?: ""
            return JSONObject().apply {
                put("id", profile.id)
                put("email", profile.email)
                put("profileName", profile.profileName)
                put("level", profile.level)
                put("targetSearches", profile.targetSearches)
                put("completedSearchesToday", profile.completedSearchesToday)
                put("totalPoints", profile.totalPoints)
                put("availablePoints", profile.availablePoints)
                put("lifetimePoints", profile.lifetimePoints)
                put("streakCount", profile.streakCount)
                put("baselinePointsToday", profile.baselinePointsToday)
                put("pointsEarnedToday", profile.pointsEarnedToday)
                put("pcProgress", profile.pcProgress)
                put("pcMax", profile.pcMax)
                put("mobileProgress", profile.mobileProgress)
                put("mobileMax", profile.mobileMax)
                put("edgeProgress", profile.edgeProgress)
                put("edgeMax", profile.edgeMax)
                put("proxyUrl", profile.proxyUrl ?: "")
                put("customUserAgent", profile.customUserAgent ?: "")
                put("cookiesSnapshot", sanitizedCookies)
                put("lastActiveDate", profile.lastActiveDate)
                put("isEnabled", profile.isEnabled)
                put("baselineDate", profile.baselineDate)
                put("dailySetProgress", profile.dailySetProgress)
                put("dailySetTotal", profile.dailySetTotal)
                put("dailySetDone", profile.dailySetDone)
                put("claimablePoints", profile.claimablePoints)
                put("readProgress", profile.readProgress)
                put("readMax", profile.readMax)
                put("puzzleProgress", profile.puzzleProgress)
                put("cooldownUntilTimestamp", profile.cooldownUntilTimestamp)
                put("dapiAccessToken", profile.dapiAccessToken ?: "")
                put("dapiRefreshToken", profile.dapiRefreshToken ?: "")
                put("dapiTokenExpiryTimestampMs", profile.dapiTokenExpiryTimestampMs)
            }
        }

        fun deserializeProfileFromJson(pJson: JSONObject): AccountProfile {
            val rawCookies = pJson.optString("cookiesSnapshot", "")
            val sanitizedCookies = if (rawCookies.isBlank()) {
                null
            } else {
                ProfileManager.sanitizeCookieMap(rawCookies).ifBlank { rawCookies }
            }
            val email = pJson.getString("email")
            return AccountProfile(
                id = pJson.getString("id"),
                email = email,
                profileName = pJson.optString("profileName", email),
                level = pJson.optInt("level", 2),
                targetSearches = pJson.optInt("targetSearches", 35),
                completedSearchesToday = pJson.optInt("completedSearchesToday", 0),
                totalPoints = pJson.optInt("totalPoints", 0),
                availablePoints = pJson.optInt("availablePoints", 0),
                lifetimePoints = pJson.optInt("lifetimePoints", 0),
                streakCount = pJson.optInt("streakCount", 0),
                baselinePointsToday = pJson.optInt("baselinePointsToday", 0),
                pointsEarnedToday = pJson.optInt("pointsEarnedToday", 0),
                pcProgress = pJson.optInt("pcProgress", 0),
                pcMax = pJson.optInt("pcMax", 0),
                mobileProgress = pJson.optInt("mobileProgress", 0),
                mobileMax = pJson.optInt("mobileMax", 0),
                edgeProgress = pJson.optInt("edgeProgress", 0),
                edgeMax = pJson.optInt("edgeMax", 0),
                proxyUrl = pJson.optString("proxyUrl", "").trim().takeIf { it.isNotEmpty() },
                customUserAgent = pJson.optString("customUserAgent", "").trim().takeIf { it.isNotEmpty() },
                cookiesSnapshot = sanitizedCookies,
                lastActiveDate = pJson.optString("lastActiveDate", ""),
                isEnabled = pJson.optBoolean("isEnabled", true),
                baselineDate = pJson.optString("baselineDate", ""),
                dailySetProgress = pJson.optInt("dailySetProgress", 0),
                dailySetTotal = pJson.optInt("dailySetTotal", 3),
                dailySetDone = pJson.optBoolean("dailySetDone", false),
                claimablePoints = pJson.optInt("claimablePoints", 0),
                readProgress = pJson.optInt("readProgress", 0),
                readMax = pJson.optInt("readMax", 30),
                puzzleProgress = pJson.optInt("puzzleProgress", 0),
                cooldownUntilTimestamp = pJson.optLong("cooldownUntilTimestamp", 0L),
                dapiAccessToken = pJson.optString("dapiAccessToken", "").trim().takeIf { it.isNotEmpty() },
                dapiRefreshToken = pJson.optString("dapiRefreshToken", "").trim().takeIf { it.isNotEmpty() },
                dapiTokenExpiryTimestampMs = pJson.optLong("dapiTokenExpiryTimestampMs", 0L)
            )
        }
    }

    data class BackupMetadata(
        val totalProfiles: Int,
        val appVersion: String,
        val timestamp: Long,
        val fileName: String
    )

    /**
     * Xuất toàn bộ hồ sơ tài khoản (bao cả tài khoản đang tắt) và cấu hình hệ thống ra file .rbkp mã hóa.
     */
    suspend fun exportVaultBackup(password: CharArray, outputFile: File): Result<BackupMetadata> = withContext(Dispatchers.IO) {
        try {
            if (password.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Mật khẩu sao lưu không được để trống!"))
            }

            val database = AppDatabase.getDatabase(context)
            val profiles = database.rewardsDao().getAllProfiles()
            val prefs = EncryptedPreferences(context)
            val now = System.currentTimeMillis()

            // 1. Tạo JSON Payload
            val rootJson = JSONObject().apply {
                put("version", 1)
                put("timestamp", now)
                put("app", "RewardsNode")

                // Preferences (đầy đủ 16 trường cấu hình)
                val prefJson = JSONObject().apply {
                    put("cooldownMin", prefs.cooldownMin)
                    put("cooldownMax", prefs.cooldownMax)
                    put("cooldownMinutes", prefs.cooldownMinutes)
                    put("batchSize", prefs.batchSize)
                    put("defaultTargetSearches", prefs.defaultTargetSearches)
                    put("dailyScheduleHour", prefs.dailyScheduleHour)
                    put("dailyScheduleMinute", prefs.dailyScheduleMinute)
                    put("dailyScheduleSlot1", prefs.dailyScheduleSlot1)
                    put("dailyScheduleSlot2", prefs.dailyScheduleSlot2)
                    put("dailyScheduleSlot3", prefs.dailyScheduleSlot3)
                    put("isScheduleSlot1Enabled", prefs.isScheduleSlot1Enabled)
                    put("isScheduleSlot2Enabled", prefs.isScheduleSlot2Enabled)
                    put("isScheduleSlot3Enabled", prefs.isScheduleSlot3Enabled)
                    put("searchDelayMinSec", prefs.searchDelayMinSec)
                    put("searchDelayMaxSec", prefs.searchDelayMaxSec)
                    put("maxClaimRetries", prefs.maxClaimRetries)
                    put("dapiMaxRetries", prefs.dapiMaxRetries)
                    put("cooldownTriggerBatches", prefs.cooldownTriggerBatches)
                    put("frozenStopBatches", prefs.frozenStopBatches)
                    put("maxReconciliationPasses", prefs.maxReconciliationPasses)
                    put("proxyCircuitThreshold", prefs.proxyCircuitThreshold)
                    put("globalProxy", prefs.globalProxy)
                    put("userAgent", prefs.userAgent)
                    put("telegramBotToken", prefs.telegramBotToken)
                    put("telegramChatId", prefs.telegramChatId)
                    put("isDarkMode", prefs.isDarkMode)
                    put("isWifiOnly", prefs.isWifiOnly)
                    put("isBatteryGuardEnabled", prefs.isBatteryGuardEnabled)
                    put("isAutoDailySchedule", prefs.isAutoDailySchedule)
                    put("isAutoStopOnComplete", prefs.isAutoStopOnComplete)
                }
                put("preferences", prefJson)

                // Profiles
                val profilesArray = JSONArray()
                profiles.forEach { profile ->
                    profilesArray.put(serializeProfileToJson(profile))
                }
                put("profiles", profilesArray)
            }

            val payloadBytes = rootJson.toString().toByteArray(Charsets.UTF_8)
            val encryptedFileBytes = encryptPayloadBytes(payloadBytes, password)

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                fos.write(encryptedFileBytes)
                fos.flush()
            }

            Result.success(
                BackupMetadata(
                    totalProfiles = profiles.size,
                    appVersion = "1.0",
                    timestamp = now,
                    fileName = outputFile.name
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Giải mã file .rbkp và khôi phục hồ sơ tài khoản cùng cấu hình nguyên tử qua SQLite Transaction.
     */
    suspend fun importVaultBackup(
        password: CharArray,
        inputFile: File,
        restorePreferences: Boolean = true
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!inputFile.exists() || inputFile.length() < 36) {
                return@withContext Result.failure(IllegalArgumentException("File sao lưu không tồn tại hoặc bị lỗi kích thước!"))
            }

            val fileBytes = FileInputStream(inputFile).use { it.readBytes() }
            val decryptedBytes = decryptPayloadBytes(fileBytes, password)

            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val rootJson = JSONObject(jsonStr)

            val database = AppDatabase.getDatabase(context)

            // Khôi phục đầy đủ preferences nếu được yêu cầu
            if (restorePreferences && rootJson.has("preferences")) {
                val prefJson = rootJson.getJSONObject("preferences")
                val prefs = EncryptedPreferences(context)
                if (prefJson.has("cooldownMin")) prefs.cooldownMin = prefJson.getInt("cooldownMin")
                if (prefJson.has("cooldownMax")) prefs.cooldownMax = prefJson.getInt("cooldownMax")
                if (prefJson.has("cooldownMinutes")) prefs.cooldownMinutes = prefJson.getInt("cooldownMinutes")
                if (prefJson.has("batchSize")) prefs.batchSize = prefJson.getInt("batchSize")
                if (prefJson.has("defaultTargetSearches")) prefs.defaultTargetSearches = prefJson.getInt("defaultTargetSearches")
                if (prefJson.has("dailyScheduleHour")) prefs.dailyScheduleHour = prefJson.getInt("dailyScheduleHour")
                if (prefJson.has("dailyScheduleMinute")) prefs.dailyScheduleMinute = prefJson.getInt("dailyScheduleMinute")
                if (prefJson.has("dailyScheduleSlot1")) prefs.dailyScheduleSlot1 = prefJson.getString("dailyScheduleSlot1")
                if (prefJson.has("dailyScheduleSlot2")) prefs.dailyScheduleSlot2 = prefJson.getString("dailyScheduleSlot2")
                if (prefJson.has("dailyScheduleSlot3")) prefs.dailyScheduleSlot3 = prefJson.getString("dailyScheduleSlot3")
                if (prefJson.has("isScheduleSlot1Enabled")) prefs.isScheduleSlot1Enabled = prefJson.getBoolean("isScheduleSlot1Enabled")
                if (prefJson.has("isScheduleSlot2Enabled")) prefs.isScheduleSlot2Enabled = prefJson.getBoolean("isScheduleSlot2Enabled")
                if (prefJson.has("isScheduleSlot3Enabled")) prefs.isScheduleSlot3Enabled = prefJson.getBoolean("isScheduleSlot3Enabled")
                if (prefJson.has("searchDelayMinSec")) prefs.searchDelayMinSec = prefJson.getInt("searchDelayMinSec")
                if (prefJson.has("searchDelayMaxSec")) prefs.searchDelayMaxSec = prefJson.getInt("searchDelayMaxSec")
                if (prefJson.has("maxClaimRetries")) prefs.maxClaimRetries = prefJson.getInt("maxClaimRetries")
                if (prefJson.has("dapiMaxRetries")) prefs.dapiMaxRetries = prefJson.getInt("dapiMaxRetries")
                if (prefJson.has("cooldownTriggerBatches")) prefs.cooldownTriggerBatches = prefJson.getInt("cooldownTriggerBatches")
                if (prefJson.has("frozenStopBatches")) prefs.frozenStopBatches = prefJson.getInt("frozenStopBatches")
                if (prefJson.has("maxReconciliationPasses")) prefs.maxReconciliationPasses = prefJson.getInt("maxReconciliationPasses")
                if (prefJson.has("proxyCircuitThreshold")) prefs.proxyCircuitThreshold = prefJson.getInt("proxyCircuitThreshold")
                if (prefJson.has("globalProxy")) prefs.globalProxy = prefJson.getString("globalProxy")
                if (prefJson.has("userAgent")) prefs.userAgent = prefJson.getString("userAgent")
                if (prefJson.has("telegramBotToken")) prefs.telegramBotToken = prefJson.getString("telegramBotToken")
                if (prefJson.has("telegramChatId")) prefs.telegramChatId = prefJson.getString("telegramChatId")
                if (prefJson.has("isDarkMode")) prefs.isDarkMode = prefJson.getBoolean("isDarkMode")
                if (prefJson.has("isWifiOnly")) prefs.isWifiOnly = prefJson.getBoolean("isWifiOnly")
                if (prefJson.has("isBatteryGuardEnabled")) prefs.isBatteryGuardEnabled = prefJson.getBoolean("isBatteryGuardEnabled")
                if (prefJson.has("isAutoDailySchedule")) prefs.isAutoDailySchedule = prefJson.getBoolean("isAutoDailySchedule")
                if (prefJson.has("isAutoStopOnComplete")) prefs.isAutoStopOnComplete = prefJson.getBoolean("isAutoStopOnComplete")
            }

            // Khôi phục profiles nguyên tử theo lô (batch transaction)
            val restoredProfiles = mutableListOf<AccountProfile>()
            if (rootJson.has("profiles")) {
                val profilesArray = rootJson.getJSONArray("profiles")
                for (i in 0 until profilesArray.length()) {
                    val pJson = profilesArray.getJSONObject(i)
                    restoredProfiles.add(deserializeProfileFromJson(pJson))
                }
                if (restoredProfiles.isNotEmpty()) {
                    database.rewardsDao().insertOrUpdateProfilesBatch(restoredProfiles)
                }
            }

            Result.success(restoredProfiles.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
