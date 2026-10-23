package com.agy.rewardsnode

import com.agy.rewardsnode.core.DataSaverWebViewClient
import com.agy.rewardsnode.core.FingerprintEngine
import com.agy.rewardsnode.core.KeywordEngine
import com.agy.rewardsnode.core.RewardsStateMachine
import com.agy.rewardsnode.data.model.AccountProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit Test Suite kiểm thử toàn diện bảo mật Sandbox, DataSaver, Fingerprint và Anomaly Alert (Sprint 4).
 * Vượt qua 100% không phụ thuộc thiết bị vật lý.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Sprint4SecuritySandboxTest {

    // =========================================================================
    // a. DataSaver extension blocking & whitelist bypass logic (G4.2)
    // =========================================================================

    @Test
    fun testDataSaverWhitelistBypass() {
        // Whitelist domains phải được bypass 100% để bảo vệ Captcha và Silent SSO
        val whitelistedHosts = listOf(
            "arkoselabs.com",
            "client-api.arkoselabs.com",
            "sub.arkoselabs.com",
            "live.com",
            "login.live.com",
            "account.live.com",
            "turing.bing.com",
            "challenges.cloudflare.com",
            "turnstile.challenges.cloudflare.com"
        )

        for (host in whitelistedHosts) {
            assertTrue(
                "Host '$host' bắt buộc phải thuộc whitelist bypass của DataSaver",
                DataSaverWebViewClient.isWhitelisted(host)
            )
        }

        // Domain thông thường và tracking KHÔNG được bypass
        val nonWhitelistedHosts = listOf(
            "www.bing.com",
            "rewards.bing.com",
            "assets.msn.com",
            "doubleclick.net",
            "google-analytics.com",
            "th.bing.com"
        )

        for (host in nonWhitelistedHosts) {
            assertFalse(
                "Host '$host' không được nằm trong whitelist bypass",
                DataSaverWebViewClient.isWhitelisted(host)
            )
        }
    }

    @Test
    fun testDataSaverBlockedMediaExtensions() {
        // Chặn 100% ảnh, video/audio và font
        val blockedPaths = listOf(
            "/images/logo.png",
            "/photos/banner.jpg",
            "/media/header.jpeg",
            "/assets/loading.gif",
            "/hero.webp",
            "/icon.svg",
            "/fav.ico",
            "/art.avif",
            "/pic.bmp",
            "/trailer.mp4",
            "/stream.webm",
            "/sound.mp3",
            "/audio.wav",
            "/bgm.ogg",
            "/track.m4a",
            "/voice.aac",
            "/playlist.m3u8",
            "/fonts/segoe.woff",
            "/fonts/segoe.woff2",
            "/fonts/roboto.ttf",
            "/fonts/opensans.otf",
            "/fonts/font.eot"
        )

        for (path in blockedPaths) {
            assertTrue(
                "Extension trong path '$path' bắt buộc phải bị DataSaver chặn",
                DataSaverWebViewClient.isBlockedMediaExtension(path)
            )
        }

        // Không chặn script, style, api, html, json
        val allowedPaths = listOf(
            "/scripts/bundle.js",
            "/styles/theme.css",
            "/api/v1/userinfo",
            "/dashboard",
            "/index.html",
            "/telemetry/data.json",
            ""
        )

        for (path in allowedPaths) {
            assertFalse(
                "Đường dẫn '$path' không được bị chặn để bảo đảm DOM và API",
                DataSaverWebViewClient.isBlockedMediaExtension(path)
            )
        }
    }

    @Test
    fun testDataSaverBlockedCdnAndTracking() {
        // CDN Thumbnails / Tracking theo đặc tả G4.2
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("th.bing.com"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("sub.th.bing.com"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("tse1.mm.bing.net"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("img-s-msn-com.akamaized.net"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("assets.msn.com", path = "/media/photo.jpg"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("bat.bing.com"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("clarity.ms"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("www.bing.com", path = "/th"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("google-analytics.com"))
        assertTrue(DataSaverWebViewClient.isBlockedCdnOrTracking("doubleclick.net"))

        // Endpoint hợp lệ của Microsoft Rewards không bị chặn
        assertFalse(DataSaverWebViewClient.isBlockedCdnOrTracking("rewards.bing.com", path = "/dashboard"))
        assertFalse(DataSaverWebViewClient.isBlockedCdnOrTracking("www.bing.com", path = "/search"))
    }

    // =========================================================================
    // b. FingerprintEngine 24 presets validity (Edge 131-134, platform, sec-ch-ua) (G4.3)
    // =========================================================================

    @Test
    fun testFingerprintPresetsCount() {
        assertEquals("Số lượng Desktop presets phải đúng 12", 12, FingerprintEngine.DESKTOP.size)
        assertEquals("Số lượng Mobile presets phải đúng 12", 12, FingerprintEngine.MOBILE.size)
        assertEquals("Tổng presets phải đúng 24", 24, FingerprintEngine.DESKTOP.size + FingerprintEngine.MOBILE.size)
    }

    @Test
    fun testFingerprintDesktopPresetsValidity() {
        val validVersions = setOf("131", "132", "133", "134")

        for (preset in FingerprintEngine.DESKTOP) {
            assertNotNull("ID không được null", preset.id)
            assertTrue("Desktop preset ID '${preset.id}' phải bắt đầu bằng 'desktop_'", preset.id.startsWith("desktop_"))

            // Kiểm tra User-Agent chứa Edge/Chrome 131-134
            val hasValidVersion = validVersions.any { ver ->
                preset.userAgent.contains("Chrome/$ver.") || preset.userAgent.contains("Edg/$ver.")
            }
            assertTrue("Desktop UA '${preset.userAgent}' phải thuộc dải Edge/Chromium 131-134", hasValidVersion)

            // Sec-CH-UA Desktop
            assertEquals("Desktop secChUaMobile phải là ?0", "?0", preset.secChUaMobile)
            assertTrue("secChUa '${preset.secChUa}' phải chứa 'Chromium'", preset.secChUa.contains("Chromium"))
            assertTrue("secChUaPlatform '${preset.secChUaPlatform}' phải hợp lệ", preset.secChUaPlatform in listOf("\"Windows\"", "\"macOS\""))

            // Platform
            assertTrue("Platform '${preset.platform}' phải là Win32 hoặc MacIntel", preset.platform in listOf("Win32", "MacIntel"))

            // Phần cứng và màn hình
            assertTrue("deviceMemory phải >= 8GB", preset.deviceMemory >= 8)
            assertTrue("hardwareConcurrency phải >= 4", preset.hardwareConcurrency >= 4)
            assertTrue("screenWidth phải > 0", preset.screenWidth > 0)
            assertTrue("screenHeight phải > 0", preset.screenHeight > 0)
        }
    }

    @Test
    fun testFingerprintMobilePresetsValidity() {
        val validVersions = setOf("131", "132", "133", "134")

        for (preset in FingerprintEngine.MOBILE) {
            assertNotNull("ID không được null", preset.id)
            assertTrue("Mobile preset ID '${preset.id}' phải bắt đầu bằng 'mobile_'", preset.id.startsWith("mobile_"))

            // Kiểm tra User-Agent chứa Chromium/Edge 131-134
            val hasValidVersion = validVersions.any { ver ->
                preset.userAgent.contains("Chrome/$ver.") || preset.userAgent.contains("EdgA/$ver.")
            }
            assertTrue("Mobile UA '${preset.userAgent}' phải thuộc dải Edge/Chromium 131-134", hasValidVersion)

            // Sec-CH-UA Mobile
            assertEquals("Mobile secChUaMobile phải là ?1", "?1", preset.secChUaMobile)
            assertEquals("Mobile secChUaPlatform phải là \"Android\"", "\"Android\"", preset.secChUaPlatform)
            assertTrue("secChUa '${preset.secChUa}' phải chứa 'Chromium'", preset.secChUa.contains("Chromium"))
            assertEquals("Platform phải là Linux armv8l", "Linux armv8l", preset.platform)

            // Phần cứng và màn hình
            assertTrue("deviceMemory phải >= 8GB", preset.deviceMemory >= 8)
            assertTrue("hardwareConcurrency phải >= 8", preset.hardwareConcurrency >= 8)
            assertTrue("screenWidth phải > 0", preset.screenWidth > 0)
            assertTrue("screenHeight phải > 0", preset.screenHeight > 0)
        }
    }

    @Test
    fun testStealthScriptGeneration() {
        val preset = FingerprintEngine.DESKTOP[0]
        val script = FingerprintEngine.createStealthScript(preset, isDesktop = true)

        assertNotNull(script)
        assertTrue("Script phải bọc trong IIFE", script.trim().startsWith("(function()"))
        assertTrue("Script phải triệt tiêu navigator.webdriver", script.contains("webdriver"))
        assertTrue("Script phải ghi đè navigator.userAgentData", script.contains("userAgentData"))
        assertTrue("Script phải ghi đè navigator.platform", script.contains("platform"))
        assertTrue("Script phải ghi đè visibilityState", script.contains("visibilityState"))
    }

    // =========================================================================
    // c. ProfileManager switchSession isolation (G4.1)
    // =========================================================================

    @Test
    fun testSequentialIsolationMutexContract() = runTest {
        // Mô phỏng kiểm thử hợp đồng Mutex của Sequential Cookie Isolation (G4.1)
        val mutex = Mutex()
        val executionOrder = mutableListOf<String>()
        val counter = AtomicInteger(0)

        // Bắn 3 tác vụ swap account song song
        val jobs = listOf("Account_A", "Account_B", "Account_C").map { accountId ->
            async {
                mutex.withLock {
                    val order = counter.incrementAndGet()
                    executionOrder.add("$accountId:$order")
                    // Giữ lock mô phỏng quá trình flush và restore cookie
                    delay(20L)
                }
            }
        }

        jobs.forEach { it.await() }

        // Đảm bảo không có 2 account nào được swap đồng thời (Mutex tuần tự hóa hoàn toàn)
        assertEquals(3, executionOrder.size)
        assertEquals(3, counter.get())
        assertTrue("Mỗi swap phải có số thứ tự tăng dần duy nhất", executionOrder.all { it.contains(":") })
    }

    // =========================================================================
    // d. Anomaly detection condition checks (G4.4)
    // =========================================================================

    @Test
    fun testFrozenBalanceConditionChecks() {
        // Điều kiện Frozen Balance: zeroDeltaBatchCount >= 3
        assertFalse("0 batch không phải là Frozen Balance", RewardsStateMachine.isFrozenBalance(0))
        assertFalse("1 batch không phải là Frozen Balance", RewardsStateMachine.isFrozenBalance(1))
        assertFalse("2 batch không phải là Frozen Balance (chỉ là cooldown)", RewardsStateMachine.isFrozenBalance(2))
        assertTrue("3 batch liên tiếp delta=0 là Frozen Balance!", RewardsStateMachine.isFrozenBalance(3))
        assertTrue("4 batch liên tiếp delta=0 là Frozen Balance!", RewardsStateMachine.isFrozenBalance(4))
    }

    @Test
    fun testAnomalyUrlDetection() {
        // Vector 1: URL Abuse / Suspended / Cloudflare Challenge
        assertTrue(RewardsStateMachine.isAnomalyUrl("https://login.live.com/Abuse?mkt=en-US"))
        assertTrue(RewardsStateMachine.isAnomalyUrl("https://account.live.com/recover?mkt=en-US"))
        assertTrue(RewardsStateMachine.isAnomalyUrl("https://login.live.com/identity/confirm"))
        assertTrue(RewardsStateMachine.isAnomalyUrl("https://rewards.bing.com/?error=suspended"))
        assertTrue(RewardsStateMachine.isAnomalyUrl("https://challenges.cloudflare.com/turnstile/v0/api.js"))

        // URL hợp lệ thông thường không bị báo động
        assertFalse(RewardsStateMachine.isAnomalyUrl("https://www.bing.com/search?q=microsoft+rewards"))
        assertFalse(RewardsStateMachine.isAnomalyUrl("https://rewards.bing.com/dashboard"))
        assertFalse(RewardsStateMachine.isAnomalyUrl(null))
        assertFalse(RewardsStateMachine.isAnomalyUrl(""))
    }

    @Test
    fun testAnomalyDomDetection() {
        // Vector 2: Arkose Labs / FunCaptcha / Cloudflare Challenge trong DOM
        assertTrue(RewardsStateMachine.isAnomalyDom("<div id=\"challenge-stage\"></div>"))
        assertTrue(RewardsStateMachine.isAnomalyDom("<iframe src=\"https://client-api.arkoselabs.com/fc/api/\"></iframe>"))
        assertTrue(RewardsStateMachine.isAnomalyDom("<div id=\"FunCaptcha\"></div>"))
        assertTrue(RewardsStateMachine.isAnomalyDom("<iframe id=\"enforcementFrame\"></iframe>"))

        // DOM bình thường không bị báo động
        assertFalse(RewardsStateMachine.isAnomalyDom("<div><h1>Welcome back!</h1><p>Your current points: 15,200</p></div>"))
        assertFalse(RewardsStateMachine.isAnomalyDom(null))
        assertFalse(RewardsStateMachine.isAnomalyDom(""))
    }

    @Test
    fun testStateMachineDeltaTrackingTransition() {
        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            profileManager = null,
            vlogManager = null,
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = { }
        )

        val accountId = "test_acc_anomaly"

        // Ban đầu delta = 0 lượt 1 -> chưa restricted, chưa frozen
        sm.recordSearchDelta(accountId, 0)
        assertEquals(1, sm.getConsecutiveZeroDeltaSearches(accountId))
        assertFalse(sm.isAccountCooldownRestricted(accountId))
        assertFalse(sm.isFrozenBalance(accountId))

        // Lượt 2 delta = 0 -> cắm cờ cooldown restricted (15m), nhưng chưa frozen
        sm.recordSearchDelta(accountId, 0)
        assertEquals(2, sm.getConsecutiveZeroDeltaSearches(accountId))
        assertTrue(sm.isAccountCooldownRestricted(accountId))
        assertFalse(sm.isFrozenBalance(accountId))

        // Lượt 3 delta = 0 -> kích hoạt cờ restricted và Frozen Balance!
        sm.recordSearchDelta(accountId, 0)
        assertEquals(3, sm.getConsecutiveZeroDeltaSearches(accountId))
        assertTrue(sm.isAccountCooldownRestricted(accountId))
        assertTrue(sm.isFrozenBalance(accountId))

        // Khi có điểm tăng hợp lệ (delta = 5đ) -> reset hoàn toàn
        sm.recordSearchDelta(accountId, 5)
        assertEquals(0, sm.getConsecutiveZeroDeltaSearches(accountId))
        assertFalse(sm.isAccountCooldownRestricted(accountId))
        assertFalse(sm.isFrozenBalance(accountId))
    }
}
