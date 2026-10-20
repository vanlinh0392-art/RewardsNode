package com.agy.rewardsnode.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Gửi thông báo và báo cáo trạng thái tích điểm về Telegram Bot.
 */
class TelegramNotifier(
    private val botToken: String,
    private val chatId: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun sendMessage(message: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) return@withContext false

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val formBody = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", message)
            .add("parse_mode", "Markdown")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
