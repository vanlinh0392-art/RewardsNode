package com.agy.rewardsnode

import com.agy.rewardsnode.core.FingerprintEngine
import org.junit.Assert.*
import org.junit.Test

class FingerprintEngineTest {

    @Test
    fun testPresetCountsAndCoverage() {
        assertEquals(12, FingerprintEngine.DESKTOP.size)
        assertEquals(12, FingerprintEngine.MOBILE.size)
        assertTrue(FingerprintEngine.DESKTOP_EDGE.isNotEmpty())
        assertTrue(FingerprintEngine.MOBILE_EDGE.isNotEmpty())
        assertEquals(7, FingerprintEngine.DESKTOP_EDGE.size)
        assertEquals(7, FingerprintEngine.MOBILE_EDGE.size)
    }

    @Test
    fun testVersionsAreWithin131To134() {
        val validMajorVersions = listOf("131", "132", "133", "134")

        for (preset in FingerprintEngine.DESKTOP) {
            val hasValidVer = validMajorVersions.any { ver ->
                preset.userAgent.contains("Chrome/$ver") || preset.userAgent.contains("Edg/$ver")
            }
            assertTrue("Desktop preset ${preset.id} must be Chrome/Edge 131-134", hasValidVer)
            assertTrue("secChUa must contain Not?A_Brand", preset.secChUa.contains("Not?A_Brand"))
        }

        for (preset in FingerprintEngine.MOBILE) {
            val hasValidVer = validMajorVersions.any { ver ->
                preset.userAgent.contains("Chrome/$ver") || preset.userAgent.contains("EdgA/$ver")
            }
            assertTrue("Mobile preset ${preset.id} must be Chrome/Edge 131-134", hasValidVer)
            assertTrue("secChUa must contain Not?A_Brand", preset.secChUa.contains("Not?A_Brand"))
        }
    }

    @Test
    fun testDesktopWindowsAndMacCoverage() {
        val winPresets = FingerprintEngine.DESKTOP.filter { it.platform == "Win32" }
        val macPresets = FingerprintEngine.DESKTOP.filter { it.platform == "MacIntel" }
        assertTrue("Must include Windows presets", winPresets.isNotEmpty())
        assertTrue("Must include macOS presets", macPresets.isNotEmpty())
    }

    @Test
    fun testMobileDeviceCoverage() {
        val devices = FingerprintEngine.MOBILE.map { it.secChUaModel }
        assertTrue(devices.any { it.contains("Pixel 9") })
        assertTrue(devices.any { it.contains("SM-S928B") || it.contains("SM-S931B") })
        assertTrue(devices.any { it.contains("23127PN0CG") })
    }

    @Test
    fun testSanitizeHeadersStripsXRequestedWith() {
        val headers = mapOf(
            "User-Agent" to "TestUA",
            "X-Requested-With" to "com.agy.rewardsnode",
            "x-requested-with" to "com.example.app",
            "Accept" to "*/*"
        )
        val sanitized = FingerprintEngine.sanitizeHeaders(headers)
        assertFalse(sanitized.containsKey("X-Requested-With"))
        assertFalse(sanitized.containsKey("x-requested-with"))
        assertEquals("TestUA", sanitized["User-Agent"])
        assertEquals("*/*", sanitized["Accept"])
    }

    @Test
    fun testStealthScriptContainsAntiDetectionAndHeaderStrip() {
        val desktopPreset = FingerprintEngine.DESKTOP[0]
        val script = FingerprintEngine.createStealthScript(desktopPreset, isDesktop = true)

        assertTrue(script.contains("navigator.webdriver"))
        assertTrue(script.contains("userAgentData"))
        assertTrue(script.contains("platformVersion: '15.0.0'"))
        assertTrue(script.contains("x-requested-with"))
        assertTrue(script.contains("setRequestHeader"))
        assertTrue(script.contains("window.fetch"))
    }

    @Test
    fun testMobileLoginPresetXiaomi14Edge133() {
        val preset = FingerprintEngine.getMobileLoginPreset()
        assertEquals("mobile_xiaomi14_edge133", preset.id)
        assertTrue("User-Agent must be EdgA 133", preset.userAgent.contains("EdgA/133"))
        assertTrue("User-Agent must target Xiaomi 14 model 23127PN0CG", preset.userAgent.contains("23127PN0CG"))
        assertTrue("secChUa must contain Microsoft Edge 133", preset.secChUa.contains("\"Microsoft Edge\";v=\"133\""))
        assertFalse("secChUa must NOT contain Android WebView", preset.secChUa.contains("WebView"))
        assertEquals("?1", preset.secChUaMobile)
        assertEquals("\"Android\"", preset.secChUaPlatform)
        assertEquals("\"23127PN0CG\"", preset.secChUaModel)
        assertEquals("Linux armv8l", preset.platform)
        assertEquals(12, preset.deviceMemory)
        assertEquals(8, preset.hardwareConcurrency)
        assertEquals(393, preset.screenWidth)
        assertEquals(852, preset.screenHeight)
    }

    @Test
    fun testStealthScriptNativeCloakingAndNoWebViewBrand() {
        val preset = FingerprintEngine.getMobileLoginPreset()
        val script = FingerprintEngine.createStealthScript(preset, isDesktop = false)

        assertTrue("Script must contain native cloaking [native code]", script.contains("[native code]"))
        assertTrue("Script must contain nativeRegistry WeakMap", script.contains("nativeRegistry"))
        assertTrue("Script must contain maxTouchPoints 10 for mobile", script.contains("maxTouchPoints"))
        assertTrue("Script must filter out WebView from brands", script.contains("indexOf('WebView') === -1"))
        assertFalse("Script must NOT have raw Android WebView in brandsJson", script.contains("\"brand\":\"Android WebView\""))
    }
}
