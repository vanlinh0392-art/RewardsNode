package com.agy.rewardsnode.core

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Động cơ trích xuất số dư điểm Microsoft Rewards đa lớp (Resilient Balance Extractor).
 *
 * Chiến lược 3 lớp:
 * 1. HTTP Flyout API Endpoint: Truy vấn độc lập bất đồng bộ `https://www.bing.com/rewardsapp/flyoutpageinformation` 
 *    trên Dispatchers.IO với cookie đã lọc phạm vi .bing.com để lấy availablePoints, lifetimePoints,
 *    streakCount, và 3 rổ điểm counters (PC 90đ, Mobile 60đ, Edge 12đ).
 * 2. Window Globals: Quét các đối tượng JavaScript toàn cục (`rewardsData`, `UserStatus`, `bing.rewards`).
 * 3. DOM Fallback: Quét các phần tử giao diện Bing `#id_rc`, `#id_rh`, `.rh_meter`, `span[data-points]`.
 */
object RewardsBalanceExtractor {

    const val DASHBOARD_URL = "https://rewards.bing.com/dashboard"
    const val FLYOUT_API_URL = "https://www.bing.com/rewardsapp/flyoutpageinformation"
    const val GET_USER_INFO_API_URL = "https://rewards.bing.com/api/getuserinfo"
    private const val DEFAULT_DESKTOP_EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0"

    val latestTelemetryMap = java.util.concurrent.ConcurrentHashMap<String, ExtractedRewardsBalance>()

    /**
     * Dữ liệu điểm số trích xuất được từ phiên làm việc Bing Rewards.
     */
    data class ExtractedRewardsBalance(
        val availablePoints: Int = 0,
        val lifetimePoints: Int = 0,
        val streakCount: Int = 0,
        val pointsEarnedToday: Int = 0,
        val isSuccess: Boolean = false,
        val source: String = "none",
        val pcProgress: Int = 0,
        val pcMax: Int = 0,
        val mobileProgress: Int = 0,
        val mobileMax: Int = 0,
        val edgeProgress: Int = 0,
        val edgeMax: Int = 0,
        val isAllMaxedOut: Boolean = false,
        val email: String? = null,
        val profileName: String? = null,
        val level: Int = 1,
        val claimablePoints: Int = 0,
        val streakProtectionOn: Boolean = false,
        val dailySetDone: Boolean = false,
        val dailySetProgress: Int = 0
    )

    /**
     * Sinh kịch bản JavaScript tự động thực thi trong ngữ cảnh WebView Bing.
     * ĐÃ LOẠI BỎ Synchronous XHR (xhr.open(..., false)) để tránh Chromium chặn [Deprecation] Synchronous XHR trên Main Thread.
     */
    fun createExtractionScript(): String {
        return """
            (function() {
                var result = {
                    availablePoints: 0,
                    lifetimePoints: 0,
                    streakCount: 0,
                    pointsEarnedToday: 0,
                    isSuccess: false,
                    source: 'none',
                    pcProgress: 0,
                    pcMax: 0,
                    mobileProgress: 0,
                    mobileMax: 0,
                    edgeProgress: 0,
                    edgeMax: 0,
                    isAllMaxedOut: false,
                    claimablePoints: 0,
                    streakProtectionOn: false,
                    dailySetDone: false,
                    dailySetProgress: 0
                };

                // Lớp 1: Medallion widget trên Bing SERP
                try {
                    var med = document.querySelector('[data-rewards-widget="medallion"], .medallion');
                    if (med) {
                        var content = med.getAttribute('data-content');
                        if (content) {
                            var decoded = JSON.parse(atob(content));
                            if (decoded && typeof decoded.balance === 'number' && decoded.balance > 0) {
                                result.availablePoints = decoded.balance;
                                result.isSuccess = true;
                                result.source = 'medallion_base64';
                                return JSON.stringify(result);
                            }
                        }
                    }
                } catch (e) {}

                // Lớp 2: Kiểm tra biến JavaScript toàn cục rewardsData / UserStatus
                try {
                    var globalData = window.rewardsData || window.UserStatus || (window.bing && window.bing.rewards);
                    if (globalData) {
                        var gUser = globalData.userStatus || globalData;
                        if (gUser && (typeof gUser.availablePoints === 'number' || typeof gUser.availablePoints === 'string')) {
                            result.availablePoints = parseInt(gUser.availablePoints, 10) || 0;
                            result.lifetimePoints = parseInt(gUser.lifetimePoints, 10) || 0;
                            if (gUser.streakInfo && typeof gUser.streakInfo.streakCount === 'number') {
                                result.streakCount = gUser.streakInfo.streakCount;
                            }
                            if (typeof gUser.claimablePoints === 'number' || typeof gUser.claimablePoints === 'string') {
                                result.claimablePoints = parseInt(gUser.claimablePoints, 10) || 0;
                            }
                            if (gUser.pointClaim && (typeof gUser.pointClaim.points === 'number' || typeof gUser.pointClaim.points === 'string')) {
                                result.claimablePoints = parseInt(gUser.pointClaim.points, 10) || 0;
                            }
                            if (gUser.streakInfo && gUser.streakInfo.streakProtection) {
                                result.streakProtectionOn = !!(gUser.streakInfo.streakProtection.isProtectionOn || gUser.streakInfo.streakProtection.isProtectionEnabled);
                            }
                            if (result.availablePoints > 0 || result.lifetimePoints > 0) {
                                result.isSuccess = true;
                                result.source = 'global_var';
                                return JSON.stringify(result);
                            }
                        }
                    }
                } catch (e) {}

                // Lớp 3: Fallback DOM (#id_rc, #id_rh, .rh_meter, span[data-points])
                try {
                    var selectors = [
                        '#id_rc',
                        '#id_rh',
                        '#rh_meter_count',
                        '.rh_meter',
                        'span[data-points]',
                        '.rewards-card-points',
                        '#flyout_canvas #id_rc',
                        '.mectrl_topHeader #id_rc'
                    ];

                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el) {
                            var text = (el.innerText || el.textContent || '').trim();
                            var cleaned = text.replace(/[^0-9]/g, '');
                            if (cleaned.length > 0) {
                                var pts = parseInt(cleaned, 10);
                                if (!isNaN(pts) && pts >= 0) {
                                    result.availablePoints = pts;
                                    result.isSuccess = true;
                                    result.source = 'dom_' + selectors[i].replace(/[^a-zA-Z0-9_]/g, '_');
                                    return JSON.stringify(result);
                                }
                            }
                        }
                    }
                } catch (e) {}

                // Lớp 4: Quét toàn bộ HTML / Script tags cho rewards.bing.com dashboard
                try {
                    var bodyHtml = (document.body ? document.body.innerHTML : '') + (document.head ? document.head.innerHTML : '');
                    if (bodyHtml) {
                        var balMatch = bodyHtml.match(/balance[^0-9]{1,10}(\d+)/);
                        if (balMatch && balMatch[1]) {
                            var pts = parseInt(balMatch[1], 10);
                            if (pts > 0) {
                                result.availablePoints = pts;
                                var lifeMatch = bodyHtml.match(/lifetimeEarn[^0-9]{1,10}(\d+)/);
                                if (lifeMatch && lifeMatch[1]) {
                                    result.lifetimePoints = parseInt(lifeMatch[1], 10);
                                }
                                var streakMatch = bodyHtml.match(/streakCounter[^0-9]{1,10}(\d+)/);
                                if (streakMatch && streakMatch[1]) {
                                    result.streakCount = parseInt(streakMatch[1], 10);
                                }
                                var claimMatch = bodyHtml.match(/claimablePoints[^0-9]{1,10}(\d+)/) || bodyHtml.match(/pointClaim[^\}]*?points[^\d]{1,10}(\d+)/);
                                if (claimMatch && claimMatch[1]) {
                                    result.claimablePoints = parseInt(claimMatch[1], 10) || 0;
                                }
                                var protMatch = bodyHtml.match(/isProtectionOn["':\s]+(true|false)/);
                                if (protMatch && protMatch[1] === 'true') {
                                    result.streakProtectionOn = true;
                                }
                                result.isSuccess = true;
                                result.source = 'body_regex';
                                return JSON.stringify(result);
                            }
                        }
                    }
                } catch (e) {}

                return JSON.stringify(result);
            })();
        """.trimIndent()
    }

    fun getExtractionScript(): String = createExtractionScript()

    /**
     * Phân tích kết quả JSON trả về từ WebView.evaluateJavascript.
     */
    fun parseResult(rawOutput: String?): ExtractedRewardsBalance {
        if (rawOutput.isNullOrBlank() || rawOutput == "null" || rawOutput == "\"null\"") {
            return ExtractedRewardsBalance(isSuccess = false)
        }

        try {
            var sanitized = rawOutput.trim()

            // evaluateJavascript thường bọc kết quả trong chuỗi JSON (escaped quotes)
            if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                sanitized = sanitized.substring(1, sanitized.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }

            // Trường hợp trả về trực tiếp số nguyên
            sanitized.toIntOrNull()?.let { points ->
                if (points >= 0) {
                    return ExtractedRewardsBalance(
                        availablePoints = points,
                        isSuccess = true,
                        source = "raw_integer"
                    )
                }
            }

            if (sanitized.startsWith("{") && sanitized.endsWith("}")) {
                val json = JSONObject(sanitized)
                val availablePoints = json.optInt("availablePoints", 0)
                val lifetimePoints = json.optInt("lifetimePoints", 0)
                val streakCount = json.optInt("streakCount", 0)
                val pointsEarnedToday = json.optInt("pointsEarnedToday", 0)
                val isSuccess = json.optBoolean("isSuccess", false) || availablePoints > 0 || lifetimePoints > 0
                val source = json.optString("source", "json")

                val pcProgress = json.optInt("pcProgress", 0)
                val pcMax = json.optInt("pcMax", 0)
                val mobileProgress = json.optInt("mobileProgress", 0)
                val mobileMax = json.optInt("mobileMax", 0)
                val edgeProgress = json.optInt("edgeProgress", 0)
                val edgeMax = json.optInt("edgeMax", 0)
                val level = json.optInt("level", if (mobileMax > 0 || pcMax > 30) 2 else 1)
                val effPcMax = if (pcMax > 0) pcMax else (if (level == 1) 30 else 90)
                val effMobMax = if (mobileMax > 0) mobileMax else (if (level == 1) 0 else 60)
                val effEdgeMax = if (edgeMax > 0) edgeMax else (if (level == 1) 0 else 12)
                val isAllMaxedOut = json.optBoolean("isAllMaxedOut", false) || (
                    pcProgress >= effPcMax &&
                    (effMobMax == 0 || mobileProgress >= effMobMax) &&
                    (effEdgeMax == 0 || edgeProgress >= effEdgeMax)
                )

                val email = json.optString("email", "").takeIf { it.isNotBlank() }
                val profileName = json.optString("profileName", "").takeIf { it.isNotBlank() }
                val claimablePoints = json.optInt("claimablePoints", 0)
                val streakProtectionOn = json.optBoolean("streakProtectionOn", false)
                val dailySetDone = json.optBoolean("dailySetDone", false)
                val dailySetProgress = json.optInt("dailySetProgress", if (dailySetDone) 3 else 0)

                return ExtractedRewardsBalance(
                    availablePoints = availablePoints,
                    lifetimePoints = lifetimePoints,
                    streakCount = streakCount,
                    pointsEarnedToday = pointsEarnedToday,
                    isSuccess = isSuccess,
                    source = source,
                    pcProgress = pcProgress,
                    pcMax = pcMax,
                    mobileProgress = mobileProgress,
                    mobileMax = mobileMax,
                    edgeProgress = edgeProgress,
                    edgeMax = edgeMax,
                    isAllMaxedOut = isAllMaxedOut,
                    email = email,
                    profileName = profileName,
                    level = level,
                    claimablePoints = claimablePoints,
                    streakProtectionOn = streakProtectionOn,
                    dailySetDone = dailySetDone,
                    dailySetProgress = dailySetProgress
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ExtractedRewardsBalance(isSuccess = false)
    }

    /**
     * Thực thi bóc tách điểm trên WebView với callback.
     */
    fun extractFromWebView(webView: WebView, onResult: (ExtractedRewardsBalance) -> Unit) {
        val script = createExtractionScript()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(script) { rawOutput ->
                val result = parseResult(rawOutput)
                onResult(result)
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(script) { rawOutput ->
                    val result = parseResult(rawOutput)
                    onResult(result)
                }
            }
        }
    }

    /**
     * Thực thi bóc tách điểm trên WebView dưới dạng suspend function an toàn luồng.
     */
    suspend fun extractFromWebView(webView: WebView): ExtractedRewardsBalance = withContext(AndroidMainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            try {
                val script = createExtractionScript()
                webView.evaluateJavascript(script) { rawOutput ->
                    val parsed = parseResult(rawOutput)
                    if (continuation.isActive) {
                        continuation.resume(parsed)
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(ExtractedRewardsBalance(isSuccess = false))
                }
            }
        }
    }

    /**
     * Alias giao tiếp Worker: extractPoints(webView)
     */
    suspend fun extractPoints(webView: WebView): ExtractedRewardsBalance = extractFromWebView(webView)

    /**
     * Bóc tách điểm nhanh từ trang kết quả tìm kiếm (Bing SERP) qua selector #id_rc.
     */
    suspend fun extractPointsFromSerp(webView: WebView): ExtractedRewardsBalance = withContext(AndroidMainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            try {
                val script = "(function() { var el = document.querySelector('#id_rc'); return el ? (el.innerText || el.textContent || '') : null; })();"
                webView.evaluateJavascript(script) { rawOutput ->
                    if (rawOutput.isNullOrBlank() || rawOutput == "null" || rawOutput == "\"null\"") {
                        if (continuation.isActive) {
                            continuation.resume(ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc"))
                        }
                        return@evaluateJavascript
                    }

                    var sanitized = rawOutput.trim()
                    if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                        sanitized = sanitized.substring(1, sanitized.length - 1)
                    }
                    val cleaned = sanitized.replace(Regex("[^0-9]"), "")
                    val points = cleaned.toIntOrNull()

                    val result = if (points != null && points >= 0) {
                        ExtractedRewardsBalance(
                            availablePoints = points,
                            isSuccess = true,
                            source = "serp_id_rc"
                        )
                    } else {
                        ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc")
                    }

                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc"))
                }
            }
        }
    }

    fun extractPointsFromSerp(webView: WebView, onResult: (ExtractedRewardsBalance) -> Unit) {
        val script = "(function() { var el = document.querySelector('#id_rc'); return el ? (el.innerText || el.textContent || '') : null; })();"
        val runner = {
            webView.evaluateJavascript(script) { rawOutput ->
                if (rawOutput.isNullOrBlank() || rawOutput == "null" || rawOutput == "\"null\"") {
                    onResult(ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc"))
                    return@evaluateJavascript
                }
                var sanitized = rawOutput.trim()
                if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                    sanitized = sanitized.substring(1, sanitized.length - 1)
                }
                val cleaned = sanitized.replace(Regex("[^0-9]"), "")
                val points = cleaned.toIntOrNull()
                if (points != null && points >= 0) {
                    onResult(ExtractedRewardsBalance(availablePoints = points, isSuccess = true, source = "serp_id_rc"))
                } else {
                    onResult(ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc"))
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runner()
        } else {
            Handler(Looper.getMainLooper()).post(runner)
        }
    }

    /**
     * Lọc trích xuất danh sách cookie dành riêng cho miền .bing.com.
     * Chuyển đổi an toàn từ Domain-Scoped JSON Map hoặc chuỗi thô để tránh tràn Header HTTP 400.
     */
    fun filterBingCookies(rawCookies: String?): String {
        if (rawCookies.isNullOrBlank()) return ""

        val trimmed = rawCookies.trim()
        val cookiePairs = mutableListOf<String>()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.contains("bing.com")) {
                        val cookieStr = json.optString(key, "")
                        if (cookieStr.isNotBlank()) {
                            cookieStr.split(";").forEach { p ->
                                val tp = p.trim()
                                if (tp.isNotEmpty() && tp.contains("=")) {
                                    cookiePairs.add(tp)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (cookiePairs.isEmpty()) {
            val pairs = trimmed.split(";")
            for (p in pairs) {
                val tp = p.trim()
                if (tp.isNotEmpty() && tp.contains("=")) {
                    val k = tp.substringBefore("=").trim()
                    if (!k.startsWith("MSP") && !k.startsWith("MSPOK")) {
                        cookiePairs.add(tp)
                    }
                }
            }
        }

        val map = linkedMapOf<String, String>()
        for (cp in cookiePairs) {
            val k = cp.substringBefore("=").trim()
            val v = cp.substringAfter("=").trim()
            if (k.isNotEmpty()) {
                map[k] = v
            }
        }
        return map.map { "${it.key}=${it.value}" }.joinToString("; ")
    }

    /**
     * Bóc tách điểm số trực tiếp qua HTTP GET endpoint rewards.bing.com/dashboard trên Dispatchers.IO.
     * Đây là endpoint chính xác nhất của Microsoft Rewards hiện tại, chứa trực tiếp availablePoints (balance),
     * lifetimeEarn, level, streakCounter, profileName, và email trong HTML streaming payload.
     */
    private fun parseProxy(proxyUrl: String?): java.net.Proxy {
        if (proxyUrl.isNullOrBlank()) return java.net.Proxy.NO_PROXY
        return try {
            val uri = java.net.URI(if (proxyUrl.contains("://")) proxyUrl else "http://$proxyUrl")
            val host = uri.host ?: return java.net.Proxy.NO_PROXY
            val port = if (uri.port != -1) uri.port else 8080
            val type = if (uri.scheme?.startsWith("socks") == true) {
                java.net.Proxy.Type.SOCKS
            } else {
                java.net.Proxy.Type.HTTP
            }
            java.net.Proxy(type, java.net.InetSocketAddress(host, port))
        } catch (e: Exception) {
            java.net.Proxy.NO_PROXY
        }
    }

    suspend fun extractBalanceFromDashboardHttp(
        cookiesSnapshot: String?,
        proxyUrl: String? = null
    ): ExtractedRewardsBalance = withContext(Dispatchers.IO) {
        extractPointsViaHttp(cookiesSnapshot, null, proxyUrl)
    }

    suspend fun extractPointsViaHttp(
        cookiesSnapshot: String?,
        userAgent: String? = null,
        proxyUrl: String? = null
    ): ExtractedRewardsBalance = withContext(Dispatchers.IO) {
        val bingCookies = filterBingCookies(cookiesSnapshot)
        val effectiveUa = if (!userAgent.isNullOrBlank()) userAgent else DEFAULT_DESKTOP_EDGE_UA

        // Ưu tiên Endpoint 1: https://www.bing.com/rewardsapp/flyoutpageinformation (có cấu trúc JSON counters 3 rổ đầy đủ)
        val flyoutResult = queryFlyoutApi(FLYOUT_API_URL, bingCookies, effectiveUa, proxyUrl)
        if (flyoutResult.isSuccess && (flyoutResult.availablePoints > 0 || flyoutResult.lifetimePoints > 0)) {
            return@withContext flyoutResult
        }

        // Fallback Endpoint 2: getuserinfo
        val userInfoResult = queryFlyoutApi(GET_USER_INFO_API_URL, bingCookies, effectiveUa, proxyUrl)
        if (userInfoResult.isSuccess && (userInfoResult.availablePoints > 0 || userInfoResult.lifetimePoints > 0)) {
            return@withContext userInfoResult.copy(source = "userinfo_http")
        }

        // Fallback Endpoint 3: https://rewards.bing.com/dashboard (HTML stream)
        val dashResult = queryRewardsDashboard(DASHBOARD_URL, bingCookies, effectiveUa, proxyUrl)
        if (dashResult.isSuccess && (dashResult.availablePoints > 0 || dashResult.lifetimePoints > 0)) {
            return@withContext dashResult
        }

        return@withContext dashResult
    }

    private fun queryRewardsDashboard(
        endpointUrl: String,
        bingCookies: String,
        userAgent: String,
        proxyUrl: String? = null
    ): ExtractedRewardsBalance {
        var connection: HttpURLConnection? = null
        try {
            val separator = if (endpointUrl.contains("?")) "&" else "?"
            val cacheBuster = "_ts=${System.currentTimeMillis()}&_nonce=${java.util.UUID.randomUUID().toString().take(6)}"
            val url = URL("$endpointUrl$separator$cacheBuster")
            val proxy = parseProxy(proxyUrl)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                if (bingCookies.isNotBlank()) {
                    setRequestProperty("Cookie", bingCookies)
                }
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Referer", "https://rewards.bing.com/")
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (responseCode !in 200..299 || responseBody.isBlank()) {
                return ExtractedRewardsBalance(isSuccess = false, source = "dashboard_http_$responseCode")
            }

            val balMatch = Regex("""balance[^0-9]{1,10}(\d+)""").find(responseBody)
            val availablePoints = balMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val lifeMatch = Regex("""lifetimeEarn[^0-9]{1,10}(\d+)""").find(responseBody)
            val lifetimePoints = lifeMatch?.groupValues?.get(1)?.toIntOrNull() ?: availablePoints

            val lvlMatch = Regex("""level[^0-9]{1,10}(\d+)""").find(responseBody)
            val level = lvlMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val streakMatch = Regex("""streakCounter[^0-9]{1,10}(\d+)""").find(responseBody)
            val streakCount = streakMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val progMatch = Regex("""pointsProgress[^0-9]{1,10}(\d+)""").find(responseBody)
            val pointsProgress = progMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val nameMatch = Regex("""class="[^"]*text-pageHeader[^"]*">([^<]+)<""").find(responseBody)
                ?: Regex("""name[\\":\s]+([^\\",}{]+)""").find(responseBody)
            val profileName = nameMatch?.groupValues?.get(1)?.trim()

            val emailMatch = Regex("""([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""").find(responseBody)
            val email = emailMatch?.groupValues?.get(1)?.trim()

            val claimMatch = Regex("""DashboardHeader_ClaimablePoints[^}]*?points["':\s]+"?(\d+)""").find(responseBody)
                ?: Regex("""pointClaim[^}]*?points["':\s]+(\d+)""").find(responseBody)
                ?: Regex("""claimablePoints[^0-9]{1,10}(\d+)""").find(responseBody)
            val claimablePoints = claimMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val streakProtMatch = Regex("""isProtectionOn["':\s]+(true|false)""").find(responseBody)
                ?: Regex("""isProtectionEnabled["':\s]+(true|false)""").find(responseBody)
                ?: Regex("""streakProtection[^}]*isProtectionOn["':\s]+(true|false)""").find(responseBody)
            val streakProtectionOn = streakProtMatch?.groupValues?.get(1)?.equals("true", ignoreCase = true) == true

            val dailySetItems = Regex("""Global_DailySet_[a-zA-Z0-9_]+""").findAll(responseBody).map { it.value }.toSet()
            val completedMatches = Regex("""Global_DailySet_[^"]*?"isCompleted"\s*:\s*true""").findAll(responseBody).count()
            val dailySetTotal = if (dailySetItems.isNotEmpty()) dailySetItems.size else 3
            val dailySetDone = (completedMatches >= dailySetTotal && dailySetTotal > 0) || responseBody.contains("\"dailyActivityComplete\":")
            val dailySetProgress = completedMatches

            val existing = if (!email.isNullOrBlank()) latestTelemetryMap[email] else null
            val pcMax = existing?.pcMax ?: if (level == 1) 30 else 90
            val pcProgress = existing?.pcProgress ?: minOf(pointsProgress, pcMax)
            val mobileMax = existing?.mobileMax ?: if (level == 1) 0 else 60
            val mobileProgress = existing?.mobileProgress ?: (if (pointsProgress > pcMax) minOf(pointsProgress - pcMax, mobileMax) else 0)
            val edgeMax = existing?.edgeMax ?: if (level == 1) 0 else 12
            val edgeProgress = existing?.edgeProgress ?: (if (pointsProgress > pcMax + mobileProgress) minOf(pointsProgress - pcMax - mobileProgress, edgeMax) else 0)

            // isAllMaxedOut chỉ true khi pcProgress >= pcMax và các rổ mobile/edge đạt trần (hoặc max == 0)
            val isAllMaxedOut = pcProgress >= pcMax && (mobileMax == 0 || mobileProgress >= mobileMax) && (edgeMax == 0 || edgeProgress >= edgeMax)

            val isSuccess = availablePoints > 0 || lifetimePoints > 0

            val result = ExtractedRewardsBalance(
                availablePoints = availablePoints,
                lifetimePoints = lifetimePoints,
                streakCount = streakCount,
                pointsEarnedToday = pointsProgress,
                isSuccess = isSuccess,
                source = "rewards_dashboard_http",
                pcProgress = pcProgress,
                pcMax = pcMax,
                mobileProgress = mobileProgress,
                mobileMax = mobileMax,
                edgeProgress = edgeProgress,
                edgeMax = edgeMax,
                isAllMaxedOut = isAllMaxedOut,
                email = email,
                profileName = profileName,
                level = level,
                claimablePoints = claimablePoints,
                streakProtectionOn = streakProtectionOn,
                dailySetDone = dailySetDone,
                dailySetProgress = dailySetProgress
            )
            if (!email.isNullOrBlank()) {
                latestTelemetryMap[email] = result
            }
            return result
        } catch (e: Exception) {
            return ExtractedRewardsBalance(isSuccess = false, source = "dashboard_http_error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun queryFlyoutApi(
        endpointUrl: String,
        bingCookies: String,
        userAgent: String,
        proxyUrl: String? = null
    ): ExtractedRewardsBalance {
        var connection: HttpURLConnection? = null
        try {
            val separator = if (endpointUrl.contains("?")) "&" else "?"
            val cacheBuster = "_ts=${System.currentTimeMillis()}&_nonce=${java.util.UUID.randomUUID().toString().take(6)}"
            val url = URL("$endpointUrl$separator$cacheBuster")
            val proxy = parseProxy(proxyUrl)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                if (bingCookies.isNotBlank()) {
                    setRequestProperty("Cookie", bingCookies)
                }
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Referer", "https://www.bing.com/")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Sec-Fetch-Site", "same-origin")
                setRequestProperty("Sec-Fetch-Mode", "cors")
                setRequestProperty("Sec-Fetch-Dest", "empty")
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (responseCode !in 200..299 || responseBody.isBlank()) {
                return ExtractedRewardsBalance(
                    isSuccess = false,
                    source = "http_$responseCode"
                )
            }

            val json = JSONObject(responseBody)
            val userStatus = json.optJSONObject("userStatus")
                ?: json.optJSONObject("UserStatus")
                ?: json

            val availablePoints = userStatus.optInt("availablePoints", json.optInt("availablePoints", 0))
            val lifetimePoints = userStatus.optInt("lifetimePoints", json.optInt("lifetimePoints", 0))

            val streakInfo = userStatus.optJSONObject("streakInfo")
                ?: json.optJSONObject("streakInfo")
                ?: userStatus.optJSONObject("dailyStreak")
            val streakCount = streakInfo?.optInt("streakCount", 0)
                ?: userStatus.optInt("streakCount", json.optInt("streakCount", 0))

            var pcProgress = 0
            var pcMax = 0
            var mobileProgress = 0
            var mobileMax = 0
            var edgeProgress = 0
            var edgeMax = 0
            var totalPointsEarned = 0

            val counters = userStatus.optJSONObject("counters") ?: json.optJSONObject("counters")
            val dailyPoint = counters?.optJSONArray("dailyPoint")
            if (dailyPoint != null && dailyPoint.length() > 0) {
                for (i in 0 until dailyPoint.length()) {
                    val item = dailyPoint.optJSONObject(i) ?: continue
                    val prog = item.optInt("pointProgress", item.optInt("progress", 0))
                    val maxP = item.optInt("pointProgressMax", item.optInt("max", item.optInt("maxPoint", 0)))
                    totalPointsEarned += prog
                    val desc = ((item.optString("name", "") + " " + item.optString("description", "") + " " + item.optString("title", ""))).lowercase()
                    if (desc.contains("edge")) {
                        edgeProgress = prog
                        edgeMax = maxP
                    } else if (desc.contains("mobile") || desc.contains("phone")) {
                        mobileProgress = prog
                        mobileMax = maxP
                    } else if (desc.contains("pc") || desc.contains("desktop") || desc.contains("computer")) {
                        pcProgress = prog
                        pcMax = maxP
                    } else {
                        if (i == 0 && pcMax == 0) {
                            pcProgress = prog
                            pcMax = maxP
                        } else if (i == 1 && mobileMax == 0) {
                            mobileProgress = prog
                            mobileMax = maxP
                        } else if (i == 2 && edgeMax == 0) {
                            edgeProgress = prog
                            edgeMax = maxP
                        }
                    }
                }
            } else {
                totalPointsEarned = userStatus.optInt("pointsEarnedToday", 0)
            }

            val email = userStatus.optString("email", json.optString("email", "")).takeIf { it.isNotBlank() }
            val profileName = userStatus.optString("name", json.optString("name", "")).takeIf { it.isNotBlank() }
            val level = userStatus.optInt("level", json.optInt("level", if (mobileMax > 0 || pcMax > 30) 2 else 1))

            val effPcMax = if (pcMax > 0) pcMax else (if (level == 1) 30 else 90)
            val effMobMax = if (mobileMax > 0) mobileMax else (if (level == 1) 0 else 60)
            val effEdgeMax = if (edgeMax > 0) edgeMax else (if (level == 1) 0 else 12)

            val isAllMaxedOut = pcProgress >= effPcMax &&
                (effMobMax == 0 || mobileProgress >= effMobMax) &&
                (effEdgeMax == 0 || edgeProgress >= effEdgeMax)

            val isSuccess = availablePoints > 0 || lifetimePoints > 0

            val pointClaim = userStatus.optJSONObject("pointClaim") ?: json.optJSONObject("pointClaim")
            val claimablePoints = pointClaim?.optInt("points", 0)
                ?: userStatus.optInt("claimablePoints", json.optInt("claimablePoints", 0))

            val streakProt = streakInfo?.optJSONObject("streakProtection")
                ?: userStatus.optJSONObject("streakProtection")
                ?: json.optJSONObject("streakProtection")
            val streakProtectionOn = streakProt?.optBoolean("isProtectionOn", false)
                ?: streakInfo?.optBoolean("isProtectionEnabled", false)
                ?: userStatus.optBoolean("streakProtectionOn", false)

            val dailySetObj = json.optJSONObject("dailySet") ?: userStatus.optJSONObject("dailySet")
            val dailySetDone = dailySetObj?.optBoolean("complete", false) ?: false
            val dailySetProgress = dailySetObj?.optInt("progress", if (dailySetDone) 3 else 0) ?: 0

            val result = ExtractedRewardsBalance(
                availablePoints = availablePoints,
                lifetimePoints = lifetimePoints,
                streakCount = streakCount,
                pointsEarnedToday = if (totalPointsEarned > 0) totalPointsEarned else userStatus.optInt("pointsEarnedToday", 0),
                isSuccess = isSuccess,
                source = "flyout_http",
                pcProgress = pcProgress,
                pcMax = pcMax,
                mobileProgress = mobileProgress,
                mobileMax = mobileMax,
                edgeProgress = edgeProgress,
                edgeMax = edgeMax,
                isAllMaxedOut = isAllMaxedOut,
                email = email,
                profileName = profileName,
                level = level,
                claimablePoints = claimablePoints,
                streakProtectionOn = streakProtectionOn,
                dailySetDone = dailySetDone,
                dailySetProgress = dailySetProgress
            )
            if (!email.isNullOrBlank()) {
                latestTelemetryMap[email] = result
            }
            return result
        } catch (e: Exception) {
            return ExtractedRewardsBalance(isSuccess = false, source = "http_error")
        } finally {
            connection?.disconnect()
        }
    }
}
