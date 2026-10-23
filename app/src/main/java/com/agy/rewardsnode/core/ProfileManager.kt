package com.agy.rewardsnode.core

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

    @Volatile
    var activeAccountId: String? = null

    @Volatile
    var activeEffectiveProxy: String? = null

    private val proxyFailures = ConcurrentHashMap<String, Int>()
    private val circuitBreakerThreshold: Int
        get() = try {
            com.agy.rewardsnode.data.EncryptedPreferences(context).proxyCircuitThreshold
        } catch (_: Throwable) {
            3
        }

    companion object {
        const val BING_SSO_SIGNIN_URL = "https://www.bing.com/fd/auth/signin?action=interactive&provider=windows_live_id&return_url=https%3A%2F%2Fwww.bing.com%2F"
        const val SYNTHETIC_MAX_AGE_SECONDS = 7776000L // 90 ngày (90 * 24 * 3600)

        private val sharedProxyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        val sessionIsolationMutex = Mutex()

        @Volatile
        var isInteractiveLoginActive: Boolean = false

        @Volatile
        var interactiveLoginProfileId: String? = null

        fun resolveEffectiveProxy(accountProxy: String?, globalProxy: String?): String? =
            accountProxy?.trim()?.takeIf { it.isNotEmpty() } ?: globalProxy?.trim()?.takeIf { it.isNotEmpty() }

        val AUTH_TOKENS = setOf(
            "_RwBf", "_U", "MSPAuth", "KievRPSToken", "MSPOK",
            "__Host-MSAAUTHP", "__Host-MSAAUTH", "WLSSC", ".MSA.Auth",
            "PPLState", "MSPProf", "MSPCID", "NAP", "ANON",
            "SDIDC", "JSH", "JSHP", "WLS"
        )

        val CORE_DOMAINS = listOf(
            "https://www.bing.com",
            "https://rewards.bing.com",
            "https://bing.com",
            "https://login.live.com",
            "https://account.microsoft.com"
        )

        private fun isTransientOAuthNonceCookie(cookieName: String): Boolean {
            return cookieName.startsWith(".MSA.Auth.Nonce", ignoreCase = true) ||
                cookieName.startsWith(".MSA.Auth.Correlation", ignoreCase = true)
        }

        /**
         * Làm sạch Domain Cookie Map:
         * - Loại bỏ các cookie tạm thời `.MSA.Auth.Nonce.*` và `.MSA.Auth.Correlation.*` gây tràn header HTTP 400.
         * - Khử trùng lặp (deduplicate) theo tên cookie trên từng domain (giữ lại giá trị hợp lệ cuối cùng).
         */
        fun sanitizeCookieMap(cookiesJson: String): String {
            val trimmed = cookiesJson.trim()
            if (trimmed.isEmpty()) return ""

            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return try {
                    val inputJson = JSONObject(trimmed)
                    val outputJson = JSONObject()
                    val keys = inputJson.keys()
                    while (keys.hasNext()) {
                        val domain = keys.next()
                        val rawCookies = inputJson.optString(domain, "")
                        if (rawCookies.isNotBlank()) {
                            val dedupMap = linkedMapOf<String, String>()
                            for (pair in rawCookies.split(";")) {
                                val tp = pair.trim()
                                if (tp.isNotEmpty() && tp.contains("=")) {
                                    val name = tp.substringBefore("=").trim()
                                    val value = tp.substringAfter("=").trim()
                                    if (name.isNotEmpty() &&
                                        !isTransientOAuthNonceCookie(name) &&
                                        value.isNotEmpty() &&
                                        value != ";" &&
                                        value != "\"\""
                                    ) {
                                        dedupMap[name] = value
                                    }
                                }
                            }
                            if (dedupMap.isNotEmpty()) {
                                val cleanedStr = dedupMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                                outputJson.put(domain, cleanedStr)
                            }
                        }
                    }
                    if (outputJson.length() > 0) outputJson.toString() else ""
                } catch (e: Exception) {
                    Log.w("ProfileManager", "Lỗi sanitizeCookieMap JSON: ${e.message}")
                    trimmed
                }
            }

            // Fallback cho chuỗi cookie phẳng
            val dedupMap = linkedMapOf<String, String>()
            for (pair in trimmed.split(";")) {
                val tp = pair.trim()
                if (tp.isNotEmpty() && tp.contains("=")) {
                    val name = tp.substringBefore("=").trim()
                    val value = tp.substringAfter("=").trim()
                    if (name.isNotEmpty() &&
                        !isTransientOAuthNonceCookie(name) &&
                        value.isNotEmpty() &&
                        value != ";" &&
                        value != "\"\""
                    ) {
                        dedupMap[name] = value
                    }
                }
            }
            return dedupMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        /**
         * Hợp nhất (Additive Domain Cookie Merge) giữa snapshot hiện có của tài khoản và snapshot mới:
         * - Bảo toàn 100% các cookie xác thực gốc trên `https://login.live.com` và `https://account.microsoft.com`
         *   khi `freshSnapshot` chỉ cập nhật từ trang `bing.com` hoặc `rewards.bing.com`.
         * - Không bao giờ làm rơi mất `__Host-MSAAUTHP`, `MSPAuth`, `WLSSC`, `MSPOK`, `_U`, `_RwBf` của tài khoản.
         */
        fun mergeCookieSnapshots(existingSnapshot: String?, freshSnapshot: String?): String {
            val cleanExisting = sanitizeCookieMap(existingSnapshot ?: "")
            val cleanFresh = sanitizeCookieMap(freshSnapshot ?: "")
            if (cleanExisting.isBlank()) return cleanFresh
            if (cleanFresh.isBlank()) return cleanExisting

            val domainBuckets = linkedMapOf<String, MutableMap<String, String>>()
            for (domain in CORE_DOMAINS) {
                domainBuckets[domain] = linkedMapOf()
            }

            fun ingestSnapshot(snapshot: String) {
                val trimmed = snapshot.trim()
                if (trimmed.isEmpty()) return
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        val json = JSONObject(trimmed)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val rawKey = keys.next()
                            val normalizedDomain = if (rawKey.startsWith("http://") || rawKey.startsWith("https://")) {
                                rawKey
                            } else {
                                "https://$rawKey"
                            }
                            val bucket = domainBuckets.getOrPut(normalizedDomain) { linkedMapOf() }
                            val rawStr = json.optString(rawKey, "")
                            for (pair in rawStr.split(";")) {
                                val tp = pair.trim()
                                if (tp.isNotEmpty() && tp.contains("=")) {
                                    val k = tp.substringBefore("=").trim()
                                    val v = tp.substringAfter("=").trim()
                                    if (k.isNotEmpty() && !isTransientOAuthNonceCookie(k) && v.isNotEmpty() && v != ";" && v != "\"\"") {
                                        bucket[k] = v
                                    }
                                }
                            }
                        }
                        return
                    } catch (_: Exception) {
                    }
                }

                // Fallback cho chuỗi cookie phẳng
                for (pair in trimmed.split(";")) {
                    val tp = pair.trim()
                    if (tp.isNotEmpty() && tp.contains("=")) {
                        val k = tp.substringBefore("=").trim()
                        val v = tp.substringAfter("=").trim()
                        if (k.isNotEmpty() && !isTransientOAuthNonceCookie(k) && v.isNotEmpty() && v != ";" && v != "\"\"") {
                            val targets = when {
                                k.startsWith("__Host-", ignoreCase = true) ||
                                    k.startsWith("MSP", ignoreCase = true) ||
                                    k.equals("WLSSC", ignoreCase = true) ||
                                    k.equals("SDIDC", ignoreCase = true) ||
                                    k.equals("JSH", ignoreCase = true) ||
                                    k.equals("JSHP", ignoreCase = true) ||
                                    k.equals("PPLState", ignoreCase = true) ||
                                    k.equals("NAP", ignoreCase = true) ||
                                    k.equals("ANON", ignoreCase = true) ||
                                    k.equals(".MSA.Auth", ignoreCase = true) -> listOf("https://login.live.com", "https://account.microsoft.com")
                                k.equals("_U", ignoreCase = true) || k.equals("_RwBf", ignoreCase = true) -> listOf("https://www.bing.com", "https://rewards.bing.com", "https://bing.com")
                                else -> CORE_DOMAINS
                            }
                            for (d in targets) {
                                domainBuckets.getOrPut(d) { linkedMapOf() }[k] = v
                            }
                        }
                    }
                }
            }

            ingestSnapshot(cleanExisting)
            ingestSnapshot(cleanFresh)

            val outputJson = JSONObject()
            for ((domain, map) in domainBuckets) {
                if (map.isNotEmpty()) {
                    outputJson.put(domain, map.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }
            return if (outputJson.length() > 0) sanitizeCookieMap(outputJson.toString()) else cleanFresh
        }

        /**
         * Gia hạn phiên Cookie ngầm qua thuần HTTP (Zero-WebView Background Renewal):
         * - Gửi HTTP GET với `followRedirects=false` tới `BING_SSO_SIGNIN_URL` và `https://rewards.bing.com/dashboard`.
         * - Tự động bắt và POST form ẩn `<form id="fmHF" action="...">` (OAuth2/RPS handshake) để thu hoạch
         *   `Set-Cookie` mới (`.MSA.Auth`, `rn_SID`, `_U`, `_RwBf`, `__Host-MSAAUTHP`, `WLSSC`).
         * - Trả về JSON Cookie Map đã làm sạch nếu có `_U` hoặc `.MSA.Auth` hoặc `rn_SID`, ngược lại trả về `null`.
         */
        fun renewCookiesViaPureHttp(
            cookiesSnapshot: String,
            userAgent: String? = null,
            proxyUrl: String? = null
        ): String? {
            val sanitizedInput = sanitizeCookieMap(cookiesSnapshot)
            if (sanitizedInput.isBlank()) return null

            val effectiveUa = if (!userAgent.isNullOrBlank()) {
                userAgent
            } else {
                "Mozilla/5.0 (Linux; Android 14; 23127PN0CG Build/UKQ1.230804.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.137 Mobile Safari/537.36 EdgA/133.0.3065.92"
            }

            val domainCookies = linkedMapOf<String, MutableMap<String, String>>()
            for (domain in CORE_DOMAINS) {
                domainCookies[domain] = linkedMapOf()
            }

            // Khởi tạo từ snapshot hiện có
            if (sanitizedInput.startsWith("{") && sanitizedInput.endsWith("}")) {
                try {
                    val json = JSONObject(sanitizedInput)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val normalizedDomain = if (key.startsWith("http://") || key.startsWith("https://")) key else "https://$key"
                        val map = domainCookies.getOrPut(normalizedDomain) { linkedMapOf() }
                        val cStr = json.optString(key, "")
                        for (pair in cStr.split(";")) {
                            val tp = pair.trim()
                            if (tp.isNotEmpty() && tp.contains("=")) {
                                val k = tp.substringBefore("=").trim()
                                val v = tp.substringAfter("=").trim()
                                if (k.isNotEmpty() && !isTransientOAuthNonceCookie(k) && v.isNotEmpty()) {
                                    map[k] = v
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ProfileManager", "Lỗi parse JSON trong renewCookiesViaPureHttp: ${e.message}")
                    return null
                }
            } else {
                for (pair in sanitizedInput.split(";")) {
                    val tp = pair.trim()
                    if (tp.isNotEmpty() && tp.contains("=")) {
                        val k = tp.substringBefore("=").trim()
                        val v = tp.substringAfter("=").trim()
                        if (k.isNotEmpty() && !isTransientOAuthNonceCookie(k) && v.isNotEmpty()) {
                            for (domain in CORE_DOMAINS) {
                                domainCookies.getOrPut(domain) { linkedMapOf() }[k] = v
                            }
                        }
                    }
                }
            }

            val proxy = try {
                if (proxyUrl.isNullOrBlank()) {
                    java.net.Proxy.NO_PROXY
                } else {
                    val uri = java.net.URI(if (proxyUrl.contains("://")) proxyUrl else "http://$proxyUrl")
                    val host = uri.host
                    val port = if (uri.port != -1) uri.port else 8080
                    if (host.isNullOrBlank()) {
                        java.net.Proxy.NO_PROXY
                    } else {
                        val type = if (uri.scheme?.startsWith("socks") == true) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
                        java.net.Proxy(type, java.net.InetSocketAddress(host, port))
                    }
                }
            } catch (_: Exception) {
                java.net.Proxy.NO_PROXY
            }

            fun buildCookieHeaderForUrl(url: java.net.URL): String {
                val host = url.host.lowercase()
                val merged = linkedMapOf<String, String>()
                when {
                    host.contains("rewards.bing.com") -> {
                        domainCookies["https://bing.com"]?.forEach { (k, v) ->
                            if (!k.startsWith("__Host-")) merged[k] = v
                        }
                        domainCookies["https://www.bing.com"]?.forEach { (k, v) ->
                            if (!k.startsWith("__Host-")) merged[k] = v
                        }
                        domainCookies["https://rewards.bing.com"]?.forEach { (k, v) -> merged[k] = v }
                    }
                    host.contains("bing.com") -> {
                        domainCookies["https://bing.com"]?.forEach { (k, v) -> merged[k] = v }
                        domainCookies["https://www.bing.com"]?.forEach { (k, v) -> merged[k] = v }
                    }
                    host.contains("live.com") -> {
                        domainCookies["https://login.live.com"]?.forEach { (k, v) -> merged[k] = v }
                    }
                    host.contains("microsoft.com") -> {
                        domainCookies["https://login.live.com"]?.forEach { (k, v) ->
                            if (!k.startsWith("__Host-")) merged[k] = v
                        }
                        domainCookies["https://account.microsoft.com"]?.forEach { (k, v) -> merged[k] = v }
                    }
                }
                return merged.entries
                    .filter { !isTransientOAuthNonceCookie(it.key) && it.value.isNotBlank() }
                    .joinToString("; ") { "${it.key}=${it.value}" }
            }

            fun harvestSetCookies(conn: java.net.HttpURLConnection, requestUrl: java.net.URL) {
                val headers = conn.headerFields ?: return
                val host = requestUrl.host.lowercase()
                for ((headerKey, headerValues) in headers) {
                    if (headerKey != null && (headerKey.equals("Set-Cookie", ignoreCase = true) || headerKey.equals("Set-Cookie2", ignoreCase = true))) {
                        for (rawCookie in headerValues) {
                            if (rawCookie.isNullOrBlank()) continue
                            val firstPart = rawCookie.substringBefore(";").trim()
                            if (!firstPart.contains("=")) continue
                            val name = firstPart.substringBefore("=").trim()
                            val value = firstPart.substringAfter("=").trim()
                            if (name.isEmpty() || isTransientOAuthNonceCookie(name)) continue

                            val domainAttr = Regex("(?i);\\s*domain=([^;]+)").find(rawCookie)?.groupValues?.get(1)?.trim()?.lowercase() ?: ""
                            val isExpiredOrDeleted = value.isEmpty() ||
                                value.equals("deleted", ignoreCase = true) ||
                                Regex("(?i);\\s*max-age\\s*=\\s*0\\b").containsMatchIn(rawCookie)

                            val targetBuckets = mutableListOf<String>()
                            when {
                                domainAttr.contains("rewards.bing.com") || (domainAttr.isEmpty() && host.contains("rewards.bing.com")) -> {
                                    targetBuckets.add("https://rewards.bing.com")
                                    if (name.equals("_U", ignoreCase = true) || name.equals("_RwBf", ignoreCase = true) || name.equals("KievRPSToken", ignoreCase = true)) {
                                        targetBuckets.add("https://www.bing.com")
                                        targetBuckets.add("https://bing.com")
                                    }
                                }
                                domainAttr.contains("bing.com") || host.contains("bing.com") -> {
                                    targetBuckets.add("https://www.bing.com")
                                    targetBuckets.add("https://bing.com")
                                    if (!name.startsWith("__Host-")) {
                                        targetBuckets.add("https://rewards.bing.com")
                                    }
                                }
                                domainAttr.contains("live.com") || host.contains("live.com") -> {
                                    targetBuckets.add("https://login.live.com")
                                }
                                domainAttr.contains("microsoft.com") || host.contains("microsoft.com") -> {
                                    targetBuckets.add("https://account.microsoft.com")
                                }
                            }

                            for (bucket in targetBuckets) {
                                val map = domainCookies.getOrPut(bucket) { linkedMapOf() }
                                if (isExpiredOrDeleted) {
                                    map.remove(name)
                                } else {
                                    map[name] = value
                                }
                            }
                        }
                    }
                }
            }

            fun unescapeHtml(input: String): String {
                return input
                    .replace("&amp;", "&")
                    .replace("&#38;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
            }

            fun executeRenewalFlow(initialUrl: String) {
                var currentUrl = try { java.net.URL(initialUrl) } catch (_: Exception) { return }
                var method = "GET"
                var postBody: String? = null
                var step = 0

                while (step < 10) {
                    step++
                    var conn: java.net.HttpURLConnection? = null
                    try {
                        conn = (currentUrl.openConnection(proxy) as java.net.HttpURLConnection).apply {
                            requestMethod = method
                            connectTimeout = 8000
                            readTimeout = 8000
                            instanceFollowRedirects = false
                            useCaches = false
                            setRequestProperty("User-Agent", effectiveUa)
                            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                            val cookieHeader = buildCookieHeaderForUrl(currentUrl)
                            if (cookieHeader.isNotBlank()) {
                                setRequestProperty("Cookie", cookieHeader)
                            }
                            if (method == "POST" && postBody != null) {
                                doOutput = true
                                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                                val bytes = postBody!!.toByteArray(Charsets.UTF_8)
                                setRequestProperty("Content-Length", bytes.size.toString())
                                outputStream.use { os -> os.write(bytes) }
                            }
                        }

                        val status = conn.responseCode
                        harvestSetCookies(conn, currentUrl)

                        if (status in listOf(301, 302, 303, 307, 308)) {
                            val location = conn.getHeaderField("Location")
                            if (location.isNullOrBlank()) break
                            currentUrl = java.net.URL(currentUrl, location)
                            method = "GET"
                            postBody = null
                            continue
                        }

                        if (status in 200..299) {
                            val body = try {
                                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            } catch (_: Exception) {
                                ""
                            }

                            // Kiểm tra auto-submit form <form ... id="fmHF" action="...">
                            val formTagMatch = Regex("(?is)<form[^>]*\\bid\\s*=\\s*[\"']fmHF[\"'][^>]*>").find(body)
                            if (formTagMatch != null) {
                                val formTag = formTagMatch.value
                                val actionRaw = Regex("(?is)\\baction\\s*=\\s*[\"']([^\"']+)[\"']").find(formTag)?.groupValues?.get(1)
                                if (!actionRaw.isNullOrBlank()) {
                                    val actionUrl = java.net.URL(currentUrl, unescapeHtml(actionRaw))
                                    val formInputs = mutableListOf<String>()
                                    val inputRegex = Regex("(?is)<input\\b[^>]*>")
                                    for (inputMatch in inputRegex.findAll(body)) {
                                        val tag = inputMatch.value
                                        val name = Regex("(?is)\\bname\\s*=\\s*[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1)
                                        val value = Regex("(?is)\\bvalue\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1) ?: ""
                                        if (!name.isNullOrBlank()) {
                                            val encodedName = java.net.URLEncoder.encode(unescapeHtml(name), "UTF-8")
                                            val encodedVal = java.net.URLEncoder.encode(unescapeHtml(value), "UTF-8")
                                            formInputs.add("$encodedName=$encodedVal")
                                        }
                                    }
                                    if (formInputs.isNotEmpty()) {
                                        currentUrl = actionUrl
                                        method = "POST"
                                        postBody = formInputs.joinToString("&")
                                        continue
                                    }
                                }
                            }
                        }
                        break
                    } catch (e: Exception) {
                        Log.w("ProfileManager", "Lỗi bước $step trong renewCookiesViaPureHttp ($currentUrl): ${e.message}")
                        break
                    } finally {
                        try {
                            conn?.disconnect()
                        } catch (_: Exception) {}
                    }
                }
            }

            executeRenewalFlow(BING_SSO_SIGNIN_URL)
            executeRenewalFlow("https://rewards.bing.com/dashboard")

            val resultJson = JSONObject()
            var hasAuthCookie = false
            for ((domain, map) in domainCookies) {
                if (map.isNotEmpty()) {
                    if (map.keys.any {
                            it.equals("_U", ignoreCase = true) ||
                                it.equals(".MSA.Auth", ignoreCase = true) ||
                                it.equals("rn_SID", ignoreCase = true)
                        }
                    ) {
                        hasAuthCookie = true
                    }
                    resultJson.put(domain, map.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }

            if (!hasAuthCookie) return null
            val mergedJson = mergeCookieSnapshots(cookiesSnapshot, resultJson.toString())
            return mergedJson.takeIf { it.isNotBlank() }
        }

        fun buildSyntheticCookieString(cookiePair: String): String {
            val trimmedPair = cookiePair.trim()
            val rawCookiePair = trimmedPair.substringBefore(";").trim()
            val cookieName = rawCookiePair.substringBefore("=").trim()
            if (cookieName.startsWith("__Host-")) {
                return "$rawCookiePair; Max-Age=$SYNTHETIC_MAX_AGE_SECONDS; Path=/; Secure; SameSite=None"
            }
            val isAuthToken = AUTH_TOKENS.any { it.equals(cookieName, ignoreCase = true) }
            val builder = StringBuilder(trimmedPair)

            if (isAuthToken) {
                // Tiêm synthetic Max-Age 90 ngày (7776000s), Secure, SameSite=None cho auth tokens
                if (!trimmedPair.contains("Max-Age=", ignoreCase = true) && !trimmedPair.contains("Expires=", ignoreCase = true)) {
                    builder.append("; Max-Age=$SYNTHETIC_MAX_AGE_SECONDS")
                }
                if (!trimmedPair.contains("Secure", ignoreCase = true)) {
                    builder.append("; Secure")
                }
                if (!trimmedPair.contains("SameSite=", ignoreCase = true)) {
                    builder.append("; SameSite=None")
                }
            }

            if (!trimmedPair.contains("Path=", ignoreCase = true)) {
                builder.append("; Path=/")
            }

            return builder.toString()
        }
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

    private val loopbackBridge = LoopbackProxyBridge.getInstance()

    fun getLoopbackBridge(): LoopbackProxyBridge = loopbackBridge

    /**
     * Định tuyến lưu lượng mạng của WebView và tiến trình qua LoopbackProxyBridge.
     * Áp dụng phối hợp đa tầng (System Properties + Android Proxy Intent + Chromium Reflection + WebKit Reflection).
     */
    suspend fun routeWebViewViaProxy(webView: WebView?, proxyUrl: String?): Boolean = withContext(AndroidMainDispatcher) {
        if (proxyUrl.isNullOrBlank()) {
            clearWebViewProxy(webView)
            return@withContext true
        }

        try {
            // Restart LoopbackProxyBridge để cấp phát port mới (socket swap), ép Chromium hủy SpdySessionPool và TLS session tickets cũ
            val localPort = loopbackBridge.restart(proxyUrl)
            if (localPort <= 0) {
                recordProxyFailure(proxyUrl)
                return@withContext false
            }

            applyProxyToSystemAndWebView(context, "127.0.0.1", localPort)
            recordProxySuccess(proxyUrl)
            true
        } catch (e: Exception) {
            Log.e("ProfileManager", "Lỗi khởi chạy LoopbackProxyBridge cho proxy: $proxyUrl - ${e.message}")
            recordProxyFailure(proxyUrl)
            false
        }
    }

    /**
     * Hủy bỏ định tuyến proxy trên WebView và khôi phục kết nối mạng trực tiếp (Direct IP).
     */
    fun clearWebViewProxy(webView: WebView? = null) {
        try {
            loopbackBridge.stop()
            clearProxyFromSystemAndWebView(context)
        } catch (e: Exception) {
            Log.e("ProfileManager", "Lỗi khi xóa WebView proxy: ${e.message}")
        }
    }

    private fun applyProxyToSystemAndWebView(ctx: Context, host: String, port: Int) {
        // 1. Thiết lập System properties (ảnh hưởng HttpURLConnection và socket layer)
        System.setProperty("http.proxyHost", host)
        System.setProperty("http.proxyPort", port.toString())
        System.setProperty("https.proxyHost", host)
        System.setProperty("https.proxyPort", port.toString())

        // 2. Broadcast PROXY_CHANGE_ACTION với ProxyInfo chuẩn Android SDK
        try {
            val proxyInfo = android.net.ProxyInfo.buildDirectProxy(host, port)
            val intent = android.content.Intent(android.net.Proxy.PROXY_CHANGE_ACTION).apply {
                putExtra("android.intent.extra.PROXY_INFO", proxyInfo)
                putExtra("proxy", proxyInfo)
            }
            ctx.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w("ProfileManager", "Không thể broadcast PROXY_CHANGE_ACTION: ${e.message}")
        }

        // 3. Kích hoạt trực tiếp Chromium ProxyChangeListener trong tiến trình qua Reflection (LoadedApk)
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAtMethod = atClass.getDeclaredMethod("currentActivityThread")
            currentAtMethod.isAccessible = true
            val activityThread = currentAtMethod.invoke(null)
            val appField = atClass.getDeclaredField("mInitialApplication")
            appField.isAccessible = true
            val app = appField.get(activityThread) as? android.app.Application ?: ctx.applicationContext

            val mLoadedApkField = app.javaClass.getField("mLoadedApk")
            mLoadedApkField.isAccessible = true
            val loadedApk = mLoadedApkField.get(app)

            val mReceiversField = loadedApk.javaClass.getDeclaredField("mReceivers")
            mReceiversField.isAccessible = true
            val receivers = mReceiversField.get(loadedApk) as? Map<*, *>

            val proxyInfo = android.net.ProxyInfo.buildDirectProxy(host, port)
            val proxyIntent = android.content.Intent(android.net.Proxy.PROXY_CHANGE_ACTION).apply {
                putExtra("android.intent.extra.PROXY_INFO", proxyInfo)
            }

            receivers?.values?.forEach { map ->
                (map as? Map<*, *>)?.keys?.forEach { receiver ->
                    if (receiver?.javaClass?.name?.contains("ProxyChangeListener") == true) {
                        val onReceiveMethod = receiver.javaClass.getDeclaredMethod("onReceive", Context::class.java, android.content.Intent::class.java)
                        onReceiveMethod.isAccessible = true
                        onReceiveMethod.invoke(receiver, ctx, proxyIntent)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 4. AndroidX WebKit ProxyController qua Reflection (nếu runtime có sẵn)
        try {
            val proxyControllerClass = Class.forName("androidx.webkit.ProxyController")
            val getInstanceMethod = proxyControllerClass.getMethod("getInstance")
            val controller = getInstanceMethod.invoke(null)

            val proxyConfigBuilderClass = Class.forName("androidx.webkit.ProxyConfig\$Builder")
            val builder = proxyConfigBuilderClass.getConstructor().newInstance()
            val addProxyRuleMethod = proxyConfigBuilderClass.getDeclaredMethod("addProxyRule", String::class.java)
            addProxyRuleMethod.invoke(builder, "$host:$port")
            val buildMethod = proxyConfigBuilderClass.getDeclaredMethod("build")
            val config = buildMethod.invoke(builder)

            val runnable = Runnable {}
            val setProxyOverrideMethod = proxyControllerClass.getDeclaredMethod(
                "setProxyOverride",
                Class.forName("androidx.webkit.ProxyConfig"),
                java.util.concurrent.Executor::class.java,
                Runnable::class.java
            )
            setProxyOverrideMethod.invoke(controller, config, sharedProxyExecutor, runnable)
        } catch (_: Throwable) {
        }
    }

    private fun clearProxyFromSystemAndWebView(ctx: Context) {
        System.clearProperty("http.proxyHost")
        System.clearProperty("http.proxyPort")
        System.clearProperty("https.proxyHost")
        System.clearProperty("https.proxyPort")

        try {
            val intent = android.content.Intent(android.net.Proxy.PROXY_CHANGE_ACTION)
            ctx.sendBroadcast(intent)
        } catch (_: Exception) {}

        try {
            val proxyControllerClass = Class.forName("androidx.webkit.ProxyController")
            val getInstanceMethod = proxyControllerClass.getMethod("getInstance")
            val controller = getInstanceMethod.invoke(null)
            val runnable = Runnable {}
            val clearMethod = proxyControllerClass.getDeclaredMethod("clearProxyOverride", java.util.concurrent.Executor::class.java, Runnable::class.java)
            clearMethod.invoke(controller, sharedProxyExecutor, runnable)
        } catch (_: Throwable) {}
    }

    /**
     * Xóa sạch toàn bộ cookie, web storage và cache của phiên đang hoạt động.
     * Sử dụng suspendCancellableCoroutine đợi callback removeAllCookies hoàn tất
     * để loại bỏ hoàn toàn race condition gây rò rỉ session giữa các tài khoản.
     */
    /**
     * Hoán đổi phiên làm việc giữa các tài khoản Microsoft tuần tự và an toàn (G4.1 Sequential Isolation).
     * Sử dụng NonCancellable và withTimeoutOrNull(8000L) bên trong sessionIsolationMutex để loại trừ
     * hoàn toàn tình trạng deadlock và triệt tiêu nguy cơ rò rỉ session / cookie chéo giữa các profile.
     */
    suspend fun switchSession(
        webView: WebView?,
        targetAccount: AccountProfile,
        globalProxy: String? = null,
        onSaveOutgoingSession: (suspend (accountId: String, cookies: String) -> Unit)? = null
    ): Boolean = withContext(NonCancellable) {
        if (isInteractiveLoginActive && targetAccount.id != interactiveLoginProfileId) {
            Log.w("ProfileManager", "Chặn switchSession ngầm cho ${targetAccount.id} vì đang mở LoginDialog (interactiveLoginProfileId=$interactiveLoginProfileId)")
            return@withContext false
        }
        val result = withTimeoutOrNull(15000L) {
            sessionIsolationMutex.withLock {
                if (isInteractiveLoginActive && targetAccount.id != interactiveLoginProfileId) {
                    Log.w("ProfileManager", "Chặn switchSession ngầm (trong mutex) cho ${targetAccount.id} vì đang mở LoginDialog")
                    return@withLock false
                }
                val effectiveProxy = resolveEffectiveProxy(targetAccount.proxyUrl, globalProxy)
                if (activeAccountId == targetAccount.id && activeEffectiveProxy == effectiveProxy) {
                    return@withLock true
                }

                Log.i("ProfileManager", "Hoán đổi session từ $activeAccountId sang ${targetAccount.id} (${targetAccount.email})")

                // 1. Tự động dump và lưu persistent session của nick cũ trước khi xóa sạch
                val outgoingId = activeAccountId
                if (outgoingId != null) {
                    try {
                        val outgoingCookies = dumpCookiesForProfile(outgoingId)
                        if (outgoingCookies.isNotBlank() && hasValidBingSession(outgoingCookies)) {
                            Log.i("ProfileManager", "Lưu persistent outgoing cookies cho account $outgoingId trước khi switch")
                            onSaveOutgoingSession?.invoke(outgoingId, outgoingCookies)
                        }
                    } catch (e: Exception) {
                        Log.e("ProfileManager", "Lỗi lưu outgoing session cho account $outgoingId: ${e.message}", e)
                    }
                }
                val clearSuccess = clearActiveSession(webView)
                if (!clearSuccess) {
                    Log.e("ProfileManager", "Xóa session cũ thất bại, dừng hoán đổi để tránh rò rỉ cookie chéo!")
                    return@withLock false
                }
                try {
                    cookieManager.flush()
                } catch (e: Exception) {
                    Log.w("ProfileManager", "Lỗi cookieManager.flush sau clearActiveSession: ${e.message}")
                }
                // 3. Định tuyến proxy cho phiên mới (tái sử dụng LoopbackProxyBridge nếu cùng effectiveProxy)
                if (effectiveProxy.isNullOrBlank()) {
                    clearWebViewProxy(webView)
                } else if (loopbackBridge.isAlive && loopbackBridge.activeProxyUrl == effectiveProxy) {
                    applyProxyToSystemAndWebView(context, "127.0.0.1", loopbackBridge.localPort)
                } else {
                    routeWebViewViaProxy(webView, effectiveProxy)
                }
                restoreCookiesForProfile(targetAccount.id, targetAccount.cookiesSnapshot)
                try {
                    cookieManager.flush()
                } catch (e: Exception) {
                    Log.w("ProfileManager", "Lỗi cookieManager.flush sau restoreCookies: ${e.message}")
                }
                activeAccountId = targetAccount.id
                activeEffectiveProxy = effectiveProxy
                Log.i("ProfileManager", "Hoán đổi session hoàn tất: activeAccountId=$activeAccountId")
                true
            }
        }
        result ?: false
    }

    suspend fun clearActiveSession(webView: WebView? = null): Boolean = withContext(AndroidMainDispatcher) {
        // 1. Dập tắt toàn bộ JavaScript, Origin WebStorage, ServiceWorker và in-flight AJAX request bằng about:blank
        if (webView != null) {
            try {
                webView.evaluateJavascript(
                    "(function(){try{window.localStorage.clear();window.sessionStorage.clear();}catch(e){}})();",
                    null
                )
            } catch (_: Exception) {}
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                withTimeoutOrNull(500L) {
                    while (webView.url != null && webView.url?.startsWith("about:blank") != true) {
                        delay(50L)
                    }
                }
                delay(50L)
            } catch (e: Exception) {
                Log.w("ProfileManager", "Lỗi dừng và tải about:blank trên WebView: ${e.message}")
            }
        }
        val isClean = suspendCancellableCoroutine<Boolean> { continuation ->
            try {
                cookieManager.removeSessionCookies(null)
                cookieManager.removeAllCookies { removed ->
                    try {
                        cookieManager.flush()
                    } catch (_: Exception) {}
                    val hasResidual = try { cookieManager.hasCookies() } catch (_: Throwable) { false }
                    if (continuation.isActive) {
                        continuation.resume(removed || !hasResidual)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }

        try {
            cookieManager.flush()
            WebStorage.getInstance().deleteAllData()
            try {
                android.webkit.WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
                @Suppress("DEPRECATION")
                android.webkit.WebViewDatabase.getInstance(context).clearFormData()
            } catch (_: Throwable) {}
            webView?.clearCache(true)
            webView?.clearFormData()
            webView?.clearHistory()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 300ms barrier delay to ensure SQLite WAL commits to disk
        delay(300L)

        activeAccountId = null
        activeEffectiveProxy = null
        isClean
    }

    /**
     * Phiên bản bất đồng bộ non-suspending cho các ngữ cảnh gọi ngoài coroutine.
     */
    fun clearActiveSessionAsync(webView: WebView? = null) {
        activeAccountId = null
        activeEffectiveProxy = null
        val clearTask = Runnable {
            try {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                cookieManager.removeSessionCookies(null)
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
        val mainLooper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == mainLooper) {
            clearTask.run()
        } else {
            android.os.Handler(mainLooper).post(clearTask)
        }
    }

    /**
     * Nạp lại toàn bộ Cookie đã lưu cho một tài khoản cụ thể vào CookieManager.
     * Hỗ trợ cả Domain-Scoped Cookie JSON Map lẫn chuỗi định dạng cũ.
     * Chờ tất cả các callback setCookie hoàn thành qua suspendCancellableCoroutine.
     */
    fun resetActiveSession() {
        activeAccountId = null
        activeEffectiveProxy = null
    }

    suspend fun restoreCookiesForProfile(profileId: String, cookiesSnapshot: String?): Boolean = withContext(AndroidMainDispatcher) {
        if (isInteractiveLoginActive && profileId != interactiveLoginProfileId) {
            Log.w("ProfileManager", "Chặn restoreCookiesForProfile ngầm cho $profileId vì đang mở LoginDialog (interactiveLoginProfileId=$interactiveLoginProfileId)")
            return@withContext false
        }
        if (cookiesSnapshot.isNullOrBlank()) return@withContext false

        val itemsToSet = mutableListOf<Pair<String, String>>()
        val trimmed = sanitizeCookieMap(cookiesSnapshot).ifBlank { cookiesSnapshot.trim() }

        fun buildCookieString(trimmedPair: String): String = buildSyntheticCookieString(trimmedPair)

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
                                val cookieName = trimmedPair.substringBefore("=").trim()
                                if (isTransientOAuthNonceCookie(cookieName)) continue
                                val targetUrl = if (domainKey.startsWith("http://") || domainKey.startsWith("https://")) {
                                    domainKey
                                } else {
                                    "https://$domainKey"
                                }
                                val finalCookie = buildCookieString(trimmedPair)
                                itemsToSet.add(Pair(targetUrl, finalCookie))
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
                    val cookieName = trimmedPair.substringBefore("=").trim()
                    if (isTransientOAuthNonceCookie(cookieName)) continue
                    val finalCookie = buildCookieString(trimmedPair)

                    val candidateDomains = when {
                        cookieName.startsWith("__Host-", ignoreCase = true) ||
                            cookieName.startsWith("MSP", ignoreCase = true) ||
                            cookieName.equals("KievRPSToken", ignoreCase = true) -> {
                            listOf("https://login.live.com", "https://account.microsoft.com")
                        }
                        cookieName.equals("_U", ignoreCase = true) || cookieName.equals("_RwBf", ignoreCase = true) -> {
                            listOf("https://www.bing.com", "https://rewards.bing.com", "https://bing.com")
                        }
                        else -> CORE_DOMAINS
                    }

                    for (domain in candidateDomains) {
                        itemsToSet.add(Pair(domain, finalCookie))
                    }
                }
            }
        }

        if (itemsToSet.isEmpty()) return@withContext false

        withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine { continuation ->
                var remaining = itemsToSet.size
                var allSuccess = true

                for ((targetUrl, cookieVal) in itemsToSet) {
                    try {
                        cookieManager.setCookie(targetUrl, cookieVal) { success ->
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
        } ?: false
    }

    /**
     * Trích xuất và phân vùng Cookie theo từng Domain (Domain-Scoped Cookie Map).
     * Trả về JSON String đã làm sạch: {"https://www.bing.com": "...", "https://login.live.com": "..."}.
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

        return if (hasValidContent) sanitizeCookieMap(rootJson.toString()) else ""
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
                if (key.isNotEmpty() && !isTransientOAuthNonceCookie(key)) {
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

        Log.d("ProfileManager", "Bing Session NOT yet valid. Detected keys: ${map.keys.take(10)}")
        return false
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
        val filtered = cookieMap.filter { !it.key.startsWith("MSP") && !isTransientOAuthNonceCookie(it.key) }
        return filtered.map { "${it.key}=${it.value}" }.joinToString("; ")
    }
}
