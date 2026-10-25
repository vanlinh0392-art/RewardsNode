package com.agy.rewardsnode.core

import android.webkit.WebView
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Đại diện cho một nhiệm vụ Daily Set hoặc Activity trong Microsoft Rewards.
 */
data class DailyTask(
    val id: String,
    val title: String,
    val pointValue: Int,
    val destinationUrl: String,
    val taskType: String,
    val isCompleted: Boolean,
    val authKey: String = "",
    val activityType: String = "bingredirect",
    val userId: String = "",
    val description: String = ""
) {
    val offerId: String get() = id
}

typealias PromotionTask = DailyTask

/**
 * Động cơ tự động hóa các hoạt động Microsoft Rewards (Daily Set, Quizzes, Polls, Streak Protection, Ready to Claim).
 * Tương tác an toàn và bất đồng bộ với WebView thông qua JavaScript thuần (IIFE) trên AndroidMainDispatcher.
 */
object RewardsActivitiesEngine {
    const val REPORT_ACTIVITY_API_URL = "https://www.bing.com/msrewards/api/v1/reportactivity"
    const val DEFAULT_DESKTOP_EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0"

    /**
     * Phân loại loại nhiệm vụ (poll, quiz, news, urlreward) dựa trên URL, tiêu đề và mô tả.
     */
    fun detectTaskType(url: String?, title: String?, description: String? = null): String {
        val str = "${url ?: ""} ${title ?: ""} ${description ?: ""}".lowercase(Locale.ROOT)
        return when {
            str.contains("poll") || str.contains("btoption") || str.contains("bầu chọn") || str.contains("bình chọn") || str.contains("khảo sát") -> "poll"
            str.contains("quiz") || str.contains("trivia") || str.contains("supersonic") || str.contains("turbocharge") || str.contains("lightspeed") || str.contains("câu đố") || str.contains("thử thách") || str.contains("trắc nghiệm") || str.contains("đố vui") || str.contains("kiểm tra kiến thức") -> "quiz"
            str.contains("news") || str.contains("tin tức") || str.contains("báo") || str.contains("article") -> "news"
            else -> "urlreward"
        }
    }


    internal val PROMOTION_BLACKLIST_REGEX = Regex(
        """\b(donate\w*|charit\w*|sweepstakes?|raffles?|raffle|spend\w*|buy\w*|purchas\w*|orders?|rent\w*|giveaways?|give|redeem\w*|gift\s*card\w*|win|entries|entry|draws?|draw|lottery|checkout)\b|quyên góp|từ thiện|trúng thưởng|quay số|nạp|mua|thuê|ủng hộ|đổi quà|đổi\s*thưởng""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Kiểm tra ưu đãi an toàn để tự động cày điểm mà không làm mất điểm hoặc tốn tiền.
     * Cho phép các ưu đãi độc quyền Level 2 (/levelbenefitexclusive) nếu điểm trong khoảng 5..50.
     * Loại trừ các hoạt động tiêu điểm (donate, sweepstake, redeem, give...), 0 điểm, nạp/mua (pointValue > 100).
     */
    fun isSafePromotion(title: String, destinationUrl: String?, pointValue: Int, description: String? = null): Boolean {
        if (pointValue <= 0 || pointValue > 100) return false
        val destLower = (destinationUrl ?: "").lowercase(Locale.ROOT)
        if (destLower.contains("/levelbenefitexclusive") && pointValue !in 5..50) return false
        if (destLower.contains("/referandearn") || destLower.contains("/redeem/") || destLower.contains("/redeem") || destLower.contains("/give/") || destLower.contains("/give")) return false
        val safeDest = destinationUrl ?: ""
        val safeDesc = description ?: ""
        if (PROMOTION_BLACKLIST_REGEX.containsMatchIn(title) ||
            PROMOTION_BLACKLIST_REGEX.containsMatchIn(safeDest) ||
            PROMOTION_BLACKLIST_REGEX.containsMatchIn(safeDesc)) return false
        val combined = "$title $safeDest $safeDesc"
        if (PROMOTION_BLACKLIST_REGEX.containsMatchIn(combined)) return false
        return true
    }

    /**
     * Báo cáo hoàn thành hoạt động Daily Set / Keep Earning trực tiếp qua API chính thống
     * POST https://www.bing.com/msrewards/api/v1/reportactivity.
     * Trả về số điểm nhận được (> 0 nếu thành công, 0 nếu thất bại).
     */
    fun reportActivityViaBingApi(
        task: PromotionTask,
        cookiesJson: String,
        userAgent: String = DEFAULT_DESKTOP_EDGE_UA,
        proxyUrl: String? = null
    ): Int {
        if (task.authKey.isBlank() || task.offerId.isBlank()) return 0

        val cookieHeader = RewardsBalanceExtractor.filterBingCookies(cookiesJson)
        if (cookieHeader.isBlank()) return 0

        val proxy = if (!proxyUrl.isNullOrBlank()) {
            try {
                val uri = java.net.URI(if (proxyUrl.contains("://")) proxyUrl else "http://$proxyUrl")
                val host = uri.host
                if (host != null) {
                    val port = if (uri.port != -1) uri.port else 8080
                    val type = if (uri.scheme?.startsWith("socks") == true) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
                    java.net.Proxy(type, java.net.InetSocketAddress(host, port))
                } else {
                    java.net.Proxy.NO_PROXY
                }
            } catch (_: Exception) {
                java.net.Proxy.NO_PROXY
            }
        } else {
            java.net.Proxy.NO_PROXY
        }

        val payloadJson = JSONObject().apply {
            put("ActivityType", task.activityType.ifBlank { "bingredirect" })
            put("ActivitySubType", "")
            put("OfferId", task.offerId)
            put("Channel", "BingFlyout")
            put("PartnerId", "BingRewards")
            put("UserId", task.userId)
            put("AuthKey", task.authKey)
            put("ActivityCount", 1)
        }.toString()

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(REPORT_ACTIVITY_API_URL)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                useCaches = false
                doOutput = true
                setRequestProperty("User-Agent", userAgent.ifBlank { DEFAULT_DESKTOP_EDGE_UA })
                setRequestProperty("Cookie", cookieHeader)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Origin", "https://www.bing.com")
                setRequestProperty("Referer", "https://www.bing.com/rewards/panelflyout?channel=bingflyout&partnerId=BingRewards")
            }

            connection.outputStream.use { os ->
                os.write(payloadJson.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = connection.responseCode
            val respBody = if (code in 200..299) {
                connection.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (code in 200..299 && respBody.isNotBlank()) {
                val json = JSONObject(respBody)
                val errCode = json.optJSONObject("ErrorDetail")?.optInt("ErrorCode", 0) ?: 0
                val earned = json.optInt("EarnedCredits", 0)
                val complete = json.optBoolean("ActivityComplete", false)
                if (errCode == 0 && (earned > 0 || complete)) {
                    if (earned > 0) earned else maxOf(1, task.pointValue)
                } else {
                    0
                }
            } else {
                0
            }
        } catch (_: Exception) {
            0
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Thực thi một nhiệm vụ Daily Set / Keep Earning qua HTTP:
     * Ưu tiên gọi reportActivityViaBingApi đầu tiên khi task có authKey và userId,
     * sau đó fallback sang executeFastRedirect (HTTP GET follow redirects ngầm 1.5s).
     * Đảm bảo error isolation với try/catch.
     */
    suspend fun executeSingleTaskHttp(
        task: PromotionTask,
        cookiesJson: String?,
        userAgent: String? = null,
        proxyUrl: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (task.destinationUrl.isBlank() || (!task.destinationUrl.startsWith("http://") && !task.destinationUrl.startsWith("https://") && !task.destinationUrl.startsWith("/"))) {
            return@withContext false
        }
        val effectiveCookies = cookiesJson ?: ""
        val effectiveUa = if (!userAgent.isNullOrBlank()) userAgent else DEFAULT_DESKTOP_EDGE_UA
        try {
            if (task.authKey.isNotBlank() && task.userId.isNotBlank()) {
                val earned = reportActivityViaBingApi(task, effectiveCookies, effectiveUa, proxyUrl)
                if (earned > 0) {
                    return@withContext true
                }
            }
        } catch (_: Exception) {
            // Error isolation: Thất bại ở API report chuyển tiếp an toàn sang executeFastRedirect
        }
        try {
            executeFastRedirect(task.destinationUrl, task.offerId, effectiveCookies, proxyUrl, effectiveUa)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Kích hoạt nhanh nhiệm vụ chuyển hướng (Fast Redirect) trực tiếp qua HTTP GET trên Dispatchers.IO.
     * Thực hiện HTTP GET đến destinationUrl (hoặc URL chuyển hướng Bing Rewards) với Cookie header,
     * timeout 3500ms, tự động chuyển tiếp (follow redirects ngầm 1.5s).
     */
    suspend fun executeFastRedirect(
        destinationUrl: String,
        offerId: String,
        cookiesSnapshot: String?,
        proxyUrl: String? = null,
        userAgent: String = DEFAULT_DESKTOP_EDGE_UA
    ): Boolean = withContext(Dispatchers.IO) {
        if (destinationUrl.isBlank() || (!destinationUrl.startsWith("http://") && !destinationUrl.startsWith("https://") && !destinationUrl.startsWith("/"))) {
            return@withContext false
        }
        var currentUrl = when {
            destinationUrl.contains("/rewardsapp/redirect") || destinationUrl.contains("/redirect?") -> destinationUrl
            offerId.isNotBlank() && !offerId.startsWith("Global_DailySet_DOM") -> {
                try {
                    val encodedUrl = java.net.URLEncoder.encode(destinationUrl, "UTF-8")
                    "https://www.bing.com/rewardsapp/redirect?url=$encodedUrl&id=$offerId"
                } catch (_: Exception) {
                    destinationUrl
                }
            }
            destinationUrl.startsWith("http://") || destinationUrl.startsWith("https://") -> destinationUrl
            destinationUrl.startsWith("/") -> "https://www.bing.com$destinationUrl"
            else -> destinationUrl
        }

        if (currentUrl.isBlank() || (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://"))) {
            return@withContext false
        }

        val cookieHeader = RewardsBalanceExtractor.filterBingCookies(cookiesSnapshot)
        val proxy = if (!proxyUrl.isNullOrBlank()) {
            try {
                val uri = java.net.URI(if (proxyUrl.contains("://")) proxyUrl else "http://$proxyUrl")
                val host = uri.host
                if (host != null) {
                    val port = if (uri.port != -1) uri.port else 8080
                    val type = if (uri.scheme?.startsWith("socks") == true) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
                    java.net.Proxy(type, java.net.InetSocketAddress(host, port))
                } else {
                    java.net.Proxy.NO_PROXY
                }
            } catch (_: Exception) {
                java.net.Proxy.NO_PROXY
            }
        } else {
            java.net.Proxy.NO_PROXY
        }

        var redirectsCount = 0
        val maxRedirects = 5
        var isSuccess = false

        while (redirectsCount < maxRedirects) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                val host = url.host?.lowercase(java.util.Locale.ROOT) ?: ""
                val isBingOrMsn = host.endsWith(".bing.com") || host == "bing.com" || host.endsWith(".msn.com") || host == "msn.com"

                connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3500
                    readTimeout = 3500
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Connection", "close")
                    setRequestProperty("User-Agent", userAgent.ifBlank { DEFAULT_DESKTOP_EDGE_UA })
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    setRequestProperty("Referer", "https://rewards.bing.com/")
                    if (cookieHeader.isNotBlank() && isBingOrMsn) {
                        setRequestProperty("Cookie", cookieHeader)
                    }
                }

                val code = connection.responseCode
                if (code in 300..399) {
                    isSuccess = true
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                            location
                        } else {
                            URL(url, location).toString()
                        }
                        redirectsCount++
                        continue
                    }
                } else if (code in 200..299) {
                    val buffer = ByteArray(4096)
                    connection.inputStream?.use { it.read(buffer) }
                    isSuccess = true
                }
                break
            } catch (_: Exception) {
                break
            } finally {
                connection?.disconnect()
            }
        }
        if (isSuccess) {
            try {
                // HTTP GET follow redirects ngầm 1.5s để server Bing ghi nhận dwell và cập nhật điểm
                kotlinx.coroutines.delay(1500L)
            } catch (_: Exception) {}
        }
        isSuccess
    }

    /**
     * Kịch bản JavaScript quét và click nút Ready to claim / Claim points.
     */
    fun createReadyToClaimScript(): String {
        return """
            (function() {
                try {
                    function isNegativeExcluded(el) {
                        if (!el) return true;
                        var text = (el.innerText || el.textContent || '').trim().toLowerCase();
                        var href = (el.getAttribute('href') || '').toLowerCase();
                        var bi = ((el.getAttribute('data-bi-name') || '') + ' ' + (el.getAttribute('data-bi-id') || '') + ' ' + (el.getAttribute('instrument') || '')).toLowerCase();
                        var aria = (el.getAttribute('aria-label') || '').toLowerCase();
                        var id = (el.id || '').toLowerCase();
                        var className = (el.className || '').toString().toLowerCase();
                        var combined = text + ' ' + href + ' ' + bi + ' ' + aria + ' ' + id + ' ' + className;

                        // Negative exclusion: Tuyệt đối không click vào nút đổi quà (redeem), đặt hàng (order), quyên góp (donate), rút thăm (sweepstakes), đóng/hủy
                        if (combined.indexOf('redeem') !== -1 ||
                            combined.indexOf('order') !== -1 ||
                            combined.indexOf('donate') !== -1 ||
                            combined.indexOf('sweepstake') !== -1 ||
                            combined.indexOf('gift card') !== -1 ||
                            combined.indexOf('checkout') !== -1 ||
                            text === 'close' || text === 'cancel' || text === 'dismiss' || text === 'đóng' || text === 'hủy') {
                            return true;
                        }

                        // Kiểm tra modal/dialog: Cho phép click nếu là hộp thoại PointClaim (?modal=pointsclaim), chặn các modal đổi quà khác
                        var modalEl = el.closest('.modal, [role="dialog"]');
                        if (modalEl) {
                            var winUrl = (window.location.href || '').toLowerCase();
                            var modalText = ((modalEl.innerText || modalEl.textContent || '') + ' ' + (modalEl.id || '') + ' ' + (modalEl.className || '') + ' ' + (modalEl.getAttribute('aria-label') || '')).toLowerCase();
                            if (modalText.indexOf('redeem') !== -1 ||
                                modalText.indexOf('order') !== -1 ||
                                modalText.indexOf('donate') !== -1 ||
                                modalText.indexOf('sweepstake') !== -1 ||
                                modalText.indexOf('gift card') !== -1) {
                                return true;
                            }
                            var isPointClaimModal = winUrl.indexOf('modal=pointsclaim') !== -1 ||
                                winUrl.indexOf('form=dsfstt') !== -1 ||
                                modalText.indexOf('pointclaim') !== -1 ||
                                modalText.indexOf('pointsclaim') !== -1 ||
                                modalText.indexOf('claimable') !== -1 ||
                                modalText.indexOf('bonus points') !== -1 ||
                                modalText.indexOf('claim your') !== -1 ||
                                modalText.indexOf('điểm thưởng') !== -1 ||
                                modalText.indexOf('nhận điểm') !== -1 ||
                                modalText.indexOf('nhận thưởng') !== -1 ||
                                combined.indexOf('pointclaim') !== -1 ||
                                combined.indexOf('claim') !== -1 ||
                                combined.indexOf('nhận') !== -1;
                            if (!isPointClaimModal) return true;
                        }

                        return false;
                    }

                    function tryClick(el) {
                        if (!el || isNegativeExcluded(el)) return false;
                        if (el.offsetParent !== null || el.getClientRects().length > 0) {
                            var targetAnchor = el.tagName === 'A' ? el : el.querySelector('a');
                            if (targetAnchor && targetAnchor.getAttribute('target') === '_blank') {
                                targetAnchor.setAttribute('target', '_self');
                            }
                            if (el.tagName === 'A' && el.getAttribute('target') === '_blank') {
                                el.setAttribute('target', '_self');
                            }
                            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            el.click();
                            return true;
                        }
                        return false;
                    }

                    // 0. Ưu tiên nút xác nhận Claim bên trong hộp thoại Modal (?modal=pointsclaim) đang mở
                    var modalButtons = document.querySelectorAll('.modal button, .modal a, .modal div[role="button"], [role="dialog"] button, [role="dialog"] a, [role="dialog"] div[role="button"]');
                    for (var m = 0; m < modalButtons.length; m++) {
                        var mBtn = modalButtons[m];
                        var mText = (mBtn.innerText || mBtn.textContent || '').trim().toLowerCase();
                        var mBi = ((mBtn.getAttribute('data-bi-name') || '') + ' ' + (mBtn.getAttribute('data-bi-id') || '') + ' ' + (mBtn.getAttribute('instrument') || '')).toLowerCase();
                        if (mText === 'claim' || mText === 'claim now' || mText === 'claim points' || mText === 'nhận' || mText === 'nhận ngay' || mText === 'nhận điểm' || mText.indexOf('claim ') === 0 || mBi.indexOf('claim') !== -1) {
                            if (tryClick(mBtn)) return "true";
                        }
                    }

                    // 1. Scoped selectors: #dashboard-header, PointClaim_Details_Default, ?modal=pointsclaim, [instrument*="DashboardHeader_ClaimablePoints"]
                    var scopedSelectors = [
                        '#dashboard-header [instrument*="DashboardHeader_ClaimablePoints"]',
                        '#dashboard-header [data-bi-name*="claim" i]',
                        '#dashboard-header #claimPoints',
                        '#dashboard-header .claimable-points-card',
                        '#dashboard-header button, #dashboard-header a, #dashboard-header div[role="button"]',
                        '[instrument*="PointClaim" i]',
                        '[instrument*="DashboardHeader_ClaimablePoints"]',
                        '[data-bi-id*="pointclaim" i]',
                        '[data-bi-name*="pointclaim" i]',
                        'a[href*="modal=pointsclaim" i]',
                        'a[href*="form=DSFSTT" i]',
                        '[class*="pointClaim" i] button, [class*="pointClaim" i] a',
                        '[data-bi-name*="claim" i]',
                        '#claimPoints',
                        '.claimable-points-card'
                    ];

                    for (var s = 0; s < scopedSelectors.length; s++) {
                        var candidates = document.querySelectorAll(scopedSelectors[s]);
                        for (var c = 0; c < candidates.length; c++) {
                            var el = candidates[c];
                            var text = (el.innerText || el.textContent || '').trim().toLowerCase();
                            if (scopedSelectors[s].toLowerCase().indexOf('claim') !== -1 || scopedSelectors[s].indexOf('DSFSTT') !== -1 || text.indexOf('claim') !== -1 || text.indexOf('nhận') !== -1) {
                                if (tryClick(el)) return "true";
                            }
                        }
                    }

                    // 2. Quét các thẻ tương tác chứa text Claim / Ready to claim / Nhận điểm
                    var interactiveElements = document.querySelectorAll('button, a, div[role="button"], span[role="button"], div.cursor-pointer');
                    for (var i = 0; i < interactiveElements.length; i++) {
                        var el = interactiveElements[i];
                        var text = (el.innerText || el.textContent || '').trim().toLowerCase();
                        if (text === 'claim' || text === 'claim now' || text === 'claim points' || text === 'ready to claim' || text === 'nhận' || text === 'nhận ngay' || text === 'nhận điểm' || text.indexOf('claim points') !== -1 || text.indexOf('claim your') !== -1) {
                            if (tryClick(el)) return "true";
                        }
                    }

                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản JavaScript trích xuất danh sách 3 task trong Daily Set (title, pointValue, url, isCompleted, type).
     */
    /**
     * Sinh kịch bản JavaScript click thẻ Daily Set / Activity card an toàn với PointerEvent và ép target='_self'.
     */
    fun createCardClickScript(token: String, offerId: String): String {
        val safeToken = token.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val safeOfferId = offerId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        return """
            (function() {
                try {
                    var card = document.querySelector('[instrument*="$safeOfferId"]') ||
                               document.querySelector('[data-bi-name*="$safeOfferId"]') ||
                               document.querySelector('[data-bi-name*="$safeToken"]') ||
                               document.querySelector('a[href*="$safeOfferId"]');
                    if (card) {
                        var targetAnchor = card.tagName === 'A' ? card : card.querySelector('a');
                        if (targetAnchor) {
                            targetAnchor.setAttribute('target', '_self');
                        }
                        card.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        var event = new PointerEvent('click', { bubbles: true, cancelable: true, view: window });
                        card.dispatchEvent(event);
                        return "true";
                    }
                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    fun createDailySetExtractorScript(): String {
        return """
            (function() {
                // 1. Next.js __NEXT_DATA__ JSON payload
                try {
                    var nextData = document.getElementById('__NEXT_DATA__');
                    if (nextData && nextData.textContent && nextData.textContent.trim().length > 10) {
                        return nextData.textContent;
                    }
                } catch (e) {}

                // 2. Global objects (rewardsData / UserStatus / bing.rewards)
                try {
                    var gData = window.rewardsData || window.UserStatus || (window.bing && window.bing.rewards);
                    if (gData && (gData.dailySetPromotions || gData.userStatus)) {
                        return JSON.stringify(gData);
                    }
                } catch (e) {}

                // 3. Scan scripts for RSC JSON containing dailySetPromotions or dailySetItems
                try {
                    var scripts = document.querySelectorAll('script');
                    for (var s = 0; s < scripts.length; s++) {
                        var content = scripts[s].textContent;
                        if (!content) continue;
                        var idx = content.indexOf('dailySetPromotions');
                        if (idx === -1) idx = content.indexOf('dailySetItems');
                        if (idx !== -1) {
                            var openBrace = content.lastIndexOf('{', idx);
                            if (openBrace !== -1) {
                                var depth = 0;
                                var inStr = false;
                                var esc = false;
                                var closeBrace = -1;
                                for (var i = openBrace; i < content.length; i++) {
                                    var ch = content.charAt(i);
                                    if (esc) { esc = false; continue; }
                                    if (ch === '\\') { esc = true; continue; }
                                    if (ch === '"') { inStr = !inStr; continue; }
                                    if (!inStr) {
                                        if (ch === '{') depth++;
                                        else if (ch === '}') {
                                            depth--;
                                            if (depth === 0) { closeBrace = i + 1; break; }
                                        }
                                    }
                                }
                                if (closeBrace !== -1) {
                                    return content.substring(openBrace, closeBrace);
                                }
                            }
                            var openBracket = content.indexOf('[', idx);
                            if (openBracket !== -1 && openBracket < idx + 30) {
                                var bDepth = 0;
                                var bInStr = false;
                                var bEsc = false;
                                var closeBracket = -1;
                                for (var j = openBracket; j < content.length; j++) {
                                    var bCh = content.charAt(j);
                                    if (bEsc) { bEsc = false; continue; }
                                    if (bCh === '\\') { bEsc = true; continue; }
                                    if (bCh === '"') { bInStr = !bInStr; continue; }
                                    if (!bInStr) {
                                        if (bCh === '[') bDepth++;
                                        else if (bCh === ']') {
                                            bDepth--;
                                            if (bDepth === 0) { closeBracket = j + 1; break; }
                                        }
                                    }
                                }
                                if (closeBracket !== -1) {
                                    return content.substring(openBracket, closeBracket);
                                }
                            }
                        }
                    }
                } catch (e) {}

                // 4. Robust fallback for DOM card elements (#dailyset, [data-bi-area='daily-set'], [data-bi-name*='DailySet'])
                try {
                    var tasks = [];
                    function detectType(url, title) {
                        var str = ((url || '') + ' ' + (title || '')).toLowerCase();
                        if (str.indexOf('poll') !== -1 || str.indexOf('btoption') !== -1 || str.indexOf('bầu chọn') !== -1 || str.indexOf('khảo sát') !== -1) return 'poll';
                        if (str.indexOf('quiz') !== -1 || str.indexOf('trivia') !== -1 || str.indexOf('supersonic') !== -1 || str.indexOf('turbocharge') !== -1 || str.indexOf('lightspeed') !== -1 || str.indexOf('câu đố') !== -1 || str.indexOf('thử thách') !== -1) return 'quiz';
                        if (str.indexOf('news') !== -1 || str.indexOf('tin tức') !== -1 || str.indexOf('báo') !== -1 || str.indexOf('article') !== -1) return 'news';
                        return 'urlreward';
                    }
                    var section = document.getElementById('dailyset') || document.querySelector('[data-bi-area="daily-set"], [data-bi-name*="daily_set" i], [data-bi-name*="DailySet" i]');
                    var cardElements = (section ? section : document).querySelectorAll('[instrument*="DailySetSection_Card"], [data-bi-name*="DailySetSection_Card"], [data-bi-name*="DailySet"], mee-rewards-daily-set-item, div.c-card');
                    for (var c = 0; c < cardElements.length; c++) {
                        var el = cardElements[c];
                        var titleNode = el.querySelector('h2, h3, p.text-sectionHeader, p.text-body, [data-bi-name="headline"]');
                        var title = titleNode ? titleNode.innerText.trim() : ('Daily Task ' + (c + 1));
                        var descNode = el.querySelector('p.text-description, p.desc, [data-bi-name="description"], .description, .c-subheading');
                        var desc = descNode ? descNode.innerText.trim() : '';
                        var pointsNode = el.querySelector('p.text-metadata, span.points, [aria-label*="point"]');
                        var ptsStr = pointsNode ? pointsNode.innerText : '10';
                        var ptsNum = parseInt((ptsStr.match(/\d+/) || ['10'])[0], 10);
                        var aLink = el.querySelector('a[href]') || (el.tagName === 'A' ? el : null);
                        var dest = aLink ? aLink.href : '';
                        var isDone = !!el.querySelector('svg[class*="success"], .mee-icon-CheckMark, [aria-label*="complete" i], [aria-label*="hoàn thành" i]');
                        var offerId = el.getAttribute('data-offer-id') || el.getAttribute('id') || ('Global_DailySet_DOM_' + (c + 1));
                        tasks.push({
                            id: offerId,
                            title: title,
                            description: desc,
                            pointValue: ptsNum,
                            destinationUrl: dest,
                            taskType: detectType(dest, title),
                            isCompleted: isDone
                        });
                    }
                    if (tasks.length > 0) return JSON.stringify(tasks);
                } catch (e) {}

                return "[]";
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản JavaScript tự động giải bình chọn hàng ngày (Poll Solver).
     * Click #btoption0 hoặc .btOption.b_cards.
     */
    fun createPollSolverScript(): String {
        return """
            (function() {
                try {
                    var opt = document.getElementById('btoption0') || 
                              document.querySelector('.btOption.b_cards') || 
                              document.querySelector('.btOption') ||
                              document.querySelector('div[id^="btoption"]') ||
                              document.querySelector('[data-option-id="0"]');

                    if (opt) {
                        var isChosen = opt.classList.contains('bt_selected') || 
                                       opt.getAttribute('aria-checked') === 'true' ||
                                       opt.querySelector('.bt_selected');
                        if (isChosen) {
                            return "true";
                        }

                        opt.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        opt.click();
                        return "true";
                    }
                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản JavaScript tự động giải Quiz Microsoft Rewards.
     * Click #rqStartQuiz, click options (.rqOption, div[iscorrectoption=True]), click next.
     */
    fun createQuizSolverScript(): String {
        return """
            (function() {
                try {
                    // 1. Click nút bắt đầu Quiz nếu có (#rqStartQuiz, Start playing, Bắt đầu, Làm bài)
                    var startBtn = document.getElementById('rqStartQuiz') || 
                                   document.querySelector('input#rqStartQuiz') || 
                                   document.querySelector('[id*="StartQuiz" i]') ||
                                   document.querySelector('input[value*="Start playing" i], input[value*="Take the quiz" i], input[value*="Bắt đầu" i], input[value*="Làm bài" i]');
                    if (!startBtn) {
                        var allButtons = document.querySelectorAll('button, div[role="button"], a.b_btn');
                        for (var b = 0; b < allButtons.length; b++) {
                            var btnTxt = (allButtons[b].innerText || allButtons[b].textContent || '').trim().toLowerCase();
                            if (btnTxt.indexOf('bắt đầu') !== -1 || btnTxt.indexOf('làm bài') !== -1 || btnTxt.indexOf('start playing') !== -1 || btnTxt.indexOf('take the quiz') !== -1) {
                                startBtn = allButtons[b];
                                break;
                            }
                        }
                    }
                    if (startBtn && (startBtn.offsetParent !== null || startBtn.getClientRects().length > 0)) {
                        startBtn.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        startBtn.click();
                        return "true";
                    }

                    // 2. Ưu tiên click đáp án chính xác nếu có thuộc tính iscorrectoption=True
                    var correctOption = document.querySelector('div[iscorrectoption="True"], div[iscorrectoption="true"], [iscorrectoption="True"], [iscorrectoption="true"], [data-is-correct="true"]');
                    if (correctOption && !correctOption.classList.contains('rqAnswerOptionSelected')) {
                        correctOption.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        correctOption.click();
                        return "true";
                    }

                    // 3. Fallback: Click các lựa chọn .rqOption, .rqAnswerOption, .bt_option, .wk_ans
                    var options = document.querySelectorAll('.rqOption:not([disabled]), .rqAnswerOption:not([disabled]), .bt_option:not([disabled]), .wk_ans:not([disabled]), .btOption:not([disabled]), div[id^="rqAnswerOption"], div[id^="btoption"], input.rqOption');
                    for (var i = 0; i < options.length; i++) {
                        var opt = options[i];
                        if (!opt.classList.contains('rqAnswerOptionSelected') && !opt.classList.contains('disabled')) {
                            opt.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            opt.click();
                            return "true";
                        }
                    }

                    // 4. Click nút sang câu hỏi tiếp theo nếu hiển thị (Next, Tiếp theo, Tiếp)
                    var nextBtn = document.getElementById('rqNextQuestion') || 
                                  document.querySelector('.rqNext, input[value*="Next" i], input[value*="Tiếp theo" i], input[value*="Tiếp" i], button[aria-label*="Tiếp theo" i], button[aria-label*="Tiếp" i], [aria-label*="Next" i], [id*="NextQuestion" i]');
                    if (!nextBtn) {
                        var allNext = document.querySelectorAll('button, div[role="button"], a.b_btn, input[type="button"], input[type="submit"]');
                        for (var n = 0; n < allNext.length; n++) {
                            var nTxt = (allNext[n].innerText || allNext[n].textContent || allNext[n].value || '').trim().toLowerCase();
                            if (nTxt === 'tiếp' || nTxt === 'tiếp theo' || nTxt.indexOf('tiếp') === 0 || nTxt === 'next') {
                                nextBtn = allNext[n];
                                break;
                            }
                        }
                    }
                    if (nextBtn && (nextBtn.offsetParent !== null || nextBtn.getClientRects().length > 0)) {
                        nextBtn.click();
                        return "true";
                    }

                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản JavaScript kích hoạt Streak Protection (isProtectionOn = true nếu đang tắt).
     */
    fun createStreakProtectionScript(): String {
        return """
            (function() {
                try {
                    // 1. Tìm switch / checkbox / toggle cho Streak Protection
                    var toggles = document.querySelectorAll('[aria-label*="Streak Protection" i], [data-bi-name*="streak_protection" i], [id*="streak-protection" i], [id*="streakProtection" i]');
                    for (var i = 0; i < toggles.length; i++) {
                        var el = toggles[i];
                        var isChecked = el.getAttribute('aria-checked') === 'true' || el.checked === true;
                        if (isChecked) {
                            return "true";
                        }
                        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        el.click();
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return "true";
                    }

                    // 2. Tìm trong khu vực section có chứa nhãn Streak Protection
                    var allSections = document.querySelectorAll('section, div[id*="streak"], div.react-aria-Disclosure');
                    for (var s = 0; s < allSections.length; s++) {
                        var sec = allSections[s];
                        if ((sec.innerText || '').toLowerCase().indexOf('streak protection') !== -1) {
                            var btn = sec.querySelector('button[role="switch"], input[type="checkbox"], button:not([disabled])');
                            if (btn) {
                                var checked = btn.getAttribute('aria-checked') === 'true' || btn.checked === true;
                                if (checked) return "true";
                                btn.click();
                                btn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                return "true";
                            }
                        }
                    }

                    // 3. Kiểm tra biến dữ liệu toàn cục
                    var streakData = (window.rewardsData && window.rewardsData.streakProtection) || 
                                     (window.UserStatus && window.UserStatus.streakProtection);
                    if (streakData && streakData.isProtectionOn === true) {
                        return "true";
                    }

                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    /**
     * Giải mã chuỗi JSON từ evaluateJavascript hoặc API thành danh sách DailyTask.
     * Tự động nhận diện cả 2 định dạng:
     * - JSON Array: [{"id":..., "title":...}, ...]
     * - JSON Object: {"props":{"pageProps":...}}, {"dailySetPromotions":...}, {"userStatus":...}
     */
    fun parseDailyTasks(rawOutput: String?): List<DailyTask> {
        if (rawOutput.isNullOrBlank() || rawOutput == "null" || rawOutput == "\"null\"" || rawOutput == "[]" || rawOutput == "\"[]\"") {
            return emptyList()
        }
        var sanitized = rawOutput.trim()
        if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
            try {
                val token = JSONTokener(sanitized).nextValue()
                sanitized = token.toString().trim()
            } catch (_: Exception) {
                sanitized = sanitized.substring(1, sanitized.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim()
            }
        }
        if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
            try {
                val token = JSONTokener(sanitized).nextValue()
                sanitized = token.toString().trim()
            } catch (_: Exception) {
                sanitized = sanitized.substring(1, sanitized.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim()
            }
        }

        // Nếu là JSON Array
        if (sanitized.startsWith("[")) {
            val fromArray = parseDailyTasksFromArray(sanitized)
            if (fromArray.isNotEmpty()) return fromArray
        }

        // Nếu là JSON Object
        if (sanitized.startsWith("{")) {
            val fromObj = parseDailyTasksFromJson(sanitized)
            if (fromObj.isNotEmpty()) return fromObj
        }

        return emptyList()
    }

    /**
     * Trích xuất danh sách DailyTask từ chuỗi JSON Array.
     */
    fun parseDailyTasksFromArray(jsonStr: String): List<DailyTask> {
        fun parseArray(array: JSONArray): List<DailyTask> {
            val list = mutableListOf<DailyTask>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", obj.optString("offerId", obj.optString("name", "daily_set_$i")))
                val title = obj.optString("title", obj.optString("name", "Daily Task ${i + 1}"))
                val pointValue = obj.optInt("pointValue", obj.optInt("pointProgressMax", obj.optInt("points", 10)))
                val destinationUrl = obj.optString("destinationUrl", obj.optString("destination", ""))
                val description = obj.optString("description", obj.optString("desc", ""))
                val taskType = obj.optString("taskType", detectTaskType(destinationUrl, title, description))
                val isCompleted = obj.optBoolean("isCompleted", obj.optBoolean("complete", false))
                val authKey = obj.optString("hash", obj.optString("authKey", ""))
                val activityType = obj.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                val userId = obj.optString("userId", "")
                list.add(DailyTask(id, title, pointValue, destinationUrl, taskType, isCompleted, authKey, activityType, userId, description))
            }
            return list
        }
        return try {
            parseArray(JSONArray(jsonStr))
        } catch (_: Exception) {
            try {
                var s = jsonStr.trim()
                if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                    s = s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                }
                parseArray(JSONArray(s))
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Trích xuất danh sách DailyTask từ chuỗi JSON Object (Next.js __NEXT_DATA__, getuserinfo, userStatus, v.v.).
     */
    fun parseDailyTasksFromJson(rawOutput: String?): List<DailyTask> {
        if (rawOutput.isNullOrBlank()) return emptyList()
        return try {
            var sanitized = rawOutput.trim()
            if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                try {
                    val token = JSONTokener(sanitized).nextValue()
                    sanitized = token.toString().trim()
                } catch (_: Exception) {
                    sanitized = sanitized.substring(1, sanitized.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim()
                }
            }
            if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                try {
                    val token = JSONTokener(sanitized).nextValue()
                    sanitized = token.toString().trim()
                } catch (_: Exception) {
                    sanitized = sanitized.substring(1, sanitized.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim()
                }
            }

            val rootJson = JSONObject(sanitized)
            val list = mutableListOf<DailyTask>()

            // Handle Next.js __NEXT_DATA__ props.pageProps & flyoutResult
            val dataObj = when {
                rootJson.has("props") -> rootJson.optJSONObject("props")?.optJSONObject("pageProps") ?: rootJson
                rootJson.has("flyoutResult") -> rootJson.optJSONObject("flyoutResult") ?: rootJson
                else -> rootJson
            }

            val userStatus = dataObj.optJSONObject("userStatus") ?: rootJson.optJSONObject("userStatus")
            val rootUserId = rootJson.optString("userId", "")
                .ifBlank { dataObj.optString("userId", "") }
                .ifBlank { userStatus?.optString("userId", "") ?: "" }
                .ifBlank { dataObj.optJSONObject("userProfile")?.optString("userId", "") ?: "" }

            // 1. Trích xuất dailySetPromotions
            val promos = dataObj.optJSONObject("dailySetPromotions")
                ?: userStatus?.optJSONObject("dailySetPromotions")
                ?: rootJson.optJSONObject("dailySetPromotions")

            if (promos != null) {
                val todayKey = RewardsBalanceExtractor.selectTodayDailySetKey(promos)
                val arr = if (todayKey != null) promos.optJSONArray(todayKey) else null
                if (arr != null) {
                    for (j in 0 until arr.length()) {
                        val item = arr.optJSONObject(j) ?: continue
                        val pMax = item.optInt("pointProgressMax", item.optInt("points", 10))
                        val pProg = item.optInt("pointProgress", 0)
                        val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                        val title = item.optString("title", item.optString("name", "Daily Task ${j + 1}"))
                        val dest = item.optString("destinationUrl", item.optString("destination", ""))
                        val offerId = item.optString("offerId", item.optString("id", item.optString("name", "daily_set_${j + 1}")))
                        val description = item.optString("description", item.optString("desc", ""))
                        val taskType = detectTaskType(dest, title, description)
                        val authKey = item.optString("hash", item.optString("authKey", ""))
                        val activityType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                        if (isSafePromotion(title, dest, pMax, description) && list.none { it.id == offerId }) {
                            list.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, activityType, rootUserId, description))
                        }
                    }
                }
            } else {
                val arr = dataObj.optJSONArray("dailySetPromotions")
                    ?: userStatus?.optJSONArray("dailySetPromotions")
                    ?: rootJson.optJSONArray("dailySetPromotions")
                    ?: dataObj.optJSONArray("dailySetItems")
                    ?: rootJson.optJSONArray("dailySetItems")
                if (arr != null) {
                    for (j in 0 until arr.length()) {
                        val item = arr.optJSONObject(j) ?: continue
                        val pMax = item.optInt("pointProgressMax", item.optInt("points", 10))
                        val pProg = item.optInt("pointProgress", 0)
                        val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                        val title = item.optString("title", item.optString("name", "Daily Task ${j + 1}"))
                        val dest = item.optString("destinationUrl", item.optString("destination", ""))
                        val offerId = item.optString("offerId", item.optString("id", item.optString("name", "daily_set_${j + 1}")))
                        val description = item.optString("description", item.optString("desc", ""))
                        val taskType = detectTaskType(dest, title, description)
                        val authKey = item.optString("hash", item.optString("authKey", ""))
                        val activityType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                        if (isSafePromotion(title, dest, pMax, description) && list.none { it.id == offerId }) {
                            list.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, activityType, rootUserId, description))
                        }
                    }
                }
            }

            // 2. Trích xuất morePromotions
            val morePromos = dataObj.optJSONArray("morePromotions")
                ?: userStatus?.optJSONArray("morePromotions")
                ?: rootJson.optJSONArray("morePromotions")
            if (morePromos != null) {
                for (j in 0 until morePromos.length()) {
                    val item = morePromos.optJSONObject(j) ?: continue
                    val pMax = if (item.has("pointProgressMax")) item.optInt("pointProgressMax", 0) else if (item.has("points")) item.optInt("points", 0) else 0
                    if (pMax <= 0) continue
                    val exclStatus = item.optString("exclusiveLockedFeatureStatus", "").lowercase(Locale.ROOT)
                    val isUnlockedAttr = item.optJSONObject("attributes")?.optString("is_unlocked", "True") ?: "True"
                    if (exclStatus == "locked" || isUnlockedAttr.equals("False", ignoreCase = true) || item.optBoolean("isLocked", false)) continue
                    val pProg = item.optInt("pointProgress", 0)
                    val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                    val title = item.optString("title", item.optString("name", "More Promotion ${j + 1}"))
                    val dest = item.optString("destinationUrl", item.optString("destination", ""))
                    val offerId = item.optString("offerId", item.optString("id", item.optString("name", "more_promo_${j + 1}")))
                    val description = item.optString("description", item.optString("desc", ""))
                    val taskType = detectTaskType(dest, title, description)
                    val authKey = item.optString("hash", item.optString("authKey", ""))
                    val activityType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                    if (isSafePromotion(title, dest, pMax, description) && list.none { it.id == offerId }) {
                        list.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, activityType, rootUserId, description))
                    }
                }
            }

            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Chuẩn hóa kết quả boolean từ Chromium evaluateJavascript.
     */
    fun parseBoolean(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"null\"") return false
        val cleaned = raw.trim().trim('"', '\'')
        return cleaned.equals("true", ignoreCase = true) || cleaned == "1"
    }

    /**
     * Thực thi kịch bản JavaScript trên WebView an toàn trong ngữ cảnh AndroidMainDispatcher kèm timeout bảo vệ chống treo.
     */
    private suspend fun evaluateScript(webView: WebView, script: String, timeoutMs: Long = 10000L): String = withContext(AndroidMainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)
            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
            }
            try {
                webView.evaluateJavascript(script) { result ->
                    handler.removeCallbacks(timeoutRunnable)
                    if (continuation.isActive) {
                        continuation.resume(result ?: "")
                    }
                }
            } catch (e: Exception) {
                handler.removeCallbacks(timeoutRunnable)
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
        }
    }

    /**
     * Thực thi click claim điểm (Ready to claim) trên WebView.
     */
    suspend fun executeReadyToClaim(webView: WebView): Boolean = withContext(AndroidMainDispatcher) {
        val raw = evaluateScript(webView, createReadyToClaimScript())
        parseBoolean(raw)
    }

    /**
     * Trích xuất danh sách các nhiệm vụ Daily Set từ WebView đã lọc bỏ các hoạt động không an toàn.
     */
    suspend fun extractDailyTasks(webView: WebView): List<DailyTask> = withContext(AndroidMainDispatcher) {
        val raw = evaluateScript(webView, createDailySetExtractorScript())
        parseDailyTasks(raw).filter { isSafePromotion(it.title, it.destinationUrl, it.pointValue, it.description) }
    }

    /**
     * Thực thi giải tự động một nhiệm vụ Daily Task tùy thuộc vào taskType (poll, quiz, hoặc urlreward).
     * Bọc trong try/catch bảo đảm error isolation khi thao tác DOM/WebView.
     */
    suspend fun solveDailyTask(webView: WebView, task: DailyTask): Boolean = withContext(AndroidMainDispatcher) {
        if (task.isCompleted) return@withContext true

        try {
            when (task.taskType.lowercase(Locale.ROOT)) {
                "poll" -> {
                    val raw = evaluateScript(webView, createPollSolverScript())
                    parseBoolean(raw)
                }
                "quiz" -> {
                    val raw = evaluateScript(webView, createQuizSolverScript())
                    parseBoolean(raw)
                }
                else -> {
                    val safeId = task.id.replace(Regex("[^a-zA-Z0-9_-]"), "")
                    val clickScript = """
                        (function() {
                            try {
                                var card = document.querySelector('[instrument*="' + '$safeId' + '"]') ||
                                           document.querySelector('[data-bi-name*="' + '$safeId' + '"]') ||
                                           document.querySelector('a[href*="' + '$safeId' + '"]');
                                if (card) {
                                    var a = card.tagName === 'A' ? card : card.querySelector('a');
                                    if (a) a.setAttribute('target', '_self');
                                    card.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                    card.click();
                                    return "true";
                                }
                                return "false";
                            } catch (e) {
                                return "false";
                            }
                        })();
                    """.trimIndent()
                    val clicked = parseBoolean(evaluateScript(webView, clickScript))
                    if (clicked) {
                        true
                    } else if (task.destinationUrl.isNotBlank() && 
                               task.destinationUrl.startsWith("https://") &&
                               isAllowedRewardsHost(task.destinationUrl) &&
                               webView.url != task.destinationUrl) {
                        webView.loadUrl(task.destinationUrl)
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * SEC-06: Whitelist kiểm tra host đích đến của nhiệm vụ, chỉ cho phép thuộc các domain chính thống
     * (.bing.com / .microsoft.com / .msn.com) trước khi gọi WebView.loadUrl() để triệt tiêu nguy cơ Open Redirect / phishing.
     */
    internal fun isAllowedRewardsHost(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val host = java.net.URI(url).host?.lowercase(Locale.ROOT) ?: return false
            host == "bing.com" || host.endsWith(".bing.com") ||
            host == "microsoft.com" || host.endsWith(".microsoft.com") ||
            host == "msn.com" || host.endsWith(".msn.com")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Kiểm tra xem nhiệm vụ có bắt buộc phải tương tác DOM (Quiz hoặc Poll) hay không.
     * urlreward / news / browse chạy thuần túy qua HTTP, không cần WebView.
     */
    fun isInteractiveDomTask(task: DailyTask): Boolean {
        val type = task.taskType.lowercase(Locale.ROOT)
        if (type == "quiz" || type == "poll") return true
        val text = "${task.title} ${task.description} ${task.destinationUrl}".lowercase(Locale.ROOT)
        val keywords = listOf(
            "trắc nghiệm", "đố vui", "câu đố", "thử thách", "thủ đô", "kiểm tra kiến thức",
            "quiz", "trivia", "poll", "khảo sát", "bình chọn", "form=dsetqu", "btoption"
        )
        return keywords.any { text.contains(it) }
    }

    /**
     * Xử lý an toàn cho một thẻ Daily Set đơn lẻ:
     * - Thẻ urlreward: Chạy thuần HTTP 100% qua executeSingleTaskHttp (reportActivityViaBingApi -> executeFastRedirect ngầm 1.5s), không chạm WebView.
     * - Thẻ quiz / poll:
     *   1. Thử HTTP reportActivityViaBingApi trước nếu có authKey + userId.
     *   2. Chỉ fallback WebView (solveDailyTask) khi task là quiz/poll cần click DOM.
     * - Error isolation: try/catch per task độc lập, lỗi 1 thẻ không làm hỏng toàn bộ pipeline.
     */
    suspend fun executeDailySetCardSafe(
        task: DailyTask,
        cookiesJson: String?,
        userAgent: String? = null,
        proxyUrl: String? = null,
        webView: WebView? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (task.isCompleted) return@withContext true

        try {
            val isDomTask = isInteractiveDomTask(task)

            // 1. Thẻ urlreward thuần túy -> Chạy 100% thuần HTTP, tuyệt đối không mở/click WebView
            if (!isDomTask) {
                return@withContext executeSingleTaskHttp(task, cookiesJson, userAgent, proxyUrl)
            }

            // 2. Thẻ Quiz hoặc Poll:
            // A. Ưu tiên thử nhanh qua Bing API trước
            if (task.authKey.isNotBlank() && task.userId.isNotBlank()) {
                try {
                    val earned = reportActivityViaBingApi(
                        task,
                        cookiesJson ?: "",
                        userAgent ?: DEFAULT_DESKTOP_EDGE_UA,
                        proxyUrl
                    )
                    if (earned > 0) return@withContext true
                } catch (_: Exception) {}
            }

            // B. Chỉ fallback WebView khi task là quiz/poll cần click DOM
            if (webView != null) {
                try {
                    withContext(AndroidMainDispatcher) {
                        solveDailyTask(webView, task)
                    }
                } catch (_: Exception) {
                    false
                }
            } else {
                executeFastRedirect(task.destinationUrl, task.offerId, cookiesJson, proxyUrl, userAgent ?: DEFAULT_DESKTOP_EDGE_UA)
            }
        } catch (_: Exception) {
            // Error isolation: Thẻ lỗi trả về false, không throw ra ngoài
            false
        }
    }

    /**
     * Điều phối thực thi an toàn cho 3 thẻ Daily Set (hoặc N thẻ):
     * - Error isolation: try/catch per task riêng biệt, 1 thẻ lỗi không làm gián đoạn các thẻ khác.
     * - Thẻ urlreward chạy thuần HTTP, chỉ fallback WebView khi task là quiz/poll cần click DOM.
     */
    suspend fun executeDailySetPipelineSafe(
        tasks: List<DailyTask>,
        cookiesJson: String?,
        userAgent: String? = null,
        proxyUrl: String? = null,
        webView: WebView? = null,
        delayBetweenCardsMs: Long = 1500L,
        onCardFinished: ((task: DailyTask, success: Boolean) -> Unit)? = null
    ): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        for ((idx, task) in tasks.withIndex()) {
            if (task.isCompleted) {
                results[task.id] = true
                try { onCardFinished?.invoke(task, true) } catch (_: Exception) {}
                continue
            }

            val success = try {
                executeDailySetCardSafe(task, cookiesJson, userAgent, proxyUrl, webView)
            } catch (_: Exception) {
                false
            }
            results[task.id] = success
            try { onCardFinished?.invoke(task, success) } catch (_: Exception) {}

            if (idx < tasks.size - 1 && delayBetweenCardsMs > 0) {
                try {
                    kotlinx.coroutines.delay(delayBetweenCardsMs)
                } catch (_: Exception) {}
            }
        }
        results
    }

    /**
     * Kích hoạt Streak Protection nếu đang ở trạng thái tắt.
     */
    suspend fun enableStreakProtection(webView: WebView): Boolean = withContext(AndroidMainDispatcher) {
        val raw = evaluateScript(webView, createStreakProtectionScript())
        parseBoolean(raw)
    }
}
