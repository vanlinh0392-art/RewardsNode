package com.agy.rewardsnode.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import com.agy.rewardsnode.data.model.AccountProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

        // Thử gửi định dạng Markdown trước
        val sentWithMarkdown = sendRawMessage(message, parseMode = "Markdown")
        if (sentWithMarkdown) return@withContext true

        // Fallback: Tự động gửi plain text (không parse_mode) nếu gọi với Markdown bị lỗi 400 hoặc exception
        android.util.Log.w("TelegramNotifier", "Gửi Markdown thất bại, tự động fallback sang plain text không parse_mode...")
        sendRawMessage(message, parseMode = null)
    }

    /**
     * Thoát các ký tự đặc biệt trong định dạng Markdown của Telegram để tránh lỗi HTTP 400 Bad Request.
     * Hỗ trợ đặc biệt cho địa chỉ email có dấu gạch dưới (_), sao (*), backtick (`), ngoặc vuông ([), và gạch chéo ngược (\).
     */
    fun escapeMarkdown(text: String): String = Companion.escapeMarkdown(text)

    /**
     * Che bớt ký tự email an toàn phục vụ hiển thị báo cáo bảo mật.
     */
    fun maskEmail(email: String): String = Companion.maskEmail(email)

    /**
     * Gửi báo cáo tổng kết tiến độ và điểm thưởng hàng ngày của toàn bộ danh sách tài khoản.
     * Định dạng Markdown chuyên nghiệp, có cơ chế tự động chia nhỏ bản tin nếu vượt quá giới hạn 4000 ký tự của Telegram,
     * đồng thời tự động fallback về plain text nếu Markdown gặp lỗi cú pháp.
     */
    suspend fun sendDailyCompletionReport(
        accounts: List<AccountProfile>,
        todayStr: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) return@withContext false

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val totalAccounts = accounts.size
        val totalEarned = accounts.sumOf { it.pointsEarnedToday }
        val totalBalance = accounts.sumOf { it.availablePoints }

        val header = buildString {
            append("📊 *BÁO CÁO TỔNG KẾT REWARDS*\n")
            append("📅 Ngày: *").append(todayStr).append("* (").append(timeStr).append(")\n")
            append("👥 Tổng tài khoản: *").append(totalAccounts).append("*\n")
            append("✨ Tổng điểm kiếm hôm nay: *+").append(String.format(Locale.US, "%,d", totalEarned)).append(" pts*\n")
            append("💰 Tổng số dư khả dụng: *").append(String.format(Locale.US, "%,d", totalBalance)).append(" pts*\n")
            append("──────────────────\n")
        }

        if (accounts.isEmpty()) {
            return@withContext sendMessage(header + "⚠️ Không có tài khoản nào được ghi nhận.")
        }

        val accountBlocks = accounts.mapIndexed { index, account ->
            val safeEmail = escapeMarkdown(maskEmail(account.email))
            val pcMax = if (account.pcMax > 0) account.pcMax else (if (account.level == 1) 30 else 90)
            val mobMax = if (account.mobileMax > 0) account.mobileMax else (if (account.level == 1) 0 else 60)
            val readMax = if (account.readMax > 0) account.readMax else 30
            val dailyStatus = if (account.dailySetDone) "3/3 ✅" else "${account.dailySetProgress}/${account.dailySetTotal}"
            val streakStr = if (account.streakCount > 0) " (🔥 ${account.streakCount} ngày)" else ""
            val earnedStr = String.format(Locale.US, "%,d", account.pointsEarnedToday)
            val availStr = String.format(Locale.US, "%,d", account.availablePoints)
            val edgePart = if (account.edgeMax > 0) " | Edge: ${account.edgeProgress}/${account.edgeMax}" else ""

            buildString {
                append("*#").append(index + 1).append(".* ").append(safeEmail).append(streakStr).append("\n")
                append("• Điểm hôm nay: *+").append(earnedStr).append(" pts* | Số dư: *").append(availStr).append(" pts*\n")
                append("• Chi tiết: PC: ").append(account.pcProgress).append("/").append(pcMax)
                append(" | Mob: ").append(account.mobileProgress).append("/").append(mobMax)
                append(" | Đọc tin: ").append(account.readProgress).append("/").append(readMax)
                append(" | Daily: ").append(dailyStatus)
                append(edgePart).append("\n")
            }
        }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder(header)

        for (block in accountBlocks) {
            if (currentChunk.length + block.length + 1 > 4000) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trimEnd())
                    currentChunk.clear()
                }
                currentChunk.append("📊 *BÁO CÁO REWARDS (tiếp theo)*\n──────────────────\n")
            }
            currentChunk.append(block).append("\n")
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trimEnd())
        }

        var allSuccess = true
        for ((idx, chunk) in chunks.withIndex()) {
            if (idx > 0) {
                delay(500)
            }
            val ok = sendMessage(chunk)
            if (!ok) allSuccess = false
        }
        allSuccess
    }

    private fun sendRawMessage(message: String, parseMode: String?): Boolean {
        var conn: HttpsURLConnection? = null
        return try {
            val endpoint = "https://api.telegram.org/bot$botToken/sendMessage"
            val url = URL(endpoint)
            conn = url.openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

            val postData = buildPayload(chatId, message, parseMode)

            conn.outputStream.use { os ->
                os.write(postData.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                true
            } else {
                val errorDetails = try {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "No error stream"
                } catch (e: Exception) {
                    "Error reading error stream: ${e.message}"
                }
                android.util.Log.e("TelegramNotifier", "Telegram API HTTP $responseCode (parse_mode=$parseMode): ${errorDetails.replace(botToken, "***")}")
                false
            }
        } catch (e: Exception) {
            // Chỉ log thông báo lỗi chung, che giấu token an toàn
            android.util.Log.e("TelegramNotifier", "Gửi thông báo Telegram thất bại: ${e.javaClass.simpleName} - ${e.message?.replace(botToken, "***")}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Day 11: Gửi ảnh chụp màn hình WebView (in-memory Bitmap) khi gặp Captcha / Thử thách bảo mật qua Telegram Bot API.
     * Sử dụng chuẩn multipart/form-data (RFC 7578) qua HttpsURLConnection thuần, nén ảnh trực tiếp trong RAM, không ghi file ra đĩa.
     */
    suspend fun sendScreenshot(bitmap: Bitmap, caption: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) return@withContext false

        var conn: HttpsURLConnection? = null
        try {
            val boundary = "==RewardsNodeBoundary${System.currentTimeMillis()}=="
            val endpoint = "https://api.telegram.org/bot$botToken/sendPhoto"
            val url = URL(endpoint)
            conn = (url.openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 15000
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Connection", "Keep-Alive")
            }

            val imageBytes = ByteArrayOutputStream().use { bos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                bos.toByteArray()
            }

            conn.outputStream.use { os ->
                // Part 1: chat_id
                os.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
                os.write(("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n").toByteArray(Charsets.UTF_8))
                os.write(chatId.toByteArray(Charsets.UTF_8))
                os.write(("\r\n").toByteArray(Charsets.UTF_8))

                // Part 2: caption
                if (caption.isNotBlank()) {
                    os.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
                    os.write(("Content-Disposition: form-data; name=\"caption\"\r\n\r\n").toByteArray(Charsets.UTF_8))
                    os.write(caption.toByteArray(Charsets.UTF_8))
                    os.write(("\r\n").toByteArray(Charsets.UTF_8))
                }

                // Part 3: photo (file payload)
                os.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
                os.write(("Content-Disposition: form-data; name=\"photo\"; filename=\"captcha_screenshot.jpg\"\r\n").toByteArray(Charsets.UTF_8))
                os.write(("Content-Type: image/jpeg\r\n\r\n").toByteArray(Charsets.UTF_8))
                os.write(imageBytes)
                os.write(("\r\n").toByteArray(Charsets.UTF_8))

                // Kết thúc multipart
                os.write(("--$boundary--\r\n").toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                true
            } else {
                val errorDetails = try {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "No error stream"
                } catch (e: Exception) {
                    "Error reading error stream: ${e.message}"
                }
                android.util.Log.e("TelegramNotifier", "Gửi screenshot Telegram lỗi HTTP $responseCode: ${errorDetails.replace(botToken, "***")}")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("TelegramNotifier", "Gửi screenshot Telegram thất bại: ${e.javaClass.simpleName} - ${e.message?.replace(botToken, "***")}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Gửi file tài liệu (như bản sao lưu mã hóa .rbkp) qua Telegram Bot API (sendDocument).
     * Sử dụng chuẩn multipart/form-data (RFC 7578) qua HttpsURLConnection thuần.
     */
    suspend fun sendDocument(file: java.io.File, caption: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank() || !file.exists() || !file.isFile) return@withContext false

        var conn: HttpsURLConnection? = null
        try {
            val boundary = "==RewardsNodeDocBoundary${System.currentTimeMillis()}=="
            val endpoint = "https://api.telegram.org/bot$botToken/sendDocument"
            val url = URL(endpoint)
            conn = (url.openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Connection", "Keep-Alive")
            }

            val safeFileName = file.name.replace("\"", "_")

            conn.outputStream.use { os ->
                // Part 1: chat_id
                os.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
                os.write(("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n").toByteArray(Charsets.UTF_8))
                os.write(chatId.toByteArray(Charsets.UTF_8))
                os.write(("\r\n").toByteArray(Charsets.UTF_8))

                // Part 2: caption
                if (caption.isNotBlank()) {
                    os.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
                    os.write(("Content-Disposition: form-data; name=\"caption\"\r\n\r\n").toByteArray(Charsets.UTF_8))
                    os.write(caption.toByteArray(Charsets.UTF_8))
                    os.write(("\r\n").toByteArray(Charsets.UTF_8))
                }

                // Part 3: document (file payload)
                os.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
                os.write(("Content-Disposition: form-data; name=\"document\"; filename=\"$safeFileName\"\r\n").toByteArray(Charsets.UTF_8))
                os.write(("Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8))
                java.io.FileInputStream(file).use { fis ->
                    fis.copyTo(os, bufferSize = 8192)
                }
                os.write(("\r\n").toByteArray(Charsets.UTF_8))

                // Kết thúc multipart
                os.write(("--$boundary--\r\n").toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                true
            } else {
                val errorDetails = try {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "No error stream"
                } catch (e: Exception) {
                    "Error reading error stream: ${e.message}"
                }
                android.util.Log.e("TelegramNotifier", "Gửi document Telegram lỗi HTTP $responseCode: ${errorDetails.replace(botToken, "***")}")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("TelegramNotifier", "Gửi document Telegram thất bại: ${e.javaClass.simpleName} - ${e.message?.replace(botToken, "***")}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        /**
         * Thoát các ký tự đặc biệt trong định dạng Markdown của Telegram để tránh lỗi HTTP 400 Bad Request.
         * Hỗ trợ đặc biệt cho địa chỉ email có dấu gạch dưới (_), sao (*), backtick (`), ngoặc vuông ([), và gạch chéo ngược (\).
         */
        fun escapeMarkdown(text: String): String {
            if (text.isEmpty()) return ""
            return text
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("[", "\\[")
        }

        /**
         * Che bớt ký tự email an toàn phục vụ hiển thị báo cáo bảo mật.
         */
        fun maskEmail(email: String): String {
            val clean = email.trim()
            if (clean.isBlank()) return "unknown"
            val atIndex = clean.indexOf('@')
            if (atIndex <= 0) {
                return if (clean.length > 4) {
                    clean.take(2) + "***" + clean.takeLast(2)
                } else {
                    clean.take(1) + "***"
                }
            }
            val name = clean.substring(0, atIndex)
            val domain = clean.substring(atIndex + 1)
            val maskedName = when {
                name.length <= 2 -> name.take(1) + "***"
                name.length <= 4 -> name.take(1) + "***" + name.takeLast(1)
                else -> name.take(2) + "***" + name.takeLast(2)
            }
            return "$maskedName@$domain"
        }

        fun buildPayload(chatId: String, message: String, parseMode: String? = null): String {
            val baseData = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&text=${URLEncoder.encode(message, "UTF-8")}"
            return if (!parseMode.isNullOrBlank()) {
                "$baseData&parse_mode=${URLEncoder.encode(parseMode, "UTF-8")}"
            } else {
                baseData
            }
        }
        /**
         * Chụp ảnh nhanh nội dung hiển thị hiện thời của WebView vào bộ nhớ RAM.
         * Tối ưu hóa Sprint 4 (G4.4): Giới hạn chiều rộng tối đa 720px, sử dụng cấu hình Bitmap.Config.RGB_565
         * để giảm 50% RAM so với ARGB_8888, bọc try/finally tự giải phóng bitmap khi có lỗi để chống rò rỉ native OOM.
         * Tuyệt đối không lưu file tạm ra bộ nhớ flash để đảm bảo tốc độ và bảo mật.
         */
        fun captureBitmap(webView: WebView?): Bitmap? {
            if (webView == null || webView.width <= 0 || webView.height <= 0) return null
            var bitmap: Bitmap? = null
            var success = false
            return try {
                val origW = webView.width
                val origH = webView.height
                val targetW = if (origW > 720) 720 else origW
                val targetH = if (origW > 720) {
                    ((origH.toFloat() / origW.toFloat()) * targetW).toInt().coerceAtLeast(1)
                } else origH

                bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                if (origW > 720) {
                    val scale = targetW.toFloat() / origW.toFloat()
                    canvas.scale(scale, scale)
                }
                webView.draw(canvas)
                success = true
                bitmap
            } catch (e: Throwable) {
                android.util.Log.e("TelegramNotifier", "Lỗi chụp ảnh WebView: ${e.message}")
                null
            } finally {
                if (!success && bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }
}
