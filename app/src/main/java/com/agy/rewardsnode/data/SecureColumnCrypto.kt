package com.agy.rewardsnode.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Công cụ mã hóa/giải mã cột dữ liệu nhạy cảm SQLite (AES-256-GCM) sử dụng AndroidKeyStore phần cứng.
 * 100% Pure Android SDK. Tương thích ngược trong suốt với dữ liệu dạng plaintext cũ.
 * Tích hợp bộ đệm in-memory (ConcurrentHashMap) cho kết quả giải mã để tránh gọi phần cứng Keystore liên tục.
 */
object SecureColumnCrypto {

    private const val KEY_ALIAS = "RewardsNodeColumnMasterKey"
    private const val PREFIX = "enc:v1:"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private const val MIN_PAYLOAD_SIZE = IV_LENGTH + (GCM_TAG_LENGTH / 8) // 12 + 16 = 28 bytes

    // Bộ đệm in-memory lưu trữ kết quả giải mã: ciphertext -> decrypted plaintext
    private val decryptCache = ConcurrentHashMap<String, String>()

    init {
        ensureKeyStoreKey()
    }

    private fun ensureKeyStoreKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
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
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Mã hóa chuỗi plaintext thành dạng enc:v1:<Base64>
     */
    fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrBlank()) return plainText
        // Đã được mã hóa trước đó thì không mã hóa đè
        if (plainText.startsWith(PREFIX)) return plainText

        val secretKey = getSecretKey() ?: return plainText
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            val cipherText = PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
            
            // Tự động nạp vào bộ đệm giải mã để các truy vấn đọc ngay sau khi ghi không cần gọi Keystore
            if (decryptCache.size > 1000) {
                decryptCache.clear()
            }
            decryptCache[cipherText] = plainText
            cipherText
        } catch (e: Exception) {
            plainText
        }
    }

    /**
     * Giải mã chuỗi enc:v1:<Base64> thành plaintext.
     * Nếu chuỗi là plaintext cũ (không có tiền tố PREFIX), trả về nguyên vẹn để tương thích ngược.
     * Sử dụng bộ đệm ConcurrentHashMap để tránh gọi AndroidKeyStore phần cứng liên tục.
     */
    fun decrypt(cipherText: String?): String? {
        if (cipherText.isNullOrBlank()) return cipherText
        if (!cipherText.startsWith(PREFIX)) return cipherText // Legacy plaintext fallback

        // Kiểm tra bộ đệm in-memory trước
        val cached = decryptCache[cipherText]
        if (cached != null) {
            return cached
        }

        val rawBase64 = cipherText.substring(PREFIX.length)
        val secretKey = getSecretKey() ?: return ""
        return try {
            val combined = Base64.decode(rawBase64, Base64.NO_WRAP)
            if (combined.size < MIN_PAYLOAD_SIZE) return ""

            val iv = ByteArray(IV_LENGTH)
            val encryptedBytes = ByteArray(combined.size - IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH)
            System.arraycopy(combined, IV_LENGTH, encryptedBytes, 0, encryptedBytes.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val plainText = String(decryptedBytes, Charsets.UTF_8)

            if (decryptCache.size > 1000) {
                decryptCache.clear()
            }
            decryptCache[cipherText] = plainText
            plainText
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Xóa sạch bộ đệm in-memory (dùng khi logout, đổi master key hoặc giải phóng RAM).
     */
    fun clearCache() {
        decryptCache.clear()
    }
}
