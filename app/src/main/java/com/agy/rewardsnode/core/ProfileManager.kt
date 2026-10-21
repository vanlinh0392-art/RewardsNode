package com.agy.rewardsnode.core

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Quản trị phân lập Profile cho WebView thuần Android SDK.
 * Đảm bảo mỗi tài khoản Microsoft sở hữu một Cookie Jar, Cache và WebStorage hoàn toàn tách biệt
 * thông qua cơ chế Domain-Scoped Cookie Map snapshot/restore tự động và Proxy Circuit Breaker.
 */
class ProfileManager(private val context: Context) {

    private val cookieManager: CookieManager = CookieManager.getInstance().apply {
        setAcceptCookie(true)
    }

    private val proxyFailures = ConcurrentHashMap<String, Int>()
    private val circuitBreakerThreshold = 3

    companion object {
        const val BING_SSO_SIGNIN_URL = "https://www.bing.com/fd/auth/signin?action=interactive&provider=windows_live_id&return_url=https%3A%2F%2Fwww.bing.com%2F"

        val CORE_DOMAINS = listOf(
            "https://www.bing.com",
            "https://rewards.bing.com",
            "https://bing.com",
            "https://login.live.com",
            "https://account.microsoft.com"
        )
    }

    /**
     * Kiểm tra xem Cookie Snapshot đã có token xác thực Rewards Bing (_RwBf) chưa.
     */
    fun hasRwBfToken(cookiesSnapshot: String? = null): Boolean {
        val map = parseCookieStringToMap(cookiesSnapshot)
        return !map["_RwBf"].isNullOrBlank()
    }

    fun recordProxyFailure(proxyUrl: String): Boolean {
        val count = proxyFailures.getOrDefault(proxyUrl, 0) + 1
        proxyFailures[proxyUrl] = count
        return count >= circuitBreakerThreshold
    }

    fun resetProxyFailure(proxyUrl: String) {
        proxyFailures.remove(proxyUrl)
    }

    fun recordProxySuccess(proxyUrl: String) {
        resetProxyFailure(proxyUrl)
    }

    fun isProxyCircuitOpen(proxyUrl: String): Boolean {
        return (proxyFailures[proxyUrl] ?: 0) >= circuitBreakerThreshold
    }

    /**
     * Xóa sạch toàn bộ cookie, web storage và cache của phiên đang hoạt động.
     * Sử dụng suspendCancellableCoroutine đợi callback removeAllCookies hoàn tất
     * để loại bỏ hoàn toàn race condition gây rò rỉ session giữa các tài khoản.
     */
    suspend fun clearActiveSession(webView: WebView? = null): Boolean = withContext(AndroidMainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            try {
                cookieManager.removeAllCookies { success ->
                    try {
                        cookieManager.flush()
                        WebStorage.getInstance().deleteAllData()
                        webView?.clearCache(true)
                        webView?.clearFormData()
                        webView?.clearHistory()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    /**
     * Phiên bản bất đồng bộ non-suspending cho các ngữ cảnh gọi ngoài coroutine.
     */
    fun clearActiveSessionAsync(webView: WebView? = null) {
        try {
            cookieManager.removeAllCookies {
                try {
                    cookieManager.flush()
                    WebStorage.getInstance().deleteAllData()
                    webView?.clearCache(true)
                    webView?.clearFormData()
                    webView?.clearHistory()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Nạp lại toàn bộ Cookie đã lưu cho một tài khoản cụ thể vào CookieManager.
     * Hỗ trợ cả Domain-Scoped Cookie JSON Map lẫn chuỗi định dạng cũ.
     * Chờ tất cả các callback setCookie hoàn thành qua suspendCancellableCoroutine.
     */
    suspend fun restoreCookiesForProfile(profileId: String, cookiesSnapshot: String?): Boolean = withContext(AndroidMainDispatcher) {
        if (cookiesSnapshot.isNullOrBlank()) return@withContext false

        val itemsToSet = mutableListOf<Pair<String, String>>()
        val trimmed = cookiesSnapshot.trim()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val domainKey = keys.next()
                    val cookieStr = json.optString(domainKey, "")
                    if (cookieStr.isNotBlank()) {
                        val pairs = cookieStr.split(";")
                        for (pair in pairs) {
                            val trimmedPair = pair.trim()
                            if (trimmedPair.isNotEmpty() && trimmedPair.contains("=")) {
                                val targetDomain = if (domainKey.startsWith("http")) domainKey else "https://$domainKey"
                                val domainAttr = when {
                                    targetDomain.contains("bing.com") -> ".bing.com"
                                    targetDomain.contains("live.com") -> ".live.com"
                                    targetDomain.contains("microsoft.com") -> ".microsoft.com"
                                    else -> null
                                }
                                val finalCookie = if (domainAttr != null && !trimmedPair.contains("Domain=", ignoreCase = true)) {
                                    "$trimmedPair; Domain=$domainAttr; Path=/; Secure"
                                } else {
                                    trimmedPair
                                }
                                itemsToSet.add(Pair(targetDomain, finalCookie))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback cho chuỗi cookie legacy phẳng
        if (itemsToSet.isEmpty()) {
            val pairs = trimmed.split(";")
            for (pair in pairs) {
                val trimmedPair = pair.trim()
                if (trimmedPair.isNotEmpty() && trimmedPair.contains("=")) {
                    for (domain in CORE_DOMAINS) {
                        val domainAttr = when {
                            domain.contains("bing.com") -> ".bing.com"
                            domain.contains("live.com") -> ".live.com"
                            domain.contains("microsoft.com") -> ".microsoft.com"
                            else -> null
                        }
                        val finalCookie = if (domainAttr != null && !trimmedPair.contains("Domain=", ignoreCase = true)) {
                            "$trimmedPair; Domain=$domainAttr; Path=/; Secure"
                        } else {
                            trimmedPair
                        }
                        itemsToSet.add(Pair(domain, finalCookie))
                    }
                }
            }
        }

        if (itemsToSet.isEmpty()) return@withContext false

        suspendCancellableCoroutine { continuation ->
            var remaining = itemsToSet.size
            var allSuccess = true

            for ((domain, cookieVal) in itemsToSet) {
                try {
                    cookieManager.setCookie(domain, cookieVal) { success ->
                        if (!success) allSuccess = false
                        remaining--
                        if (remaining <= 0 && continuation.isActive) {
                            try {
                                cookieManager.flush()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            continuation.resume(allSuccess)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    remaining--
                    if (remaining <= 0 && continuation.isActive) {
                        try {
                            cookieManager.flush()
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                        continuation.resume(false)
                    }
                }
            }
        }
    }

    /**
     * Trích xuất và phân vùng Cookie theo từng Domain (Domain-Scoped Cookie Map).
     * Trả về JSON String: {"https://www.bing.com": "...", "https://login.live.com": "..."}.
     */
    fun dumpCookiesForProfile(profileId: String): String {
        try {
            cookieManager.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val rootJson = JSONObject()
        var hasValidContent = false

        for (domain in CORE_DOMAINS) {
            val cookieStr = cookieManager.getCookie(domain)
            if (!cookieStr.isNullOrBlank()) {
                val cleaned = cookieStr.trim()
                if (cleaned.isNotEmpty()) {
                    rootJson.put(domain, cleaned)
                    hasValidContent = true
                }
            }
        }

        return if (hasValidContent) rootJson.toString() else ""
    }

    /**
     * Chuyển đổi chuỗi Cookie hoặc JSON Cookie Snapshot thành Map<String, String> key-value chính xác.
     * Tự động gọi cookieManager.flush() trước khi đọc từ CookieManager nếu cookieStr là null hoặc rỗng.
     * Sử dụng substringBefore("=") và substringAfter("=") để xử lý chính xác các cookie có chứa dấu '=' (như Base64).
     */
    fun parseCookieStringToMap(cookieStr: String? = null): Map<String, String> {
        try {
            cookieManager.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val rawSource: String = if (cookieStr.isNullOrBlank()) {
            val c1 = cookieManager.getCookie("https://www.bing.com") ?: ""
            val c2 = cookieManager.getCookie("https://rewards.bing.com") ?: ""
            val c3 = cookieManager.getCookie("https://bing.com") ?: ""
            val c4 = cookieManager.getCookie("https://login.live.com") ?: ""
            val c5 = cookieManager.getCookie("https://account.microsoft.com") ?: ""
            "$c1; $c2; $c3; $c4; $c5"
        } else if (cookieStr.trim().startsWith("{")) {
            try {
                val json = JSONObject(cookieStr.trim())
                val sb = StringBuilder()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    sb.append(json.optString(key, "")).append("; ")
                }
                sb.toString()
            } catch (e: Exception) {
                cookieStr
            }
        } else {
            cookieStr
        }

        val cookieMap = linkedMapOf<String, String>()
        val pairs = rawSource.split(";")
        for (pair in pairs) {
            val trimmed = pair.trim()
            if (trimmed.isNotEmpty() && trimmed.contains("=")) {
                val key = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim()
                if (key.isNotEmpty()) {
                    cookieMap[key] = value
                }
            }
        }
        return cookieMap
    }

    /**
     * Kiểm tra phiên đăng nhập hợp lệ đa tầng:
     * - Tầng 1: Cookie _U (Bing user auth cốt lõi)
     * - Tầng 2: Cookie KievRPSToken (Microsoft RPS token)
     * - Tầng 3: Cookie _RwBf (Bing Rewards browser fingerprint/session)
     * - Tầng 4: (MUID && (SRCHUSR hoặc RPS))
     */
    fun hasValidBingSession(cookiesSnapshot: String? = null): Boolean {
        val map = parseCookieStringToMap(cookiesSnapshot)
        return isValidBingSessionMap(map)
    }

    /**
     * Overload kiểm tra theo Map<String, String>.
     */
    fun hasValidBingSession(cookiesMap: Map<String, String>): Boolean {
        if (cookiesMap.isEmpty()) return false
        val isDomainMap = cookiesMap.keys.any { it.startsWith("http") || it.contains(".") }
        val effectiveMap = if (isDomainMap) {
            val combined = cookiesMap.values.joinToString("; ")
            parseCookieStringToMap(combined)
        } else {
            cookiesMap
        }
        return isValidBingSessionMap(effectiveMap)
    }

    private fun isValidBingSessionMap(map: Map<String, String>): Boolean {
        if (map.isEmpty()) return false

        // 1. _U token
        val uVal = map["_U"] ?: map.entries.firstOrNull { it.key.equals("_U", ignoreCase = true) }?.value
        if (!uVal.isNullOrBlank() && uVal != ";" && uVal != "\"\"") {
            Log.i("ProfileManager", "Bing Session Validated: Tier 1 (_U token present)")
            return true
        }

        // 2. KievRPSToken
        val kievVal = map["KievRPSToken"] ?: map.entries.firstOrNull { it.key.equals("KievRPSToken", ignoreCase = true) }?.value
        if (!kievVal.isNullOrBlank() && kievVal != ";" && kievVal != "\"\"") {
            Log.i("ProfileManager", "Bing Session Validated: Tier 2 (KievRPSToken present)")
            return true
        }

        // 3. _RwBf
        val rwbfVal = map["_RwBf"] ?: map.entries.firstOrNull { it.key.equals("_RwBf", ignoreCase = true) }?.value
        if (!rwbfVal.isNullOrBlank() && rwbfVal != ";" && rwbfVal != "\"\"") {
            Log.i("ProfileManager", "Bing Session Validated: Tier 3 (_RwBf Rewards browser session present)")
            return true
        }

        // 4. (MUID && (SRCHUSR || RPS))
        val muidVal = map["MUID"] ?: map.entries.firstOrNull { it.key.equals("MUID", ignoreCase = true) }?.value
        val hasMuid = !muidVal.isNullOrBlank() && muidVal != ";" && muidVal != "\"\""

        val hasSrchUsr = map.entries.any {
            it.key.contains("SRCHUSR", ignoreCase = true) && it.value.isNotBlank() && it.value != ";"
        }
        val hasRps = map.entries.any {
            it.key.contains("RPS", ignoreCase = true) && it.value.isNotBlank() && it.value != ";"
        }

        val isValid = hasMuid && (hasSrchUsr || hasRps)
        if (isValid) {
            Log.i("ProfileManager", "Bing Session Validated: Tier 4 (MUID + SRCHUSR/RPS present)")
        } else {
            Log.d("ProfileManager", "Bing Session NOT yet valid. Detected keys: ${map.keys.take(10)}")
        }
        return isValid
    }

    /**
     * Trích xuất và lọc riêng các Cookie thuộc phạm vi domain .bing.com để gửi HTTP Request độc lập.
     */
    fun extractBingCookies(cookiesSnapshot: String? = null): String {
        val rawSource = if (cookiesSnapshot.isNullOrBlank()) {
            val c1 = cookieManager.getCookie("https://www.bing.com") ?: ""
            val c2 = cookieManager.getCookie("https://rewards.bing.com") ?: ""
            val c3 = cookieManager.getCookie("https://bing.com") ?: ""
            "$c1; $c2; $c3"
        } else if (cookiesSnapshot.trim().startsWith("{")) {
            try {
                val json = JSONObject(cookiesSnapshot.trim())
                val sb = StringBuilder()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.contains("bing.com")) {
                        sb.append(json.optString(key, "")).append("; ")
                    }
                }
                sb.toString()
            } catch (e: Exception) {
                cookiesSnapshot
            }
        } else {
            cookiesSnapshot
        }

        val cookieMap = parseCookieStringToMap(rawSource)
        val filtered = cookieMap.filter { !it.key.startsWith("MSP") }
        return filtered.map { "${it.key}=${it.value}" }.joinToString("; ")
    }
}
