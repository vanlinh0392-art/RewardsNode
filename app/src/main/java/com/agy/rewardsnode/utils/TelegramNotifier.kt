package com.agy.rewardsnode.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Gửi thông báo và báo cáo trạng thái tích điểm về Telegram Bot.
 * Triển khai qua Native HttpsURLConnection (100% Android/Java Stdlib, 0 phụ thuộc thư viện ngoài).
 * Bảo mật: Tuyệt đối không log URL chứa bot token ra console hay stacktrace khi có lỗi mạng.
 */
class TelegramNotifier(
    private val botToken: String,
    private val chatId: String
) {

    suspend fun sendMessage(message: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) return@withContext false

        var conn: HttpsURLConnection? = null
        try {
            val endpoint = "https://api.telegram.org/bot$botToken/sendMessage"
            val url = URL(endpoint)
            conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

            val postData = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&text=${URLEncoder.encode(message, "UTF-8")}&parse_mode=Markdown"
            conn.outputStream.use { os ->
                os.write(postData.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            // Chỉ log thông báo lỗi chung, che giấu token an toàn
            android.util.Log.e("TelegramNotifier", "Gửi thông báo Telegram thất bại: ${e.javaClass.simpleName} - ${e.message?.replace(botToken, "***")}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
