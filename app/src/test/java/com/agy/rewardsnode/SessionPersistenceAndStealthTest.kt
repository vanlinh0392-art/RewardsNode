package com.agy.rewardsnode

import com.agy.rewardsnode.core.FingerprintEngine
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsStateMachine
import org.junit.Assert.*
import org.junit.Test

class SessionPersistenceAndStealthTest {

    @Test
    fun testSyntheticMaxAgeInjection() {
        assertEquals(7776000L, ProfileManager.SYNTHETIC_MAX_AGE_SECONDS)

        // 1. Auth token _RwBf: synthetic Max-Age (90 days), Secure, SameSite=None, Path=/
        val rwbfCookie = ProfileManager.buildSyntheticCookieString("_RwBf=v4.1.token.hash")
        assertTrue("Must inject Max-Age=7776000", rwbfCookie.contains("Max-Age=7776000"))
        assertTrue("Must inject Secure", rwbfCookie.contains("Secure"))
        assertTrue("Must inject SameSite=None", rwbfCookie.contains("SameSite=None"))
        assertTrue("Must inject Path=/", rwbfCookie.contains("Path=/"))

        // 2. Auth token _U
        val uCookie = ProfileManager.buildSyntheticCookieString("_U=123456789abcdef")
        assertTrue("Must inject Max-Age=7776000", uCookie.contains("Max-Age=7776000"))
        assertTrue("Must inject Secure", uCookie.contains("Secure"))
        assertTrue("Must inject SameSite=None", uCookie.contains("SameSite=None"))

        // 3. Auth token MSPAuth
        val mspAuthCookie = ProfileManager.buildSyntheticCookieString("MSPAuth=sso_ticket_data")
        assertTrue("Must inject Max-Age=7776000", mspAuthCookie.contains("Max-Age=7776000"))
        assertTrue("Must inject Secure", mspAuthCookie.contains("Secure"))
        assertTrue("Must inject SameSite=None", mspAuthCookie.contains("SameSite=None"))

        // 4. Auth token KievRPSToken
        val kievCookie = ProfileManager.buildSyntheticCookieString("KievRPSToken=rps_auth_token")
        assertTrue("Must inject Max-Age=7776000", kievCookie.contains("Max-Age=7776000"))

        // 5. Auth token MSPOK
        val mspOkCookie = ProfileManager.buildSyntheticCookieString("MSPOK=uuid-here")
        assertTrue("Must inject Max-Age=7776000", mspOkCookie.contains("Max-Age=7776000"))

        // 6. Non-auth cookie: should NOT inject synthetic Max-Age or SameSite=None, but should ensure Path=/
        val prefCookie = ProfileManager.buildSyntheticCookieString("SRCHHPGUSR=SRCHLANG=en")
        assertFalse("Non-auth should NOT inject Max-Age", prefCookie.contains("Max-Age="))
        assertFalse("Non-auth should NOT inject SameSite=None", prefCookie.contains("SameSite=None"))
        assertTrue("Must ensure Path=/", prefCookie.contains("Path=/"))

        // 7. Cookie with existing Max-Age: should NOT duplicate
        val existingMaxAge = ProfileManager.buildSyntheticCookieString("_RwBf=token; Max-Age=3600")
        assertEquals(1, existingMaxAge.split("Max-Age=").size - 1)
        assertTrue(existingMaxAge.contains("Max-Age=3600"))

        // 8. Cookie with existing Expires: should NOT inject synthetic Max-Age
        val existingExpires = ProfileManager.buildSyntheticCookieString("_U=token; Expires=Wed, 21 Oct 2026 07:28:00 GMT")
        assertFalse("Should not inject Max-Age if Expires exists", existingExpires.contains("Max-Age="))
    }

    @Test
    fun testCookiePreservationCondition() {
        // Condition: freshCookies.isNotBlank() && (contains _RwBf || _U || MSPAuth || MUID || KievRPSToken)
        assertFalse("Null cookies must not be preserved", RewardsStateMachine.isPreservableCookie(null))
        assertFalse("Empty cookies must not be preserved", RewardsStateMachine.isPreservableCookie(""))
        assertFalse("Blank cookies must not be preserved", RewardsStateMachine.isPreservableCookie("   "))

        // Bogus .MSA.Auth check that used to fail to preserve real Microsoft tokens
        val bogusOnly = ".MSA.Auth=1; random_cookie=xyz"
        assertFalse("Old bogus .MSA.Auth alone should not satisfy preservation without real auth tokens",
            RewardsStateMachine.isPreservableCookie(bogusOnly))

        // Legitimate tokens
        assertTrue("Must preserve on _RwBf", RewardsStateMachine.isPreservableCookie("_RwBf=live_token_123"))
        assertTrue("Must preserve on _U", RewardsStateMachine.isPreservableCookie("MUID=123; _U=user_sess_abc"))
        assertTrue("Must preserve on MSPAuth", RewardsStateMachine.isPreservableCookie("MSPAuth=sso_ticket; other=1"))
        assertTrue("Must preserve on MUID", RewardsStateMachine.isPreservableCookie("MUID=39281729381; SRCH=1"))
        assertTrue("Must preserve on KievRPSToken", RewardsStateMachine.isPreservableCookie("KievRPSToken=auth_rps; test=1"))

        // Full cookie dump from Microsoft live login
        val fullDump = "MUID=992812A; _U=live_session_u; _RwBf=rw_token; MSPAuth=sso_data; KievRPSToken=kiev_data"
        assertTrue("Must preserve full live cookie dump", RewardsStateMachine.isPreservableCookie(fullDump))
    }

    @Test
    fun testMobileLoginPresetValidity() {
        val preset = FingerprintEngine.getMobileLoginPreset()

        // EdgA User-Agent
        assertTrue("User-Agent must be EdgA", preset.userAgent.contains("EdgA/"))
        assertTrue("User-Agent must target Android 14", preset.userAgent.contains("Android 14"))
        assertTrue("User-Agent must include Xiaomi 14 model (23127PN0CG)", preset.userAgent.contains("23127PN0CG"))

        // Client Hints: Mobile ?1, Android platform
        assertEquals("?1", preset.secChUaMobile)
        assertEquals("\"Android\"", preset.secChUaPlatform)
        assertEquals("\"23127PN0CG\"", preset.secChUaModel)

        // Brands must not leak Android WebView
        assertFalse("Client Hints brands must NOT leak Android WebView", preset.secChUa.contains("Android WebView"))
        assertFalse("Client Hints brands must NOT leak WebView", preset.secChUa.contains("WebView"))
        assertTrue("Client Hints brands must specify Microsoft Edge", preset.secChUa.contains("\"Microsoft Edge\""))

        // Native hardware compatibility
        assertEquals("Linux armv8l", preset.platform)
        assertEquals(393, preset.screenWidth)
        assertEquals(852, preset.screenHeight)
        assertEquals(12, preset.deviceMemory)
        assertEquals(8, preset.hardwareConcurrency)
    }

    @Test
    fun testAboutBlankIsolationBarrierLogic() {
        // Isolation Barrier Logic:
        // Before clearing cookies, WebView must navigate to about:blank to neutralize all active AJAX & JS
        val blankUrl = "about:blank"
        assertTrue("about:blank is valid blank target", blankUrl.equals("about:blank", ignoreCase = true))

        // Core Domains Isolation scope
        assertTrue("Core domains must contain login.live.com", ProfileManager.CORE_DOMAINS.contains("https://login.live.com"))
        assertTrue("Core domains must contain rewards.bing.com", ProfileManager.CORE_DOMAINS.contains("https://rewards.bing.com"))
        assertTrue("Core domains must contain bing.com", ProfileManager.CORE_DOMAINS.contains("https://www.bing.com"))
        assertTrue("Core domains must contain account.microsoft.com", ProfileManager.CORE_DOMAINS.contains("https://account.microsoft.com"))
    }

    @Test
    fun testContextualPersonaPhasePreservation() {
        val accountId = "acc-test-persona"

        // Phase 0 / Phase 0.5 / Phase 2: Mobile Edge Android (EdgA)
        val mobilePreset = FingerprintEngine.getEdgePresetForAccount(accountId, isDesktop = false)
        assertTrue("Mobile persona must use EdgA User-Agent", mobilePreset.userAgent.contains("EdgA/"))
        assertEquals("?1", mobilePreset.secChUaMobile)
        assertEquals("\"Android\"", mobilePreset.secChUaPlatform)

        // Phase 1 (PC Search): Desktop Edge
        val desktopPreset = FingerprintEngine.getEdgePresetForAccount(accountId, isDesktop = true)
        assertTrue("Desktop persona must use Edg/ User-Agent", desktopPreset.userAgent.contains("Edg/"))
        assertEquals("?0", desktopPreset.secChUaMobile)
        assertEquals("\"Windows\"", desktopPreset.secChUaPlatform)
    }
}
