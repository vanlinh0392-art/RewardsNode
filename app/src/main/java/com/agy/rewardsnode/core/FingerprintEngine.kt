package com.agy.rewardsnode.core

import android.util.Log
import android.webkit.WebView

/**
 * Động cơ giả lập danh tính trình duyệt & vân tay phần cứng (Browser Stealth & Fingerprint Engine).
 * Cung cấp 24 cấu hình thiết bị thực tế (12 Desktop, 12 Mobile) chuẩn Edge/Chromium 131-134
 * và kịch bản JavaScript ghi đè Navigator, Client Hints (User-Agent Client Hints API),
 * triệt tiêu cờ navigator.webdriver và triệt tiêu header X-Requested-With.
 */
object FingerprintEngine {

    data class FingerprintPreset(
        val id: String,
        val userAgent: String,
        val secChUa: String,
        val secChUaMobile: String,
        val secChUaPlatform: String,
        val secChUaModel: String,
        val platform: String,
        val deviceMemory: Int,
        val hardwareConcurrency: Int,
        val screenWidth: Int,
        val screenHeight: Int
    )

    // 12 Cấu hình Desktop: Win 11 24H2, Win 10, macOS 15 Sequoia (Edge / Chrome 131-134)
    val DESKTOP = listOf(
        FingerprintPreset(
            id = "desktop_win11_edge134",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edg/134.0.0.0",
            secChUa = "\"Microsoft Edge\";v=\"134\", \"Chromium\";v=\"134\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 16,
            hardwareConcurrency = 16,
            screenWidth = 1920,
            screenHeight = 1080
        ),
        FingerprintPreset(
            id = "desktop_win11_chrome134",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"134\", \"Chromium\";v=\"134\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 16,
            hardwareConcurrency = 12,
            screenWidth = 2560,
            screenHeight = 1440
        ),
        FingerprintPreset(
            id = "desktop_win11_edge133",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0",
            secChUa = "\"Microsoft Edge\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 16,
            hardwareConcurrency = 16,
            screenWidth = 1920,
            screenHeight = 1080
        ),
        FingerprintPreset(
            id = "desktop_win11_chrome133",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 32,
            hardwareConcurrency = 16,
            screenWidth = 2560,
            screenHeight = 1440
        ),
        FingerprintPreset(
            id = "desktop_win11_edge132",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Edg/132.0.0.0",
            secChUa = "\"Microsoft Edge\";v=\"132\", \"Chromium\";v=\"132\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 16,
            hardwareConcurrency = 8,
            screenWidth = 1920,
            screenHeight = 1080
        ),
        FingerprintPreset(
            id = "desktop_win11_chrome132",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"132\", \"Chromium\";v=\"132\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 1920,
            screenHeight = 1200
        ),
        FingerprintPreset(
            id = "desktop_win11_edge131",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
            secChUa = "\"Microsoft Edge\";v=\"131\", \"Chromium\";v=\"131\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 16,
            hardwareConcurrency = 16,
            screenWidth = 1920,
            screenHeight = 1080
        ),
        FingerprintPreset(
            id = "desktop_win10_edge133",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0",
            secChUa = "\"Microsoft Edge\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 1920,
            screenHeight = 1080
        ),
        FingerprintPreset(
            id = "desktop_win10_chrome133",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 1600,
            screenHeight = 900
        ),
        FingerprintPreset(
            id = "desktop_win10_edge132",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Edg/132.0.0.0",
            secChUa = "\"Microsoft Edge\";v=\"132\", \"Chromium\";v=\"132\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 8,
            hardwareConcurrency = 6,
            screenWidth = 1366,
            screenHeight = 768
        ),
        FingerprintPreset(
            id = "desktop_mac15_edge134",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edg/134.0.0.0",
            secChUa = "\"Microsoft Edge\";v=\"134\", \"Chromium\";v=\"134\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"macOS\"",
            secChUaModel = "\"\"",
            platform = "MacIntel",
            deviceMemory = 16,
            hardwareConcurrency = 10,
            screenWidth = 1728,
            screenHeight = 1117
        ),
        FingerprintPreset(
            id = "desktop_mac15_chrome133",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"macOS\"",
            secChUaModel = "\"\"",
            platform = "MacIntel",
            deviceMemory = 16,
            hardwareConcurrency = 8,
            screenWidth = 1440,
            screenHeight = 900
        )
    )

    /**
     * Cấu hình thiết bị đăng nhập chuẩn Edge Android (EdgA 133) trên Xiaomi 14 (Android 14).
     * Khớp hoàn toàn với phần cứng thực tế (Qualcomm Adreno GPU, touchPoints=10, màn hình dọc 393x852)
     * nhằm triệt tiêu hoàn toàn cảnh báo "This browser or app may not be secure" từ Microsoft SSO.
     */
    val LOGIN_MOBILE_PRESET = FingerprintPreset(
        id = "mobile_xiaomi14_edge133",
        userAgent = "Mozilla/5.0 (Linux; Android 14; 23127PN0CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.122 Mobile Safari/537.36 EdgA/133.0.3065.82",
        secChUa = "\"Microsoft Edge\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
        secChUaMobile = "?1",
        secChUaPlatform = "\"Android\"",
        secChUaModel = "\"23127PN0CG\"",
        platform = "Linux armv8l",
        deviceMemory = 12,
        hardwareConcurrency = 8,
        screenWidth = 393,
        screenHeight = 852
    )

    fun getMobileLoginPreset(): FingerprintPreset = LOGIN_MOBILE_PRESET

    // 12 Cấu hình Mobile: Pixel 9/9 Pro, Galaxy S25/S24, Xiaomi 14 trên Android 14 & Android 15
    val MOBILE = listOf(
        FingerprintPreset(
            id = "mobile_pixel9pro_edge134",
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.35 Mobile Safari/537.36 EdgA/134.0.3124.51",
            secChUa = "\"Microsoft Edge\";v=\"134\", \"Chromium\";v=\"134\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"Pixel 9 Pro\"",
            platform = "Linux armv8l",
            deviceMemory = 16,
            hardwareConcurrency = 8,
            screenWidth = 412,
            screenHeight = 960
        ),
        FingerprintPreset(
            id = "mobile_pixel9pro_chrome134",
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.35 Mobile Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"134\", \"Chromium\";v=\"134\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"Pixel 9 Pro\"",
            platform = "Linux armv8l",
            deviceMemory = 16,
            hardwareConcurrency = 8,
            screenWidth = 412,
            screenHeight = 960
        ),
        FingerprintPreset(
            id = "mobile_pixel9_edge133",
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.122 Mobile Safari/537.36 EdgA/133.0.3065.82",
            secChUa = "\"Microsoft Edge\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"Pixel 9\"",
            platform = "Linux armv8l",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 412,
            screenHeight = 915
        ),
        FingerprintPreset(
            id = "mobile_pixel9_chrome133",
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.122 Mobile Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"Pixel 9\"",
            platform = "Linux armv8l",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 412,
            screenHeight = 915
        ),
        FingerprintPreset(
            id = "mobile_s25_edge134",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.35 Mobile Safari/537.36 EdgA/134.0.3124.51",
            secChUa = "\"Microsoft Edge\";v=\"134\", \"Chromium\";v=\"134\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S931B\"",
            platform = "Linux armv8l",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 390,
            screenHeight = 844
        ),
        FingerprintPreset(
            id = "mobile_s25_chrome134",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.35 Mobile Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"134\", \"Chromium\";v=\"134\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S931B\"",
            platform = "Linux armv8l",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 390,
            screenHeight = 844
        ),
        FingerprintPreset(
            id = "mobile_s24ultra_edge133",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.122 Mobile Safari/537.36 EdgA/133.0.3065.82",
            secChUa = "\"Microsoft Edge\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S928B\"",
            platform = "Linux armv8l",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 384,
            screenHeight = 832
        ),
        FingerprintPreset(
            id = "mobile_s24ultra_chrome133",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.122 Mobile Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S928B\"",
            platform = "Linux armv8l",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 384,
            screenHeight = 832
        ),
        FingerprintPreset(
            id = "mobile_xiaomi14_edge134",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23127PN0CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.35 Mobile Safari/537.36 EdgA/134.0.3124.51",
            secChUa = "\"Microsoft Edge\";v=\"134\", \"Chromium\";v=\"134\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"23127PN0CG\"",
            platform = "Linux armv8l",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 393,
            screenHeight = 852
        ),
        FingerprintPreset(
            id = "mobile_xiaomi14_chrome133",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23127PN0CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.122 Mobile Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"23127PN0CG\"",
            platform = "Linux armv8l",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 393,
            screenHeight = 852
        ),
        LOGIN_MOBILE_PRESET,
        FingerprintPreset(
            id = "mobile_s24_edge131",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36 EdgA/131.0.2903.86",
            secChUa = "\"Microsoft Edge\";v=\"131\", \"Chromium\";v=\"131\", \"Not?A_Brand\";v=\"99\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S921B\"",
            platform = "Linux armv8l",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 360,
            screenHeight = 780
        )
    )

    val DESKTOP_EDGE = DESKTOP.filter { it.userAgent.contains("Edg/") }
    val MOBILE_EDGE = MOBILE.filter { it.userAgent.contains("EdgA/") }

    fun getEdgePresetForAccount(accountId: String, isDesktop: Boolean): FingerprintPreset {
        val list = if (isDesktop) DESKTOP_EDGE else MOBILE_EDGE
        val index = (Math.abs(accountId.hashCode().toLong()) % list.size).toInt()
        return list[index]
    }

    fun getPresetForAccount(accountId: String, isDesktop: Boolean, edgePreferred: Boolean = true): FingerprintPreset {
        if (edgePreferred) {
            return getEdgePresetForAccount(accountId, isDesktop)
        }
        val list = if (isDesktop) DESKTOP else MOBILE
        return list[(Math.abs(accountId.hashCode().toLong()) % list.size).toInt()]
    }

    /**
     * Triệt tiêu cờ X-Requested-With ở tầng AndroidX WebKit native (Tầng 1).
     * Sử dụng Reflection an toàn để tương thích 100% với Pure SDK Kotlinc CLI và AndroidX WebKit.
     * Ngăn chặn WebView tự động gửi header tên package của ứng dụng lên máy chủ Microsoft.
     */
    fun stripXRequestedWith(webView: WebView) {
        try {
            val featureClass = Class.forName("androidx.webkit.WebViewFeature")
            val isSupportedMethod = featureClass.getMethod("isFeatureSupported", String::class.java)
            val isSupported = isSupportedMethod.invoke(null, "REQUESTED_WITH_HEADER_ALLOW_LIST") as? Boolean
            if (isSupported == true) {
                val compatClass = Class.forName("androidx.webkit.WebSettingsCompat")
                val setAllowListMethod = compatClass.getMethod(
                    "setRequestedWithHeaderOriginAllowList",
                    android.webkit.WebSettings::class.java,
                    java.util.Set::class.java
                )
                setAllowListMethod.invoke(null, webView.settings, emptySet<String>())
                Log.d("FingerprintEngine", "Đã cấu hình setRequestedWithHeaderOriginAllowList(emptySet)")
            }
        } catch (_: Throwable) {
            // Thiết bị hoặc môi trường runtime không có WebKit Compat, tự động fallback sang Tầng 2 & Tầng 3
        }
    }

    /**
     * Áp dụng WebSettingsCompat.setUserAgentMetadata (nếu WebKit hỗ trợ) hoặc cấu hình WebView Desktop properties.
     * Sử dụng Reflection an toàn để tương thích 100% với Pure SDK Kotlinc CLI và AndroidX WebKit.
     */
    fun applyUserAgentMetadata(webView: WebView, preset: FingerprintPreset, isDesktop: Boolean) {
        try {
            val featureClass = Class.forName("androidx.webkit.WebViewFeature")
            val isSupportedMethod = featureClass.getMethod("isFeatureSupported", String::class.java)
            val isSupported = isSupportedMethod.invoke(null, "USER_AGENT_METADATA") as? Boolean
            if (isSupported == true) {
                val builderClass = Class.forName("androidx.webkit.UserAgentMetadata\$Builder")
                val builderInstance = builderClass.getDeclaredConstructor().newInstance()

                val setMobileMethod = builderClass.getMethod("setMobile", Boolean::class.javaPrimitiveType)
                setMobileMethod.invoke(builderInstance, !isDesktop)

                val platform = if (isDesktop) {
                    if (preset.platform.contains("Mac")) "macOS" else "Windows"
                } else {
                    "Android"
                }
                val setPlatformMethod = builderClass.getMethod("setPlatform", String::class.java)
                setPlatformMethod.invoke(builderInstance, platform)

                val buildMethod = builderClass.getMethod("build")
                val metadata = buildMethod.invoke(builderInstance)

                val compatClass = Class.forName("androidx.webkit.WebSettingsCompat")
                val setMetadataMethod = compatClass.getMethod(
                    "setUserAgentMetadata",
                    android.webkit.WebSettings::class.java,
                    Class.forName("androidx.webkit.UserAgentMetadata")
                )
                setMetadataMethod.invoke(null, webView.settings, metadata)
                Log.d("FingerprintEngine", "Đã cấu hình setUserAgentMetadata(mobile=${!isDesktop}, platform=$platform)")
            }
        } catch (_: Throwable) {
            // Thiết bị hoặc WebKit không hỗ trợ, tự động fallback an toàn
        }
    }

    /**
     * Lọc bỏ header X-Requested-With khỏi header map (Tầng 2).
     */
    fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
        if (headers == null) return emptyMap()
        return headers.filterKeys { !it.equals("X-Requested-With", ignoreCase = true) }
    }

    private val BRAND_REGEX = Regex("\"?([^\"]+)\"?;v=\"?([^\"]+)\"?")

    /**
     * Chuyển đổi định dạng sec-ch-ua thành mảng JSON đối tượng [{brand, version}].
     */
    private fun parseBrandsToJson(secChUa: String): String {
        val parts = secChUa.split(",")
        val sb = StringBuilder("[")
        for (part in parts) {
            val trimmed = part.trim()
            val match = BRAND_REGEX.find(trimmed)
            if (match != null) {
                val brand = match.groupValues[1]
                val ver = match.groupValues[2]
                if (sb.length > 1) sb.append(",")
                sb.append("{\"brand\":").append("\"$brand\"").append(",\"version\":").append("\"$ver\"").append("}")
            }
        }
        sb.append("]")
        return if (sb.length > 2) sb.toString() else "[{\"brand\":\"Microsoft Edge\",\"version\":\"133\"}]"
    }

    /**
     * Sinh kịch bản JavaScript tiêm vào WebView để ghi đè navigator.userAgentData,
     * navigator.platform, navigator.webdriver, hardwareConcurrency, deviceMemory, screen specs,
     * và triệt tiêu header X-Requested-With trong XMLHttpRequest và Fetch API.
     */
    fun createStealthScript(preset: FingerprintPreset, isDesktop: Boolean): String {
        val brandsJson = parseBrandsToJson(preset.secChUa)
        val platformStr = preset.secChUaPlatform.replace("\"", "")
        val modelStr = preset.secChUaModel.replace("\"", "")
        val isMobileBool = preset.secChUaMobile.contains("1")
        val appVersion = preset.userAgent.removePrefix("Mozilla/")
        val platformVersion = when {
            isDesktop && (preset.secChUaPlatform.contains("Windows") || preset.secChUaPlatform.contains("macOS")) -> "15.0.0"
            preset.userAgent.contains("Android 15") -> "15.0.0"
            else -> "14.0.0"
        }

        return """
            (function() {
                try {
                    // ═══ 0. Native Cloaking Engine ([native code] spoofing) ═══
                    var nativeRegistry = new WeakMap();
                    var origToString = Function.prototype.toString;
                    function cloak(fn, name, length) {
                        if (typeof fn !== 'function') return fn;
                        nativeRegistry.set(fn, 'function ' + (name || '') + '() { [native code] }');
                        try {
                            Object.defineProperty(fn, 'name', { value: name || '', configurable: true });
                            Object.defineProperty(fn, 'length', { value: length || 0, configurable: true });
                        } catch (e) {}
                        return fn;
                    }
                    var patchedToString = function toString() {
                        if (this && nativeRegistry.has(this)) {
                            return nativeRegistry.get(this);
                        }
                        return origToString.apply(this, arguments);
                    };
                    nativeRegistry.set(patchedToString, 'function toString() { [native code] }');
                    try {
                        Object.defineProperty(Function.prototype, 'toString', {
                            value: patchedToString,
                            configurable: true,
                            writable: true
                        });
                    } catch (e) {
                        Function.prototype.toString = patchedToString;
                    }

                    // 1. Vô hiệu hóa navigator.webdriver
                    try {
                        var getWebdriver = cloak(function() { return undefined; }, 'get webdriver', 0);
                        Object.defineProperty(navigator, 'webdriver', {
                            get: getWebdriver,
                            configurable: true
                        });
                        if (typeof Navigator !== 'undefined' && Navigator.prototype) {
                            Object.defineProperty(Navigator.prototype, 'webdriver', {
                                get: getWebdriver,
                                configurable: true
                            });
                        }
                    } catch (e1) {}

                    // 2. Ghi đè navigator.platform
                    try {
                        var getPlatform = cloak(function() { return '${preset.platform}'; }, 'get platform', 0);
                        Object.defineProperty(navigator, 'platform', {
                            get: getPlatform,
                            configurable: true
                        });
                        if (typeof Navigator !== 'undefined' && Navigator.prototype) {
                            Object.defineProperty(Navigator.prototype, 'platform', {
                                get: getPlatform,
                                configurable: true
                            });
                        }
                    } catch (e2) {}

                    // 3. Ghi đè phần cứng (hardwareConcurrency & deviceMemory)
                    try {
                        var getHw = cloak(function() { return ${preset.hardwareConcurrency}; }, 'get hardwareConcurrency', 0);
                        var getMem = cloak(function() { return ${preset.deviceMemory}; }, 'get deviceMemory', 0);
                        Object.defineProperty(navigator, 'hardwareConcurrency', {
                            get: getHw,
                            configurable: true
                        });
                        Object.defineProperty(navigator, 'deviceMemory', {
                            get: getMem,
                            configurable: true
                        });
                        if (typeof Navigator !== 'undefined' && Navigator.prototype) {
                            Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', {
                                get: getHw,
                                configurable: true
                            });
                            Object.defineProperty(Navigator.prototype, 'deviceMemory', {
                                get: getMem,
                                configurable: true
                            });
                        }
                    } catch (e3) {}

                    // 4. Ghi đè kích thước màn hình
                    try {
                        var getW = cloak(function() { return ${preset.screenWidth}; }, 'get width', 0);
                        var getH = cloak(function() { return ${preset.screenHeight}; }, 'get height', 0);
                        Object.defineProperty(screen, 'width', { get: getW, configurable: true });
                        Object.defineProperty(screen, 'height', { get: getH, configurable: true });
                        Object.defineProperty(screen, 'availWidth', { get: getW, configurable: true });
                        Object.defineProperty(screen, 'availHeight', { get: getH, configurable: true });
                        if (typeof Screen !== 'undefined' && Screen.prototype) {
                            Object.defineProperty(Screen.prototype, 'width', { get: getW, configurable: true });
                            Object.defineProperty(Screen.prototype, 'height', { get: getH, configurable: true });
                            Object.defineProperty(Screen.prototype, 'availWidth', { get: getW, configurable: true });
                            Object.defineProperty(Screen.prototype, 'availHeight', { get: getH, configurable: true });
                        }
                    } catch (e4) {}

                    ${if (isDesktop) """
                    // 4b. Ghi đè innerWidth, innerHeight & devicePixelRatio trên Desktop
                    try {
                        var getInnerW = cloak(function() { return 1920; }, 'get innerWidth', 0);
                        var getInnerH = cloak(function() { return 1080; }, 'get innerHeight', 0);
                        var getDpr = cloak(function() { return 1.0; }, 'get devicePixelRatio', 0);
                        Object.defineProperty(window, 'innerWidth', { get: getInnerW, configurable: true });
                        Object.defineProperty(window, 'innerHeight', { get: getInnerH, configurable: true });
                        Object.defineProperty(window, 'devicePixelRatio', { get: getDpr, configurable: true });
                    } catch (eDim) {}
                    """.trimIndent() else """
                    // 4b. Ghi đè innerWidth, innerHeight & devicePixelRatio trên Mobile
                    try {
                        var getInnerW = cloak(function() { return ${preset.screenWidth}; }, 'get innerWidth', 0);
                        var getInnerH = cloak(function() { return ${preset.screenHeight}; }, 'get innerHeight', 0);
                        var getDpr = cloak(function() { return 3.0; }, 'get devicePixelRatio', 0);
                        Object.defineProperty(window, 'innerWidth', { get: getInnerW, configurable: true });
                        Object.defineProperty(window, 'innerHeight', { get: getInnerH, configurable: true });
                        Object.defineProperty(window, 'devicePixelRatio', { get: getDpr, configurable: true });
                    } catch (eDim) {}
                    """.trimIndent()}

                    // 5. Ghi đè userAgent & appVersion
                    try {
                        var getUa = cloak(function() { return '${preset.userAgent}'; }, 'get userAgent', 0);
                        var getAppVer = cloak(function() { return '$appVersion'; }, 'get appVersion', 0);
                        Object.defineProperty(navigator, 'userAgent', {
                            get: getUa,
                            configurable: true
                        });
                        Object.defineProperty(navigator, 'appVersion', {
                            get: getAppVer,
                            configurable: true
                        });
                        if (typeof Navigator !== 'undefined' && Navigator.prototype) {
                            Object.defineProperty(Navigator.prototype, 'userAgent', {
                                get: getUa,
                                configurable: true
                            });
                            Object.defineProperty(Navigator.prototype, 'appVersion', {
                                get: getAppVer,
                                configurable: true
                            });
                        }
                    } catch (e5) {}

                    // 6. Ghi đè navigator.userAgentData (Client Hints) & triệt tiêu hoàn toàn "Android WebView"
                    try {
                        var rawBrands = $brandsJson;
                        // Loại bỏ triệt để mọi brand chứa "WebView" hoặc "Android WebView"
                        var cleanBrands = rawBrands.filter(function(b) {
                            return b && b.brand && b.brand.indexOf('WebView') === -1;
                        });
                        if (cleanBrands.length === 0) {
                            cleanBrands = [
                                { brand: 'Microsoft Edge', version: '133' },
                                { brand: 'Chromium', version: '133' },
                                { brand: 'Not?A_Brand', version: '99' }
                            ];
                        }
                        var brandsList = Object.freeze(cleanBrands.map(function(item) {
                            return Object.freeze({ brand: item.brand, version: item.version });
                        }));

                        var getHighEntropyValuesFn = cloak(function getHighEntropyValues(hints) {
                            return Promise.resolve({
                                brands: brandsList,
                                mobile: $isMobileBool,
                                platform: '$platformStr',
                                platformVersion: '$platformVersion',
                                architecture: '${if (isDesktop) "x86" else "arm"}',
                                model: '$modelStr',
                                bitness: '64',
                                uaFullVersion: '${preset.userAgent.substringAfter("Chrome/").substringBefore(" ")}'
                            });
                        }, 'getHighEntropyValues', 1);

                        var toJSONFn = cloak(function toJSON() {
                            return {
                                brands: brandsList,
                                mobile: $isMobileBool,
                                platform: '$platformStr'
                            };
                        }, 'toJSON', 0);

                        var uaData = {
                            brands: brandsList,
                            mobile: $isMobileBool,
                            platform: '$platformStr',
                            getHighEntropyValues: getHighEntropyValuesFn,
                            toJSON: toJSONFn
                        };

                        var getUaData = cloak(function() { return uaData; }, 'get userAgentData', 0);

                        Object.defineProperty(navigator, 'userAgentData', {
                            get: getUaData,
                            configurable: true,
                            enumerable: true
                        });
                        if (typeof Navigator !== 'undefined' && Navigator.prototype) {
                            Object.defineProperty(Navigator.prototype, 'userAgentData', {
                                get: getUaData,
                                configurable: true,
                                enumerable: true
                            });
                        }
                    } catch (e6) {}

                    ${if (isDesktop) """
                    // 7. Giả lập Desktop: maxTouchPoints = 0, xóa ontouchstart, xóa TouchEvent, giả lập Mouse PointerEvent
                    try {
                        var getTouch = cloak(function() { return 0; }, 'get maxTouchPoints', 0);
                        Object.defineProperty(navigator, 'maxTouchPoints', {
                            get: getTouch,
                            configurable: true
                        });
                        if (typeof Navigator !== 'undefined' && Navigator.prototype) {
                            Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
                                get: getTouch,
                                configurable: true
                            });
                        }
                        try {
                            delete window.ontouchstart;
                        } catch (eDelTouch) {
                            window.ontouchstart = undefined;
                        }
                        if ('ontouchstart' in window) {
                            try {
                                Object.defineProperty(window, 'ontouchstart', {
                                    get: cloak(function() { return undefined; }, 'get ontouchstart', 0),
                                    configurable: true
                                });
                            } catch (eDef) {}
                        }
                        try {
                            delete window.TouchEvent;
                        } catch (eDelTe) {}
                        // Bổ sung giả lập Mouse PointerEvent thay vì TouchEvent
                        if (typeof PointerEvent !== 'undefined') {
                            var origPointerEvent = window.PointerEvent;
                            var patchedPointerEvent = function PointerEvent(type, params) {
                                params = params || {};
                                if (!params.pointerType) {
                                    params.pointerType = 'mouse';
                                }
                                return new origPointerEvent(type, params);
                            };
                            cloak(patchedPointerEvent, 'PointerEvent', 1);
                            try {
                                patchedPointerEvent.prototype = origPointerEvent.prototype;
                                window.PointerEvent = patchedPointerEvent;
                            } catch (ePe) {}
                        }
                        // Tẩy cookie layout di động SRCHHPGUSR trong document.cookie
                        try {
                            document.cookie = "SRCHHPGUSR=; expires=Thu, 01 Jan 1970 00:00:00 GMT; domain=.bing.com; path=/";
                            document.cookie = "SRCHHPGUSR=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
                        } catch (eCk) {}
                    } catch (eDesktopTouch) {}
                    """.trimIndent() else """
                    // 7. Bảo vệ maxTouchPoints, ontouchstart & TouchEvent/PointerEvent trên Mobile
                    try {
                        var getTouch = cloak(function() { return 5; }, 'get maxTouchPoints', 0);
                        Object.defineProperty(navigator, 'maxTouchPoints', {
                            get: getTouch,
                            configurable: true
                        });
                        if (typeof Navigator !== 'undefined' && Navigator.prototype) {
                            Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
                                get: getTouch,
                                configurable: true
                            });
                        }
                        window.ontouchstart = null;
                        if (typeof TouchEvent === 'undefined') {
                            window.TouchEvent = function TouchEvent() {};
                        }
                        if (typeof PointerEvent === 'undefined') {
                            window.PointerEvent = function PointerEvent() {};
                        }
                    } catch (eTouch) {}
                    """.trimIndent()}

                    // 8. Bảo vệ đối tượng window.chrome
                    try {
                        if (!window.chrome) {
                            window.chrome = {
                                app: { isInstalled: false },
                                runtime: {}
                            };
                        }
                    } catch (e7) {}

                    // 9. Giả lập hiển thị Foreground (document.visibilityState & document.hidden)
                    try {
                        var getVis = cloak(function() { return 'visible'; }, 'get visibilityState', 0);
                        var getHidden = cloak(function() { return false; }, 'get hidden', 0);
                        Object.defineProperty(document, 'visibilityState', {
                            get: getVis,
                            configurable: true
                        });
                        Object.defineProperty(document, 'hidden', {
                            get: getHidden,
                            configurable: true
                        });
                        if (typeof Document !== 'undefined' && Document.prototype) {
                            Object.defineProperty(Document.prototype, 'visibilityState', {
                                get: getVis,
                                configurable: true
                            });
                            Object.defineProperty(Document.prototype, 'hidden', {
                                get: getHidden,
                                configurable: true
                            });
                        }
                        window.dispatchEvent(new Event('focus'));
                        document.dispatchEvent(new Event('visibilitychange'));
                    } catch (e8) {}

                    // 10. Triệt tiêu cờ X-Requested-With trong XMLHttpRequest và fetch (Tầng 3) kèm Native Cloaking
                    try {
                        var origSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
                        var patchedSetRequestHeader = function setRequestHeader(header, value) {
                            if (header && typeof header === 'string' && header.toLowerCase() === 'x-requested-with') {
                                return;
                            }
                            return origSetRequestHeader.apply(this, arguments);
                        };
                        cloak(patchedSetRequestHeader, 'setRequestHeader', 2);
                        XMLHttpRequest.prototype.setRequestHeader = patchedSetRequestHeader;
                    } catch (e9) {}

                    try {
                        if (window.fetch) {
                            var origFetch = window.fetch;
                            var patchedFetch = function fetch(resource, init) {
                                if (init && init.headers) {
                                    if (typeof Headers !== 'undefined' && init.headers instanceof Headers) {
                                        init.headers.delete('X-Requested-With');
                                        init.headers.delete('x-requested-with');
                                    } else if (typeof init.headers === 'object') {
                                        delete init.headers['X-Requested-With'];
                                        delete init.headers['x-requested-with'];
                                    }
                                }
                                return origFetch.apply(this, arguments);
                            };
                            cloak(patchedFetch, 'fetch', 2);
                            window.fetch = patchedFetch;
                        }
                    } catch (e10) {}
                } catch (globalErr) {}
            })();
        """.trimIndent()
    }
}
