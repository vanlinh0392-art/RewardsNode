package com.agy.rewardsnode.core

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * WebViewClient thông minh tiết kiệm >= 90% dữ liệu mạng và tăng tốc cày điểm.
 * Chặn 100% hình ảnh, video/audio, web fonts và CDN thumbnails/tracking
 * trong khi bảo lưu 100% DOM, Scripts, API Rewards và Whitelist SSO / Arkose Labs / Cloudflare.
 * 100% Pure Android SDK.
 */
open class DataSaverWebViewClient(
    private val onPageFinishedCallback: ((view: WebView?, url: String?) -> Unit)? = null
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
        val host = uri.host ?: ""
        val path = uri.path ?: ""
        val urlString = uri.toString()

        // 1. Whitelist Bypass: Tuyệt đối KHÔNG chặn tài nguyên thuộc Arkose Labs, Live.com SSO, Turing Bing, Cloudflare
        if (isWhitelisted(host)) {
            return super.shouldInterceptRequest(view, request)
        }

        // 2. Chặn CDN thumbnails và telemetry/tracking
        if (isBlockedCdnOrTracking(host, path, urlString)) {
            return createEmptyResponse()
        }

        // 3. Chặn 100% định dạng media (ảnh, video, audio, web fonts)
        if (isBlockedMediaExtension(path)) {
            return createEmptyResponse()
        }

        return super.shouldInterceptRequest(view, request)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (url.isNullOrBlank()) return super.shouldInterceptRequest(view, url)
        val uri = try { android.net.Uri.parse(url) } catch (_: Exception) { null }
        if (uri != null) {
            val host = uri.host ?: ""
            val path = uri.path ?: ""
            if (isWhitelisted(host)) {
                return super.shouldInterceptRequest(view, url)
            }
            if (isBlockedCdnOrTracking(host, path, url)) {
                return createEmptyResponse()
            }
            if (isBlockedMediaExtension(path)) {
                return createEmptyResponse()
            }
        }
        return super.shouldInterceptRequest(view, url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback?.invoke(view, url)
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        return true
    }

    private fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(EMPTY_BYTES)
        )
    }

    companion object {
        private val EMPTY_BYTES = ByteArray(0)

        // Block 100% media extensions: png, jpg, jpeg, gif, webp, svg, ico, avif, bmp, mp4, webm, mp3, wav, ogg, m4a, aac, m3u8, woff, woff2, ttf, otf, eot
        val BLOCKED_EXTENSIONS = hashSetOf(
            // Image formats
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp",
            // Audio / Video formats
            "mp4", "webm", "mp3", "wav", "ogg", "m4a", "aac", "m3u8",
            // Web font formats
            "woff", "woff2", "ttf", "otf", "eot"
        )

        // CDN thumbnails & tracking domains to block
        val BLOCKED_TRACKING_DOMAINS = arrayOf(
            "google-analytics.com",
            "googletagmanager.com",
            "doubleclick.net",
            "scorecardresearch.com",
            "taboola.com",
            "outbrain.com",
            "adnxs.com",
            "criteo.com"
        )

        /**
         * Kiểm tra whitelist: Arkose Labs, Live.com, Microsoft SSO, Turing Bing, Cloudflare Challenges
         * Tuyệt đối không chặn bất kỳ tài nguyên nào từ các domain này để bảo vệ captcha và login SSO.
         */
        fun isWhitelisted(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            val h = host.lowercase(Locale.ROOT)
            return h == "arkoselabs.com" || h.endsWith(".arkoselabs.com") ||
                   h == "live.com" || h.endsWith(".live.com") ||
                   h == "account.microsoft.com" || h.endsWith(".account.microsoft.com") ||
                   h == "login.microsoftonline.com" || h.endsWith(".login.microsoftonline.com") ||
                   h == "aadcdn.msauth.net" || h.endsWith(".aadcdn.msauth.net") ||
                   h == "turing.bing.com" ||
                   h == "challenges.cloudflare.com" || h.endsWith(".challenges.cloudflare.com")
        }

        /**
         * Chặn CDN thumbnails và tracking:
         * th.bing.com, tse*.mm.bing.net, img-s-msn-com.*, assets.msn.com/media, bat.bing.com, clarity.ms
         */
        fun isBlockedCdnOrTracking(host: String?, path: String? = null, urlString: String? = null): Boolean {
            if (host.isNullOrBlank()) return false
            val h = host.lowercase(Locale.ROOT)
            val p = path?.lowercase(Locale.ROOT) ?: ""
            val u = urlString?.lowercase(Locale.ROOT) ?: ""

            // CDN Thumbnails / Tracking theo đặc tả G4.2
            if (h == "th.bing.com" || h.endsWith(".th.bing.com")) return true
            if (h.contains("mm.bing.net") || h.matches(Regex("""^tse.*\.mm\.bing\.net$"""))) return true
            if (h.contains("img-s-msn-com")) return true
            if (h.contains("assets.msn.com") && (p.contains("media") || u.contains("assets.msn.com/media"))) return true
            if (h == "bat.bing.com" || h.endsWith(".bat.bing.com")) return true
            if (h == "clarity.ms" || h.endsWith(".clarity.ms")) return true
            if (h.contains("bing.com") && (p == "/th" || p.startsWith("/th/"))) return true

            // Telemetry / Third-party tracking
            for (domain in BLOCKED_TRACKING_DOMAINS) {
                if (h.contains(domain)) return true
            }
            return false
        }

        /**
         * Chặn theo định dạng tệp mở rộng trong đường dẫn URL
         */
        fun isBlockedMediaExtension(path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            val p = path.lowercase(Locale.ROOT)
            val extension = p.substringAfterLast('.', "")
            return BLOCKED_EXTENSIONS.contains(extension)
        }
    }
}
