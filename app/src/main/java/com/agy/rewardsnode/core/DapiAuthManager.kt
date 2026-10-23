package com.agy.rewardsnode.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Trình quản lý vòng đời xác thực OAuth2 cho Microsoft Rewards DAPI (Direct API).
 * Triển khai theo Singleton pattern với chuẩn OAuth2 Authorization Code Grant & Refresh Token Grant.
 *
 * Client ID: 0000000040170455
 * Scope: service::prod.rewardsplatform.microsoft.com::MBI_SSL
 * Redirect URI: https://login.live.com/oauth20_desktop.srf
 */
object DapiAuthManager {
    private const val TAG = "DapiAuthManager"

    const val CLIENT_ID = "0000000040170455"
    const val SCOPE = "service::prod.rewardsplatform.microsoft.com::MBI_SSL"
    const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
    const val AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf"
    const val PRIMARY_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"

    fun getInstance(): DapiAuthManager = this

    /**
     * Mô hình phản hồi Token từ Microsoft OAuth2 token endpoint.
     */
    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Long,
        val expiryTimestampMs: Long = System.currentTimeMillis() + (expiresIn * 1000L)
    )

    /**
     * Kiểm tra timestamp hết hạn có thỏa điều kiện hết hạn không (currentTime >= expiry - bufferMs).
     * Mặc định bufferMs = 300_000L (5 phút an toàn trước khi hết hạn).
     */
    fun isExpired(
        expiryTimestampMs: Long,
        currentTime: Long = System.currentTimeMillis(),
        bufferMs: Long = 300_000L
    ): Boolean {
        if (expiryTimestampMs <= 0L) return true
        return currentTime >= (expiryTimestampMs - bufferMs)
    }

    /**
     * Bóc tách JSON phản hồi OAuth2 token (access_token, refresh_token, expires_in) và tính toán expiry timestamp.
     */
    fun parseTokenResponse(
        jsonString: String,
        currentTimeMs: Long = System.currentTimeMillis()
    ): TokenResponse? {
        return try {
            val json = JSONObject(jsonString)
            val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
            val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
            val expiresIn = json.optLong("expires_in", 3600L)
            TokenResponse(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = expiresIn,
                expiryTimestampMs = currentTimeMs + (expiresIn * 1000L)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Kiểm tra Access Token của tài khoản có hết hạn hay chưa (kèm buffer an toàn mặc định 5 phút).
     */
    fun isTokenExpired(account: AccountProfile, bufferMs: Long = 300_000L): Boolean {
        if (account.dapiAccessToken.isNullOrBlank()) return true
        return isExpired(account.dapiTokenExpiryTimestampMs, System.currentTimeMillis(), bufferMs)
    }

    /**
     * Cập nhật các trường DAPI token vào đối tượng AccountProfile trong RAM.
     */
    fun updateAccountWithToken(account: AccountProfile, token: TokenResponse) {
        account.dapiAccessToken = token.accessToken
        if (!token.refreshToken.isNullOrBlank()) {
            account.dapiRefreshToken = token.refreshToken
        }
        account.dapiTokenExpiryTimestampMs = token.expiryTimestampMs
    }

    /**
     * Đảm bảo tài khoản luôn sở hữu Access Token hợp lệ cho các cuộc gọi DAPI:
     * 1. Trả về token cache chưa hết hạn nếu còn hiệu lực.
     * 2. Nếu đã hết hạn (hoặc sắp hết hạn trong 60s), gọi làm mới qua refreshToken.
     * 3. Nếu chưa có token hoặc refresh thất bại và có WebView, điều hướng WebView cấp lại token mới.
     * Tự động cập nhật các trường profile của tài khoản và lưu vào DB nếu có.
     *
     * @return Chuỗi Access Token hợp lệ hoặc null nếu không thể lấy được.
     */
    suspend fun ensureValidToken(
        account: AccountProfile,
        webView: WebView? = null,
        db: AppDatabase? = null
    ): String? {
        val now = System.currentTimeMillis()
        val cachedToken = account.dapiAccessToken

        // 1. Trả về token còn hạn (buffer an toàn 5 phút)
        if (!cachedToken.isNullOrBlank() && !isTokenExpired(account)) {
            return cachedToken
        }

        // 2. Thử làm mới qua refreshToken
        val refreshToken = account.dapiRefreshToken
        if (!refreshToken.isNullOrBlank()) {
            val refreshed = refreshAccessToken(refreshToken, account.proxyUrl)
            if (refreshed != null && refreshed.accessToken.isNotBlank()) {
                updateAccountWithToken(account, refreshed)
                db?.rewardsDao()?.updateProfile(account)
                return refreshed.accessToken
            }
        }

        // 2.5. Ưu tiên lấy OAuth2 Authorization Code trực tiếp qua Pure HTTP (302 Redirect) từ cookie login.live.com
        if (!account.cookiesSnapshot.isNullOrBlank()) {
            val httpCode = acquireAuthCodeViaHttp(account)
            if (!httpCode.isNullOrBlank()) {
                val exchanged = exchangeCodeForTokens(httpCode, account.proxyUrl)
                if (exchanged != null && exchanged.accessToken.isNotBlank()) {
                    updateAccountWithToken(account, exchanged)
                    db?.rewardsDao()?.updateProfile(account)
                    return exchanged.accessToken
                }
            }
        }

        // 3. Fallback: Cấp mới qua WebView
        if (webView != null) {
            val acquired = acquireTokenViaWebView(webView, account)
            if (acquired != null && acquired.accessToken.isNotBlank()) {
                updateAccountWithToken(account, acquired)
                db?.rewardsDao()?.updateProfile(account)
                return acquired.accessToken
            }
        }

        return null
    }

    /**
     * Lấy OAuth2 Authorization Code trực tiếp qua Pure HTTP (không cần mở WebView)
     * bằng cách gửi cookie đã làm sạch của https://login.live.com tới oauth20_authorize.srf.
     * Lưu ý: Không truyền login_hint để tránh bị login.live.com ép vào iLoginMode=39 khi thiếu __Host-MSAAUTHP.
     */
    suspend fun acquireAuthCodeViaHttp(
        account: AccountProfile,
        timeoutMs: Int = 12000
    ): String? = withContext(Dispatchers.IO) {
        val rawSnapshot = account.cookiesSnapshot
        if (rawSnapshot.isNullOrBlank()) return@withContext null

        val cookieMap = LinkedHashMap<String, String>()
        try {
            val sanitized = ProfileManager.sanitizeCookieMap(rawSnapshot)
            if (sanitized.trimStart().startsWith("{")) {
                val json = JSONObject(sanitized)
                val liveDomains = listOf(
                    "https://login.live.com",
                    "login.live.com",
                    "https://account.live.com",
                    "https://live.com",
                    "https://account.microsoft.com"
                )
                for (dom in liveDomains) {
                    val cookieStr = json.optString(dom, "")
                    if (cookieStr.isNotBlank()) {
                        for (pair in cookieStr.split(";")) {
                            val trimmed = pair.trim()
                            val eqIdx = trimmed.indexOf('=')
                            if (eqIdx > 0) {
                                val k = trimmed.substring(0, eqIdx).trim()
                                val v = trimmed.substring(eqIdx + 1).trim()
                                if (k.isNotEmpty() && v.isNotEmpty() &&
                                    !k.startsWith(".MSA.Auth.Nonce", ignoreCase = true) &&
                                    !k.startsWith(".MSA.Auth.Correlation", ignoreCase = true)
                                ) {
                                    cookieMap[k] = v
                                }
                            }
                        }
                    }
                }
            }
            // Fallback: quét thêm các MSA auth cookie nếu snapshot lưu dạng phẳng hoặc nằm ở nhánh khác
            if (!cookieMap.containsKey("__Host-MSAAUTHP") && !cookieMap.containsKey("MSPAuth")) {
                val msaKeys = setOf(
                    "__Host-MSAAUTHP", "__Host-MSAAUTH", "MSPAuth", "MSPOK", "WLSSC",
                    "MSPProf", "NAP", "ANON", "MSPCID", "PPLState", "SDIDC", "JSH", "JSHP", ".MSA.Auth"
                )
                for (pair in sanitized.split(";", "\"", ",", "{", "}")) {
                    val trimmed = pair.trim()
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx > 0) {
                        val k = trimmed.substring(0, eqIdx).trim()
                        val v = trimmed.substring(eqIdx + 1).trim()
                        if (msaKeys.any { it.equals(k, ignoreCase = true) } && v.isNotEmpty()) {
                            cookieMap.putIfAbsent(k, v)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (cookieMap.isEmpty()) return@withContext null

        val authorizeUrl = StringBuilder(AUTHORIZE_URL)
            .append("?client_id=").append(CLIENT_ID)
            .append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"))
            .append("&response_type=code")
            .append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            .append("&response_mode=query")
            .toString()

        val proxy = resolveProxy(account.proxyUrl)
        val proxyAuth = extractProxyAuth(account.proxyUrl)
        var currentUrl = authorizeUrl

        for (hop in 0 until 6) {
            var conn: HttpURLConnection? = null
            try {
                val urlObj = URL(currentUrl)
                conn = (urlObj.openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty(
                        "User-Agent",
                        account.customUserAgent?.takeIf { it.isNotBlank() }
                            ?: "Mozilla/5.0 (Linux; Android 14; 23127PN0CG Build/UKQ1.230804.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.120 Mobile Safari/537.36 EdgA/133.0.3065.82"
                    )
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    if (cookieHeader.isNotBlank()) {
                        setRequestProperty("Cookie", cookieHeader)
                    }
                    if (proxyAuth != null && proxy.type() == java.net.Proxy.Type.HTTP) {
                        setRequestProperty("Proxy-Authorization", "Basic $proxyAuth")
                    }
                }

                val status = conn.responseCode
                // Cập nhật Set-Cookie từ response
                conn.headerFields?.forEach { (headerKey, headerValues) ->
                    if (headerKey != null && headerKey.equals("Set-Cookie", ignoreCase = true)) {
                        for (sc in headerValues) {
                            val firstPart = sc.substringBefore(";").trim()
                            val eqIdx = firstPart.indexOf('=')
                            if (eqIdx > 0) {
                                val k = firstPart.substring(0, eqIdx).trim()
                                val v = firstPart.substring(eqIdx + 1).trim()
                                if (k.isNotEmpty() && v.isNotEmpty() && !v.equals("deleted", ignoreCase = true)) {
                                    cookieMap[k] = v
                                }
                            }
                        }
                    }
                }

                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    val codeMatch = Regex("""[?&#]code=([^&#"'\s]+)""").find(location)
                    if (codeMatch != null) {
                        val rawCode = codeMatch.groupValues[1]
                        return@withContext try { URLDecoder.decode(rawCode, "UTF-8") } catch (_: Exception) { rawCode }
                    }
                    if (status in 300..399) {
                        currentUrl = URL(URL(currentUrl), location).toString()
                        continue
                    }
                }

                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val bodyCodeMatch = Regex("""oauth20_desktop\.srf\?[^"'\s<>]*code=([^&#"'\s<>]+)""").find(body)
                    ?: Regex("""name\s*=\s*["']code["'][^>]*value\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(body)
                if (bodyCodeMatch != null) {
                    val rawCode = bodyCodeMatch.groupValues[1]
                    return@withContext try { URLDecoder.decode(rawCode, "UTF-8") } catch (_: Exception) { rawCode }
                }
                break
            } catch (e: Exception) {
                logWarn("acquireAuthCodeViaHttp hop=$hop lỗi: ${e.message}")
                break
            } finally {
                conn?.disconnect()
            }
        }
        null
    }

    /**
     * Tải URL ủy quyền vào WebView, chặn chuyển hướng tới desktop.srf?code=,
     * và tráo đổi authorization code lấy bộ Access/Refresh Token qua POST oauth20_token.srf.
     */
    suspend fun acquireTokenViaWebView(
        webView: WebView,
        account: AccountProfile,
        timeoutMs: Long = 20000L
    ): TokenResponse? = withContext(AndroidMainDispatcher) {
        try {
            if (!account.cookiesSnapshot.isNullOrBlank()) {
                ProfileManager(webView.context).restoreCookiesForProfile(account.id, account.cookiesSnapshot)
            }
        } catch (_: Throwable) {}

        val authCode = suspendCancellableCoroutine<String?> { continuation ->
            var completed = false
            var interactiveLoginCheckCount = 0
            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (!completed && continuation.isActive) {
                    completed = true
                    logWarn("acquireTokenViaWebView timed out sau ${timeoutMs}ms cho [${account.email}]")
                    continuation.resume(null)
                }
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)

            continuation.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                handler.post {
                    try {
                        webView.stopLoading()
                    } catch (_: Exception) {}
                }
            }

            fun finishWithCode(code: String?) {
                if (!completed && continuation.isActive) {
                    completed = true
                    handler.removeCallbacksAndMessages(null)
                    continuation.resume(code)
                }
            }

            fun checkUrlForCode(url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                val isRedirect = url.startsWith(REDIRECT_URI, ignoreCase = true) ||
                        url.startsWith("https://login.live.com/oauth20_desktop.srf", ignoreCase = true) ||
                        url.contains("oauth20_desktop.srf", ignoreCase = true)

                if (isRedirect) {
                    try {
                        webView.stopLoading()
                    } catch (_: Exception) {}

                    val parsedUri = try { android.net.Uri.parse(url) } catch (_: Exception) { null }
                    var code = parsedUri?.getQueryParameter("code")
                    if (code.isNullOrBlank()) {
                        val match = Regex("""[?&#]code=([^&#]+)""").find(url)
                        code = match?.groupValues?.get(1)?.let {
                            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                        }
                    }

                    if (!code.isNullOrBlank()) {
                        finishWithCode(code)
                        return true
                    }

                    val error = parsedUri?.getQueryParameter("error")
                    if (!error.isNullOrBlank()) {
                        logWarn("OAuth2 redirect trả về lỗi: $error cho [${account.email}]")
                        finishWithCode(null)
                        return true
                    }
                }
                return false
            }

            val safeEmailJs = account.email.lowercase().replace("'", "\\'")
            val autoAcceptScript = """
                (function(){
                    try {
                        if (window.location.href && window.location.href.indexOf('oauth20_desktop.srf') !== -1) {
                            return 'REDIRECT:' + window.location.href;
                        }
                        var fm = document.getElementById('fmHF');
                        if (fm && typeof fm.submit === 'function') { fm.submit(); return 'FORM_SUBMITTED'; }
                        var tiles = document.querySelectorAll('.tile-container, div[data-test-id*="tile"], .table[role="button"], div[role="listitem"] button');
                        if (tiles && tiles.length > 0) {
                            var targetEmail = '$safeEmailJs';
                            for (var i = 0; i < tiles.length; i++) {
                                var txt = (tiles[i].innerText || tiles[i].textContent || '').toLowerCase();
                                if (targetEmail && txt.indexOf(targetEmail) !== -1) {
                                    tiles[i].click();
                                    return 'TILE_MATCHED';
                                }
                            }
                            tiles[0].click();
                            return 'TILE_FIRST';
                        }
                        var pwdInput = document.querySelector('input[name="passwd"]:not([type="hidden"]), input[type="password"]:not([type="hidden"])');
                        var emailInput = document.querySelector('input[name="loginfmt"]:not([type="hidden"]), input[type="email"]:not([type="hidden"])');
                        var isVisible = function(el) { return !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length)); };
                        if (isVisible(pwdInput) || (isVisible(emailInput) && !emailInput.value)) {
                            return 'NEEDS_INTERACTIVE_LOGIN';
                        }
                        var btn = document.getElementById('idSIButton9') 
                            || document.getElementById('idBtn_Accept')
                            || document.querySelector('#acceptButton')
                            || document.querySelector('input[type="submit"]') 
                            || document.querySelector('button[type="submit"]');
                        if (isVisible(btn)) { btn.click(); return 'BTN_CLICKED'; }
                    } catch(e) {}
                    return 'NONE';
                })();
            """.trimIndent()

            fun evaluateAutoAccept(view: WebView?, attempt: Int = 0) {
                if (completed || view == null) return
                if (checkUrlForCode(view.url)) return
                view.evaluateJavascript(autoAcceptScript) { rawRes ->
                    if (completed) return@evaluateJavascript
                    val res = rawRes?.trim('"') ?: "NONE"
                    when {
                        res.startsWith("REDIRECT:") -> {
                            checkUrlForCode(res.removePrefix("REDIRECT:"))
                        }
                        res == "NEEDS_INTERACTIVE_LOGIN" -> {
                            interactiveLoginCheckCount++
                            if (interactiveLoginCheckCount >= 2) {
                                logWarn("Phiên login.live.com của [${account.email}] yêu cầu nhập lại mật khẩu/email (fail-fast)")
                                finishWithCode(null)
                            } else {
                                handler.postDelayed({ evaluateAutoAccept(view, attempt + 1) }, 1000L)
                            }
                        }
                        res == "NONE" && attempt < 4 -> {
                            handler.postDelayed({ evaluateAutoAccept(view, attempt + 1) }, 750L)
                        }
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val reqUrl = request?.url?.toString()
                    if (checkUrlForCode(reqUrl)) return true
                    return super.shouldOverrideUrlLoading(view, request)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (checkUrlForCode(url)) return true
                    return super.shouldOverrideUrlLoading(view, url)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    checkUrlForCode(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (checkUrlForCode(url)) return
                    evaluateAutoAccept(view, 0)
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    if (checkUrlForCode(failingUrl)) return
                }
            }

            try {
                FingerprintEngine.stripXRequestedWith(webView)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.databaseEnabled = true
                webView.settings.javaScriptCanOpenWindowsAutomatically = true
                webView.settings.setSupportMultipleWindows(false)
                webView.settings.userAgentString = FingerprintEngine.getMobileLoginPreset().userAgent

                // Không truyền login_hint để tránh bị login.live.com ép vào iLoginMode=39 khi thiếu __Host-MSAAUTHP
                val authorizeUrl = StringBuilder(AUTHORIZE_URL)
                    .append("?client_id=").append(CLIENT_ID)
                    .append("&response_type=code")
                    .append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
                    .append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"))
                    .append("&response_mode=query")

                webView.loadUrl(authorizeUrl.toString())
            } catch (e: Exception) {
                logError("Lỗi khởi tạo luồng OAuth2 trong WebView", e)
                finishWithCode(null)
            }
        }

        if (authCode.isNullOrBlank()) {
            return@withContext null
        }

        try {
            val freshCookies = ProfileManager(webView.context).dumpCookiesForProfile(account.id)
            if (freshCookies.isNotBlank()) {
                account.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, freshCookies)
            }
        } catch (_: Throwable) {}

        val tokenResp = exchangeCodeForTokens(authCode, account.proxyUrl)
        if (tokenResp != null) {
            updateAccountWithToken(account, tokenResp)
        }
        tokenResp
    }

    /**
     * Trao đổi Authorization Code lấy Access Token và Refresh Token qua PRIMARY_TOKEN_URL (consumers/oauth2/v2.0/token)
     * với fallback sang TOKEN_URL (oauth20_token.srf).
     */
    suspend fun exchangeCodeForTokens(
        code: String,
        proxyUrl: String? = null
    ): TokenResponse? = withContext(Dispatchers.IO) {
        if (code.isBlank()) return@withContext null
        try {
            val formData = buildFormUrlEncoded(
                "client_id" to CLIENT_ID,
                "redirect_uri" to REDIRECT_URI,
                "grant_type" to "authorization_code",
                "code" to code,
                "scope" to SCOPE
            )
            val (status1, body1) = executeHttpPost(PRIMARY_TOKEN_URL, formData, proxyUrl)
            if (status1 in 200..299 && body1.isNotBlank()) {
                parseTokenResponse(body1)?.let { return@withContext it }
            }
            val (status2, body2) = executeHttpPost(TOKEN_URL, formData, proxyUrl)
            if (status2 in 200..299 && body2.isNotBlank()) {
                parseTokenResponse(body2)
            } else {
                logWarn("Exchange code thất bại: HTTP $status1/$status2 - $body2")
                null
            }
        } catch (e: Exception) {
            logError("Lỗi trong exchangeCodeForTokens", e)
            null
        }
    }

    /**
     * Làm mới Access Token bằng Refresh Token qua PRIMARY_TOKEN_URL với fallback sang TOKEN_URL.
     */
    suspend fun refreshAccessToken(
        refreshToken: String,
        proxyUrl: String? = null
    ): TokenResponse? = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank()) return@withContext null
        try {
            val formData = buildFormUrlEncoded(
                "client_id" to CLIENT_ID,
                "redirect_uri" to REDIRECT_URI,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "scope" to SCOPE
            )
            val (status1, body1) = executeHttpPost(PRIMARY_TOKEN_URL, formData, proxyUrl)
            if (status1 in 200..299 && body1.isNotBlank()) {
                val parsed1 = parseTokenResponse(body1)
                if (parsed1 != null) {
                    return@withContext if (parsed1.refreshToken == null) parsed1.copy(refreshToken = refreshToken) else parsed1
                }
            }
            val (status, body) = executeHttpPost(TOKEN_URL, formData, proxyUrl)
            if (status in 200..299 && body.isNotBlank()) {
                val parsed = parseTokenResponse(body)
                if (parsed != null && parsed.refreshToken == null) {
                    parsed.copy(refreshToken = refreshToken)
                } else parsed
            } else {
                logWarn("Refresh token thất bại: HTTP $status - $body")
                null
            }
        } catch (e: Exception) {
            logError("Lỗi trong refreshAccessToken", e)
            null
        }
    }

    /**
     * Overload tiện ích: làm mới token cho đối tượng AccountProfile.
     */
    suspend fun refreshAccessToken(account: AccountProfile): TokenResponse? {
        val token = account.dapiRefreshToken ?: return null
        return refreshAccessToken(token, account.proxyUrl)?.also {
            updateAccountWithToken(account, it)
        }
    }

    // =========================================================================
    // Network I/O & Proxy Helpers
    // =========================================================================

    private fun buildFormUrlEncoded(vararg params: Pair<String, String>): String {
        return params.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    private fun executeHttpPost(
        urlString: String,
        formData: String,
        proxyUrl: String? = null,
        timeoutMs: Int = 15000
    ): Pair<Int, String> {
        val proxy = resolveProxy(proxyUrl)
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                doInput = true
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0")

                val proxyAuth = extractProxyAuth(proxyUrl)
                if (proxyAuth != null && proxy.type() == java.net.Proxy.Type.HTTP) {
                    setRequestProperty("Proxy-Authorization", "Basic $proxyAuth")
                }
            }

            val postBytes = formData.toByteArray(Charsets.UTF_8)
            connection.outputStream.use { os ->
                os.write(postBytes)
                os.flush()
            }

            val responseCode = connection.responseCode
            val responseStream: InputStream? = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Pair(responseCode, responseBody)
        } finally {
            connection?.disconnect()
        }
    }

    private fun resolveProxy(proxyUrl: String?): java.net.Proxy {
        if (proxyUrl.isNullOrBlank()) return java.net.Proxy.NO_PROXY
        try {
            val loopback = LoopbackProxyBridge.getInstance()
            if (loopback.isAlive && loopback.activeProxyUrl == proxyUrl && loopback.localPort > 0) {
                return java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", loopback.localPort))
            }
        } catch (_: Throwable) {}

        return try {
            val uri = java.net.URI(if (proxyUrl.contains("://")) proxyUrl else "http://$proxyUrl")
            val host = uri.host ?: return java.net.Proxy.NO_PROXY
            val port = if (uri.port != -1) uri.port else 8080
            val type = if (uri.scheme?.startsWith("socks", ignoreCase = true) == true) {
                java.net.Proxy.Type.SOCKS
            } else {
                java.net.Proxy.Type.HTTP
            }
            java.net.Proxy(type, InetSocketAddress(host, port))
        } catch (_: Exception) {
            java.net.Proxy.NO_PROXY
        }
    }

    private fun extractProxyAuth(proxyUrl: String?): String? {
        if (proxyUrl.isNullOrBlank()) return null
        return try {
            val uri = java.net.URI(if (proxyUrl.contains("://")) proxyUrl else "http://$proxyUrl")
            val userInfo = uri.userInfo
            if (!userInfo.isNullOrBlank()) {
                encodeBase64(userInfo.toByteArray(Charsets.UTF_8))
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            val clazz = Class.forName("android.util.Base64")
            val method = clazz.getMethod("encodeToString", ByteArray::class.java, Int::class.javaPrimitiveType)
            (method.invoke(null, bytes, 2) as String).trim() // 2 = NO_WRAP
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes).trim()
        }
    }

    private fun logWarn(msg: String) {
        try {
            val logClass = Class.forName("android.util.Log")
            val wMethod = logClass.getMethod("w", String::class.java, String::class.java)
            wMethod.invoke(null, TAG, msg)
        } catch (_: Throwable) {
            println("WARN [$TAG] $msg")
        }
    }

    private fun logError(msg: String, tr: Throwable? = null) {
        try {
            val logClass = Class.forName("android.util.Log")
            val eMethod = logClass.getMethod("e", String::class.java, String::class.java, Throwable::class.java)
            eMethod.invoke(null, TAG, msg, tr)
        } catch (_: Throwable) {
            System.err.println("ERROR [$TAG] $msg: ${tr?.message}")
        }
    }
}
