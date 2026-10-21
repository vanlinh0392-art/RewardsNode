package com.agy.rewardsnode.core

/**
 * Động cơ giả lập danh tính trình duyệt & vân tay phần cứng (Browser Stealth & Fingerprint Engine).
 * Cung cấp 24 cấu hình thiết bị thực tế (12 Desktop, 12 Mobile) và kịch bản JavaScript
 * ghi đè Navigator, Client Hints (User-Agent Client Hints API) và triệt tiêu cờ navigator.webdriver.
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

    // 12 Cấu hình Desktop: Win 11/10 Edge/Chrome 122-126, macOS
    val DESKTOP = listOf(
        FingerprintPreset(
            id = "desktop_win11_edge126",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Microsoft Edge\";v=\"126\"",
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
            id = "desktop_win11_chrome126",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
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
            id = "desktop_win10_edge125",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"125\", \"Microsoft Edge\";v=\"125\"",
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
            id = "desktop_win10_chrome125",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"125\", \"Google Chrome\";v=\"125\"",
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
            id = "desktop_win11_edge124",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"124\", \"Microsoft Edge\";v=\"124\"",
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
            id = "desktop_win11_chrome124",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 16,
            hardwareConcurrency = 8,
            screenWidth = 1920,
            screenHeight = 1200
        ),
        FingerprintPreset(
            id = "desktop_win10_edge123",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36 Edg/123.0.0.0",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"123\", \"Microsoft Edge\";v=\"123\"",
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
            id = "desktop_win10_chrome123",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"123\", \"Google Chrome\";v=\"123\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 8,
            hardwareConcurrency = 4,
            screenWidth = 1920,
            screenHeight = 1080
        ),
        FingerprintPreset(
            id = "desktop_win11_edge122",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0",
            secChUa = "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Microsoft Edge\";v=\"122\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 16,
            hardwareConcurrency = 16,
            screenWidth = 2560,
            screenHeight = 1440
        ),
        FingerprintPreset(
            id = "desktop_win11_chrome122",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            secChUa = "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            secChUaModel = "\"\"",
            platform = "Win32",
            deviceMemory = 32,
            hardwareConcurrency = 16,
            screenWidth = 1920,
            screenHeight = 1080
        ),
        FingerprintPreset(
            id = "desktop_mac_edge126",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Microsoft Edge\";v=\"126\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"macOS\"",
            secChUaModel = "\"\"",
            platform = "MacIntel",
            deviceMemory = 16,
            hardwareConcurrency = 8,
            screenWidth = 1728,
            screenHeight = 1117
        ),
        FingerprintPreset(
            id = "desktop_mac_chrome125",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"125\", \"Google Chrome\";v=\"125\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"macOS\"",
            secChUaModel = "\"\"",
            platform = "MacIntel",
            deviceMemory = 16,
            hardwareConcurrency = 10,
            screenWidth = 1440,
            screenHeight = 900
        )
    )

    // 12 Cấu hình Mobile: Pixel 8/7, Galaxy S24/S23, Xiaomi 14/13
    val MOBILE = listOf(
        FingerprintPreset(
            id = "mobile_pixel8_chrome126",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"Pixel 8\"",
            platform = "Linux armv81",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 412,
            screenHeight = 915
        ),
        FingerprintPreset(
            id = "mobile_pixel8_edge126",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36 EdgA/126.0.2592.86",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Microsoft Edge\";v=\"126\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"Pixel 8\"",
            platform = "Linux armv81",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 412,
            screenHeight = 915
        ),
        FingerprintPreset(
            id = "mobile_pixel7_chrome125",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"125\", \"Google Chrome\";v=\"125\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"Pixel 7\"",
            platform = "Linux armv81",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 412,
            screenHeight = 915
        ),
        FingerprintPreset(
            id = "mobile_pixel7_edge125",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36 EdgA/125.0.2535.88",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"125\", \"Microsoft Edge\";v=\"125\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"Pixel 7\"",
            platform = "Linux armv81",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 412,
            screenHeight = 915
        ),
        FingerprintPreset(
            id = "mobile_s24ultra_chrome126",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S928B\"",
            platform = "Linux armv81",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 384,
            screenHeight = 832
        ),
        FingerprintPreset(
            id = "mobile_s24ultra_edge126",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36 EdgA/126.0.2592.86",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Microsoft Edge\";v=\"126\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S928B\"",
            platform = "Linux armv81",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 384,
            screenHeight = 832
        ),
        FingerprintPreset(
            id = "mobile_s23_chrome125",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"125\", \"Google Chrome\";v=\"125\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S911B\"",
            platform = "Linux armv81",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 360,
            screenHeight = 780
        ),
        FingerprintPreset(
            id = "mobile_s23_edge125",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36 EdgA/125.0.2535.88",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"125\", \"Microsoft Edge\";v=\"125\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"SM-S911B\"",
            platform = "Linux armv81",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 360,
            screenHeight = 780
        ),
        FingerprintPreset(
            id = "mobile_xiaomi14_chrome126",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23127PN0CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"23127PN0CG\"",
            platform = "Linux armv81",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 393,
            screenHeight = 852
        ),
        FingerprintPreset(
            id = "mobile_xiaomi14_edge126",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23127PN0CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36 EdgA/126.0.2592.86",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Microsoft Edge\";v=\"126\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"23127PN0CG\"",
            platform = "Linux armv81",
            deviceMemory = 12,
            hardwareConcurrency = 8,
            screenWidth = 393,
            screenHeight = 852
        ),
        FingerprintPreset(
            id = "mobile_xiaomi13_chrome124",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2211133G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.179 Mobile Safari/537.36",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"2211133G\"",
            platform = "Linux armv81",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 393,
            screenHeight = 851
        ),
        FingerprintPreset(
            id = "mobile_xiaomi13_edge124",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2211133G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.179 Mobile Safari/537.36 EdgA/124.0.2478.105",
            secChUa = "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"124\", \"Microsoft Edge\";v=\"124\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            secChUaModel = "\"2211133G\"",
            platform = "Linux armv81",
            deviceMemory = 8,
            hardwareConcurrency = 8,
            screenWidth = 393,
            screenHeight = 851
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
     * Chuyển đổi định dạng sec-ch-ua thành mảng JSON đối tượng [{brand, version}].
     */
    private fun parseBrandsToJson(secChUa: String): String {
        val parts = secChUa.split(",")
        val sb = StringBuilder("[")
        for (part in parts) {
            val trimmed = part.trim()
            val match = Regex("\"?([^\"]+)\"?;v=\"?([^\"]+)\"?").find(trimmed)
            if (match != null) {
                val brand = match.groupValues[1]
                val ver = match.groupValues[2]
                if (sb.length > 1) sb.append(",")
                sb.append("{\"brand\":").append("\"$brand\"").append(",\"version\":").append("\"$ver\"").append("}")
            }
        }
        sb.append("]")
        return if (sb.length > 2) sb.toString() else "[{\"brand\":\"Chromium\",\"version\":\"126\"}]"
    }

    /**
     * Sinh kịch bản JavaScript tiêm vào WebView để ghi đè navigator.userAgentData,
     * navigator.platform, navigator.webdriver, hardwareConcurrency, deviceMemory và screen specs.
     */
    fun createStealthScript(preset: FingerprintPreset, isDesktop: Boolean): String {
        val brandsJson = parseBrandsToJson(preset.secChUa)
        val platformStr = preset.secChUaPlatform.replace("\"", "")
        val modelStr = preset.secChUaModel.replace("\"", "")
        val isMobileBool = preset.secChUaMobile.contains("1")
        val appVersion = preset.userAgent.removePrefix("Mozilla/")

        return """
            (function() {
                try {
                    // 1. Vô hiệu hóa navigator.webdriver
                    try {
                        Object.defineProperty(navigator, 'webdriver', {
                            get: function() { return undefined; },
                            configurable: true
                        });
                    } catch (e1) {}

                    // 2. Ghi đè navigator.platform
                    try {
                        Object.defineProperty(navigator, 'platform', {
                            get: function() { return '${preset.platform}'; },
                            configurable: true
                        });
                    } catch (e2) {}

                    // 3. Ghi đè phần cứng (hardwareConcurrency & deviceMemory)
                    try {
                        Object.defineProperty(navigator, 'hardwareConcurrency', {
                            get: function() { return ${preset.hardwareConcurrency}; },
                            configurable: true
                        });
                        Object.defineProperty(navigator, 'deviceMemory', {
                            get: function() { return ${preset.deviceMemory}; },
                            configurable: true
                        });
                    } catch (e3) {}

                    // 4. Ghi đè kích thước màn hình
                    try {
                        Object.defineProperty(screen, 'width', { get: function() { return ${preset.screenWidth}; }, configurable: true });
                        Object.defineProperty(screen, 'height', { get: function() { return ${preset.screenHeight}; }, configurable: true });
                        Object.defineProperty(screen, 'availWidth', { get: function() { return ${preset.screenWidth}; }, configurable: true });
                        Object.defineProperty(screen, 'availHeight', { get: function() { return ${preset.screenHeight}; }, configurable: true });
                    } catch (e4) {}

                    // 5. Ghi đè userAgent & appVersion
                    try {
                        Object.defineProperty(navigator, 'userAgent', {
                            get: function() { return '${preset.userAgent}'; },
                            configurable: true
                        });
                        Object.defineProperty(navigator, 'appVersion', {
                            get: function() { return '$appVersion'; },
                            configurable: true
                        });
                    } catch (e5) {}

                    // 6. Ghi đè navigator.userAgentData (Client Hints)
                    try {
                        var brandsList = $brandsJson;
                        var uaData = {
                            brands: brandsList,
                            mobile: $isMobileBool,
                            platform: '$platformStr',
                            getHighEntropyValues: function(hints) {
                                return Promise.resolve({
                                    brands: brandsList,
                                    mobile: $isMobileBool,
                                    platform: '$platformStr',
                                    platformVersion: '${if (isDesktop) "15.0.0" else "14.0.0"}',
                                    architecture: '${if (isDesktop) "x86" else "arm"}',
                                    model: '$modelStr',
                                    bitness: '64',
                                    uaFullVersion: '${preset.userAgent.substringAfter("Chrome/").substringBefore(" ")}'
                                });
                            },
                            toJSON: function() {
                                return {
                                    brands: brandsList,
                                    mobile: $isMobileBool,
                                    platform: '$platformStr'
                                };
                            }
                        };

                        if (navigator.userAgentData) {
                            Object.defineProperty(navigator, 'userAgentData', {
                                get: function() { return uaData; },
                                configurable: true
                            });
                        } else {
                            Object.defineProperty(Navigator.prototype, 'userAgentData', {
                                get: function() { return uaData; },
                                configurable: true
                            });
                        }
                    } catch (e6) {}

                    // 7. Bảo vệ đối tượng window.chrome
                    try {
                        if (!window.chrome) {
                            window.chrome = {
                                app: { isInstalled: false },
                                runtime: {}
                            };
                        }
                    } catch (e7) {}

                    // 8. Giả lập hiển thị Foreground (document.visibilityState & document.hidden)
                    try {
                        Object.defineProperty(document, 'visibilityState', {
                            get: function() { return 'visible'; },
                            configurable: true
                        });
                        Object.defineProperty(document, 'hidden', {
                            get: function() { return false; },
                            configurable: true
                        });
                        Object.defineProperty(Document.prototype, 'visibilityState', {
                            get: function() { return 'visible'; },
                            configurable: true
                        });
                        Object.defineProperty(Document.prototype, 'hidden', {
                            get: function() { return false; },
                            configurable: true
                        });
                        window.dispatchEvent(new Event('focus'));
                        document.dispatchEvent(new Event('visibilitychange'));
                    } catch (e8) {}
                } catch (globalErr) {}
            })();
        """.trimIndent()
    }
}
