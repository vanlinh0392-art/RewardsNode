package com.agy.rewardsnode.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import kotlin.random.Random

/**
 * Kết quả thực thi DAPI Daily Check-in (Sapphire Gamification).
 */
data class CheckInResult(
    val isSuccess: Boolean,
    val pointsEarned: Int = 0,
    val alreadyCompleted: Boolean = false,
    val isUnauthorized: Boolean = false,
    val message: String = "",
    val rawResponse: String? = null
)

/**
 * Kết quả thực thi DAPI Read to Earn MSN News (Tối đa 10 bài = 30đ).
 */
data class ReadNewsResult(
    val isSuccess: Boolean,
    val articlesRead: Int = 0,
    val pointsEarned: Int = 0,
    val currentProgress: Int = 0,
    val isUnauthorized: Boolean = false,
    val message: String = ""
)

/**
 * Động cơ thực thi các hoạt động DAPI chính thống của Microsoft Rewards di động.
 * Endpoint: https://prod.rewardsplatform.microsoft.com/dapi/me/activities
 */
object DapiActivitiesEngine {

    private const val TAG = "DapiActivities"
    const val ACTIVITIES_ENDPOINT = "https://prod.rewardsplatform.microsoft.com/dapi/me/activities"
    const val CHECK_IN_ACTIVITY_ID = "Gamification_Sapphire_DailyCheckIn"
    const val READ_NEWS_ACTIVITY_ID = "ENUS_readarticle3_30points"
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; 23127PN0CG Build/UKQ1.230804.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.120 Mobile Safari/537.36 Bing/12.8"

    private val secureRandom = java.security.SecureRandom()

    /**
     * Sinh chuỗi 128 ký tự hex ngẫu nhiên (64 bytes) bắt buộc cho mỗi request DAPI.
     */
    fun generateRandomHex128(): String {
        val bytes = ByteArray(64)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder(128)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Tạo JSON payload cho hoạt động Sapphire Daily Check-in chuẩn DAPI 2026:
     * id = 128-char hex ngẫu nhiên, attributes = {"offerid": "Gamification_Sapphire_DailyCheckIn", "date": yyyyMMdd, "signIn": false, "timezoneOffset": "+07:00:00"}
     */
    fun buildDailyCheckInPayload(country: String = "vn"): String {
        val todayCompact = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        return JSONObject().apply {
            put("amount", 1)
            put("attributes", JSONObject().apply {
                put("offerid", CHECK_IN_ACTIVITY_ID)
                put("date", todayCompact)
                put("signIn", false)
                put("timezoneOffset", "+07:00:00")
            })
            put("id", generateRandomHex128())
            put("type", 101)
            put("country", country.lowercase())
            put("risk_context", JSONObject())
            put("channel", "SAAndroid")
        }.toString()
    }

    /**
     * Tạo JSON payload cho hoạt động Bing App Streak (Check in to the Bing App every day - type 103):
     * type = 103, channel = "SAAndroid", attributes = {}
     */
    fun buildBingAppStreakPayload(country: String = "vn"): String {
        return JSONObject().apply {
            put("id", java.util.UUID.randomUUID().toString())
            put("amount", 1)
            put("type", 103)
            put("attributes", JSONObject())
            put("risk_context", JSONObject())
            put("country", country.lowercase())
            put("channel", "SAAndroid")
        }.toString()
    }

    /**
     * Tạo JSON payload cho hoạt động MSN Read to Earn chuẩn DAPI 2026:
     * id = 128-char hex ngẫu nhiên, attributes = {"offerid": "ENUS_readarticle3_30points"}
     */
    fun buildReadToEarnPayload(country: String = "vn"): String {
        return JSONObject().apply {
            put("amount", 1)
            put("attributes", JSONObject().apply {
                put("offerid", READ_NEWS_ACTIVITY_ID)
            })
            put("id", generateRandomHex128())
            put("type", 101)
            put("country", country.lowercase())
            put("risk_context", JSONObject())
            put("channel", "SAAndroid")
        }.toString()
    }

    /**
     * Tính toán tiến độ đọc báo sau khi đọc một bài: tăng 3 điểm nhưng không vượt quá maxPoints (30).
     */
    fun calculateNextProgress(currentProgress: Int, pointsPerArticle: Int = 3, maxPoints: Int = 30): Int {
        return minOf(maxPoints, currentProgress + pointsPerArticle)
    }

    /**
     * Kiểm tra điều kiện hoàn thành của chu kỳ đọc báo: đã đạt maxPoints (>= 30) hoặc đã đọc đủ maxArticles (>= 10).
     */
    fun isReadToEarnComplete(currentProgress: Int, articlesRead: Int = 0, maxPoints: Int = 30, maxArticles: Int = 10): Boolean {
        return currentProgress >= maxPoints || articlesRead >= maxArticles
    }

    private fun parseProxy(proxyUrl: String?): Proxy {
        if (proxyUrl.isNullOrBlank()) return Proxy.NO_PROXY
        return try {
            val uri = URI(if (proxyUrl.contains("://")) proxyUrl else "http://$proxyUrl")
            val host = uri.host ?: return Proxy.NO_PROXY
            val port = if (uri.port != -1) uri.port else 8080
            val type = if (uri.scheme?.startsWith("socks") == true) Proxy.Type.SOCKS else Proxy.Type.HTTP
            Proxy(type, InetSocketAddress(host, port))
        } catch (_: Exception) {
            Proxy.NO_PROXY
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val isSuccess: Boolean,
        val body: String,
        val error: String? = null
    )

    private fun postActivityRequest(
        token: String,
        payloadJson: String,
        country: String = "vn",
        proxyUrl: String? = null
    ): HttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(ACTIVITIES_ENDPOINT)
            val proxy = parseProxy(proxyUrl)
            val cleanToken = token.replace("\r", "").replace("\n", "").trim()
            val cleanCountry = country.replace("\r", "").replace("\n", "").trim()

            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                useCaches = false
                doOutput = true
                doInput = true

                setRequestProperty("Authorization", "Bearer $cleanToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("X-Rewards-Country", cleanCountry)
                setRequestProperty("X-Rewards-AppId", "SAAndroid")
                setRequestProperty("X-Rewards-PartnerId", "startapp")
                setRequestProperty("X-Rewards-IsMobile", "true")
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payloadJson)
                writer.flush()
            }

            val statusCode = connection.responseCode
            val isSuccess = statusCode in 200..299
            val stream = if (isSuccess) connection.inputStream else connection.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } ?: ""

            HttpResponse(statusCode = statusCode, isSuccess = isSuccess, body = body)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi HTTP POST DAPI: ${e.message}", e)
            HttpResponse(statusCode = -1, isSuccess = false, body = "", error = e.message)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Thực thi Dual Bing App Check-In qua DAPI:
     * 1. Pha 1: Bing App Streak Check-In (type 103, channel = "SAAndroid")
     * 2. Pha 2: Sapphire Daily Check-In (type 101, Gamification_Sapphire_DailyCheckIn + date)
     */
    suspend fun executeDailyCheckIn(
        token: String,
        proxyUrl: String? = null
    ): CheckInResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext CheckInResult(
                isSuccess = false,
                message = "Bearer token rỗng hoặc không hợp lệ"
            )
        }

        var totalEarned = 0
        var isAnyCompleted = false
        var isAnyDuplicate = false

        // 1. Pha 1: Bing App Streak (type 103)
        val streakPayload = buildBingAppStreakPayload("vn")
        val streakResp = postActivityRequest(token, streakPayload, country = "vn", proxyUrl = proxyUrl)
        if (streakResp.statusCode == 401) {
            Log.w(TAG, "Bing App Streak Check-In 401 Unauthorized: Token hết hạn")
            return@withContext CheckInResult(
                isSuccess = false,
                isUnauthorized = true,
                message = "Token DAPI hết hạn hoặc không hợp lệ (HTTP 401)",
                rawResponse = streakResp.body
            )
        }
        if (streakResp.isSuccess) {
            val sBody = streakResp.body.lowercase()
            val sJson = try { JSONObject(streakResp.body) } catch (_: Exception) { null }
            val sRespObj = sJson?.optJSONObject("response")
            val sDup = sRespObj?.optBoolean("isDuplicate", false) ?: sJson?.optBoolean("isDuplicate", false) ?: false
            val sActObj = sRespObj?.optJSONObject("activity") ?: sJson?.optJSONObject("activity")
            val sActP = sActObj?.optInt("p", -1) ?: sRespObj?.optInt("p", -1) ?: sJson?.optInt("p", -1) ?: -1
            val sEarned = (if (sActP > 0) sActP else null)
                ?: sJson?.optInt("amount", 0)?.takeIf { it > 0 }
                ?: sJson?.optInt("points", 0)?.takeIf { it > 0 }
                ?: sRespObj?.optInt("amount", 0)?.takeIf { it > 0 }
                ?: sRespObj?.optInt("points", 0)?.takeIf { it > 0 }
                ?: 0

            if (sDup || sActP == 0 || sBody.contains("already") || sBody.contains("completed") || sBody.contains("claimed")) {
                isAnyDuplicate = true
            } else if (sEarned > 0) {
                totalEarned += sEarned
                isAnyCompleted = true
            }
        }

        // 2. Pha 2: Sapphire Daily Check-In (type 101)
        val sapphirePayload = buildDailyCheckInPayload("vn")
        val sapphireResp = postActivityRequest(token, sapphirePayload, country = "vn", proxyUrl = proxyUrl)
        if (sapphireResp.statusCode == 401) {
            Log.w(TAG, "Sapphire Daily Check-In 401 Unauthorized: Token hết hạn")
            return@withContext CheckInResult(
                isSuccess = false,
                isUnauthorized = true,
                message = "Token DAPI hết hạn hoặc không hợp lệ (HTTP 401)",
                rawResponse = sapphireResp.body
            )
        }
        if (sapphireResp.isSuccess) {
            val body = sapphireResp.body.lowercase()
            val json = try { JSONObject(sapphireResp.body) } catch (_: Exception) { null }
            val respObj = json?.optJSONObject("response")
            val isDuplicate = respObj?.optBoolean("isDuplicate", false) ?: json?.optBoolean("isDuplicate", false) ?: false
            val actObj = respObj?.optJSONObject("activity") ?: json?.optJSONObject("activity")
            val activityP = actObj?.optInt("p", -1) ?: respObj?.optInt("p", -1) ?: json?.optInt("p", -1) ?: -1
            val earned = (if (activityP > 0) activityP else null)
                ?: json?.optInt("amount", 0)?.takeIf { it > 0 }
                ?: json?.optInt("points", 0)?.takeIf { it > 0 }
                ?: json?.optInt("payout", 0)?.takeIf { it > 0 }
                ?: respObj?.optInt("amount", 0)?.takeIf { it > 0 }
                ?: respObj?.optInt("points", 0)?.takeIf { it > 0 }
                ?: 0

            if (isDuplicate || activityP == 0 || body.contains("already") || body.contains("completed") || body.contains("claimed")) {
                isAnyDuplicate = true
            } else if (earned > 0) {
                totalEarned += earned
                isAnyCompleted = true
            }
        }

        if (totalEarned > 0) {
            Log.i(TAG, "Check-in Bing App thành công: +$totalEarned điểm (type 103 + type 101)")
            CheckInResult(
                isSuccess = true,
                pointsEarned = totalEarned,
                alreadyCompleted = false,
                message = "Check in to the Bing App thành công (+$totalEarned pts)",
                rawResponse = sapphireResp.body
            )
        } else if (isAnyDuplicate || isAnyCompleted || sapphireResp.isSuccess || streakResp.isSuccess) {
            Log.i(TAG, "Check-in Bing App: Đã hoàn thành điểm danh hôm nay.")
            CheckInResult(
                isSuccess = true,
                pointsEarned = 0,
                alreadyCompleted = true,
                message = "Đã điểm danh Bing App hôm nay (0 pts)",
                rawResponse = sapphireResp.body
            )
        } else {
            val errMsg = sapphireResp.error ?: "HTTP ${sapphireResp.statusCode}: ${sapphireResp.body}"
            Log.e(TAG, "Check-in Bing App thất bại: $errMsg")
            CheckInResult(
                isSuccess = false,
                message = errMsg,
                rawResponse = sapphireResp.body
            )
        }
    }

    /**
     * Thực thi MSN Read to Earn (10 bài báo x 3đ = 30đ/ngày).
     * Payload: {"amount":1,"country":"vn","id":"ENUS_readarticle3_30points","type":101}
     * Giãn cách tự nhiên: Random delay 8500 - 14000ms giữa mỗi bài.
     */
    suspend fun executeReadToEarn(
        token: String,
        currentProgress: Int,
        maxPoints: Int = 30,
        onArticleDone: ((index: Int, earned: Int) -> Unit)? = null,
        proxyUrl: String? = null
    ): ReadNewsResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext ReadNewsResult(
                isSuccess = false,
                currentProgress = currentProgress,
                message = "Bearer token rỗng hoặc không hợp lệ"
            )
        }

        var progress = currentProgress
        if (progress >= maxPoints) {
            Log.i(TAG, "MSN Read to Earn: Đã đạt trần $progress/$maxPoints pts, bỏ qua (Early exit).")
            return@withContext ReadNewsResult(
                isSuccess = true,
                articlesRead = 0,
                pointsEarned = 0,
                currentProgress = progress,
                message = "Đã đạt tối đa đọc báo hôm nay ($progress/$maxPoints pts)"
            )
        }

        var articlesRead = 0
        var totalEarned = 0
        val maxArticles = 10
        var isUnauthorized = false
        var lastKnownBalance = -1

        Log.i(TAG, "Bắt đầu chu kỳ Read to Earn (Hiện tại: $progress/$maxPoints pts)")

        while (articlesRead < maxArticles && progress < maxPoints) {
            // Sinh payload mới với id 128-char hex ngẫu nhiên mới cho MỖI bài báo
            val payload = buildReadToEarnPayload("vn")
            val response = postActivityRequest(token, payload, country = "vn", proxyUrl = proxyUrl)

            if (response.isSuccess) {
                val body = response.body.lowercase()
                if (body.contains("limit_reached") || body.contains("max_reached") || body.contains("no_rewards")) {
                    Log.i(TAG, "MSN Read to Earn: Server báo đạt giới hạn thưởng -> Early exit.")
                    progress = maxPoints
                    break
                }

                val json = try { JSONObject(response.body) } catch (_: Exception) { null }
                val respObj = json?.optJSONObject("response")
                val isDuplicate = respObj?.optBoolean("isDuplicate", false)
                    ?: json?.optBoolean("isDuplicate", false)
                    ?: false

                // Bắt chính xác newBalance từ response object hoặc root JSON
                val newBalance = when {
                    respObj?.has("balance") == true -> respObj.optInt("balance", -1)
                    json?.has("balance") == true -> json.optInt("balance", -1)
                    else -> -1
                }

                // Bắt chính xác activityP từ activity object trong response hoặc root JSON
                val actObj = respObj?.optJSONObject("activity") ?: json?.optJSONObject("activity")
                val activityP = when {
                    actObj?.has("p") == true -> actObj.optInt("p", -1)
                    respObj?.has("p") == true -> respObj.optInt("p", -1)
                    json?.has("p") == true -> json.optInt("p", -1)
                    else -> -1
                }

                val earnedThisArticle = when {
                    isDuplicate -> 0
                    activityP > 0 -> activityP
                    activityP == 0 -> 0
                    lastKnownBalance >= 0 && newBalance >= 0 -> maxOf(0, newBalance - lastKnownBalance)
                    lastKnownBalance < 0 && newBalance >= 0 && !isDuplicate -> 3
                    else -> {
                        val fallbackPoints = (respObj?.optInt("amount", 0)?.takeIf { it > 0 })
                            ?: (json?.optInt("amount", 0)?.takeIf { it > 0 })
                            ?: (respObj?.optInt("points", 0)?.takeIf { it > 0 })
                            ?: (json?.optInt("points", 0)?.takeIf { it > 0 })
                            ?: 0
                        if (fallbackPoints > 0) fallbackPoints else (if (!isDuplicate && response.isSuccess) 3 else 0)
                    }
                }
                if (newBalance >= 0) {
                    lastKnownBalance = newBalance
                }

                if (earnedThisArticle <= 0) {
                    Log.i(TAG, "MSN Read to Earn: Không nhận thêm điểm (activityP=$activityP, isDuplicate=$isDuplicate, balance=$newBalance) -> Đã đạt giới hạn đọc báo hôm nay (Early exit).")
                    progress = maxPoints
                    break
                }

                articlesRead++
                progress = calculateNextProgress(progress, earnedThisArticle, maxPoints)
                totalEarned += earnedThisArticle

                Log.i(TAG, "Đã đọc bài #$articlesRead/10 (+${earnedThisArticle}đ, balance=$newBalance) -> Tiến độ: $progress/$maxPoints")
                onArticleDone?.invoke(articlesRead, progress)

                // Early exit ngay lập tức khi progress đạt maxPoints (30đ), không chờ delay
                if (progress >= maxPoints) {
                    Log.i(TAG, "MSN Read to Earn: Đã đạt trần $progress/$maxPoints pts -> Dừng ngay lập tức (Early exit).")
                    break
                }

                // Giãn cách ngẫu nhiên 8.5s - 14s giữa các bài nếu còn bài cần đọc
                if (articlesRead < maxArticles && progress < maxPoints) {
                    val jitterDelay = Random.nextLong(8500L, 14001L)
                    Log.d(TAG, "Nghỉ ngẫu nhiên ${jitterDelay}ms trước bài báo tiếp theo...")
                    delay(jitterDelay)
                }
            } else {
                val body = response.body.lowercase()
                if (body.contains("already") || body.contains("limit") || body.contains("completed")) {
                    Log.i(TAG, "MSN Read to Earn (HTTP ${response.statusCode}): Đạt giới hạn điểm danh/đọc báo -> Early exit.")
                    progress = maxPoints
                    break
                }

                if (response.statusCode == 401) {
                    Log.w(TAG, "MSN Read to Earn 401 Unauthorized: Token hết hạn, dừng vòng lặp.")
                    isUnauthorized = true
                    break
                }

                Log.e(TAG, "Lỗi đọc bài #$articlesRead (HTTP ${response.statusCode}): ${response.body}")
                // Thử delay ngắn rồi dừng nếu lỗi hệ thống
                delay(2000L)
                break
            }
        }

        val isSuccess = articlesRead > 0 || progress >= maxPoints
        val msg = if (isUnauthorized) {
            "Token DAPI hết hạn hoặc không hợp lệ (HTTP 401)"
        } else {
            "Đã đọc $articlesRead bài báo, tích lũy +$totalEarned pts (Tiến độ: $progress/$maxPoints)"
        }
        Log.i(TAG, "Hoàn tất Read to Earn: $msg")

        ReadNewsResult(
            isSuccess = isSuccess,
            articlesRead = articlesRead,
            pointsEarned = totalEarned,
            currentProgress = progress,
            isUnauthorized = isUnauthorized,
            message = msg
        )
    }
}
