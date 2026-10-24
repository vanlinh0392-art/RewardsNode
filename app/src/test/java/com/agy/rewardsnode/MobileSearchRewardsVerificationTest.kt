package com.agy.rewardsnode

import com.agy.rewardsnode.core.AutomationEngine
import com.agy.rewardsnode.core.FingerprintEngine
import com.agy.rewardsnode.core.RewardsStateMachine.SearchPhase
import com.agy.rewardsnode.service.RewardsForegroundService
import org.junit.Assert.*
import org.junit.Test

/**
 * Bộ kiểm thử tự động xác minh toàn diện giải pháp sửa lỗi rổ Mobile Search
 * tích điểm Microsoft Rewards (100% chuẩn Bing Edge Mobile).
 */
class MobileSearchRewardsVerificationTest {

    /**
     * Test 1: Verify SearchPhase.isDesktop cho PHASE_1_PC (true), PHASE_2_MOBILE (false), PHASE_3_EDGE (true).
     */
    @Test
    fun test1_verifySearchPhaseIsDesktopRouting() {
        assertTrue("PHASE_1_PC phải là Desktop (isDesktop == true)", SearchPhase.PHASE_1_PC.isDesktop)
        assertFalse("PHASE_2_MOBILE phải là Mobile (isDesktop == false)", SearchPhase.PHASE_2_MOBILE.isDesktop)
        assertTrue("PHASE_3_EDGE phải là Desktop (isDesktop == true)", SearchPhase.PHASE_3_EDGE.isDesktop)
        assertTrue("DONE phải là Desktop (isDesktop == true)", SearchPhase.DONE.isDesktop)
    }

    /**
     * Test 2: Verify Mobile Edge Preset có EdgA, secChUaMobile="?1", secChUaPlatform="\"Android\"".
     */
    @Test
    fun test2_verifyMobileEdgePresetsCompliance() {
        assertTrue("Danh sách preset Mobile Edge không được rỗng", FingerprintEngine.MOBILE_EDGE.isNotEmpty())

        for (preset in FingerprintEngine.MOBILE_EDGE) {
            assertTrue("Preset ${preset.id} User-Agent phải chứa EdgA/", preset.userAgent.contains("EdgA/"))
            assertEquals("Preset ${preset.id} secChUaMobile phải là ?1", "?1", preset.secChUaMobile)
            assertEquals("Preset ${preset.id} secChUaPlatform phải là \"Android\"", "\"Android\"", preset.secChUaPlatform)
            assertTrue("Preset ${preset.id} secChUa phải chứa Microsoft Edge", preset.secChUa.contains("Microsoft Edge"))
            assertTrue("Preset ${preset.id} screenWidth phải là màn hình dọc", preset.screenWidth < preset.screenHeight)
        }

        val accountPreset = FingerprintEngine.getEdgePresetForAccount("acc_test_123", isDesktop = false)
        assertTrue("Account Preset phải chứa EdgA", accountPreset.userAgent.contains("EdgA/"))
        assertEquals("?1", accountPreset.secChUaMobile)
        assertEquals("\"Android\"", accountPreset.secChUaPlatform)
    }

    /**
     * Test 3: Verify Stealth Script cho Mobile chứa innerWidth, innerHeight, devicePixelRatio=3.0, maxTouchPoints=5.
     */
    @Test
    fun test3_verifyStealthScriptMobileViewportAndTouch() {
        val mobilePreset = FingerprintEngine.MOBILE_EDGE[0]
        val mobileScript = FingerprintEngine.createStealthScript(mobilePreset, isDesktop = false)

        assertTrue("Stealth script Mobile phải ghi đè innerWidth", mobileScript.contains("innerWidth"))
        assertTrue("Stealth script Mobile phải ghi đè innerHeight", mobileScript.contains("innerHeight"))
        assertTrue("Stealth script Mobile phải ghi đè devicePixelRatio", mobileScript.contains("devicePixelRatio"))
        assertTrue("devicePixelRatio phải đặt bằng 3.0", mobileScript.contains("3.0"))
        assertTrue("Stealth script Mobile phải ghi đè maxTouchPoints", mobileScript.contains("maxTouchPoints"))
        assertTrue("maxTouchPoints phải đặt bằng 5", mobileScript.contains("return 5;"))
        assertTrue("Stealth script Mobile phải đặt window.ontouchstart = null", mobileScript.contains("window.ontouchstart = null"))
        assertTrue("Stealth script Mobile phải giả lập TouchEvent", mobileScript.contains("TouchEvent"))
        assertTrue("Stealth script Mobile phải giả lập PointerEvent", mobileScript.contains("PointerEvent"))

        // Kiểm tra đối ứng: Desktop Stealth Script không chứa innerWidth override
        val desktopPreset = FingerprintEngine.DESKTOP_EDGE[0]
        val desktopScript = FingerprintEngine.createStealthScript(desktopPreset, isDesktop = true)
        assertFalse("Desktop stealth script không được ghi đè innerWidth", desktopScript.contains("innerWidth"))
        assertFalse("Desktop stealth script không được ghi đè devicePixelRatio", desktopScript.contains("devicePixelRatio"))
    }

    /**
     * Test 4: Verify search URL cho Mobile chứa form=STARTR và PC=EMMX01.
     */
    @Test
    fun test4_verifyMobileSearchUrlParameters() {
        val mobileUrl = RewardsForegroundService.buildSearchUrl("khám phá vũ trụ", SearchPhase.PHASE_2_MOBILE)
        assertTrue("URL Mobile Search phải chứa form=STARTR", mobileUrl.contains("form=STARTR"))
        assertTrue("URL Mobile Search phải chứa PC=EMMX01", mobileUrl.contains("PC=EMMX01"))
        assertTrue("URL Mobile Search phải chứa tham số query q=", mobileUrl.contains("q="))

        val pcUrl = RewardsForegroundService.buildSearchUrl("tin tức công nghệ", SearchPhase.PHASE_1_PC)
        assertTrue("URL PC Search phải chứa form=QBLH", pcUrl.contains("form=QBLH"))
        assertFalse("URL PC Search không được chứa STARTR", pcUrl.contains("form=STARTR"))

        val edgeUrl = RewardsForegroundService.buildSearchUrl("microsoft rewards", SearchPhase.PHASE_3_EDGE)
        assertTrue("URL Edge Bonus phải chứa form=MA13FW", edgeUrl.contains("form=MA13FW"))
        assertTrue("URL Edge Bonus phải chứa pc=EUPH", edgeUrl.contains("pc=EUPH"))
    }

    /**
     * Test 5: Verify build extraHeaders cho Mobile có đầy đủ Client Hints và _EDGE_V.
     */
    @Test
    fun test5_verifyExtraHeadersAndEdgeVCookie() {
        val mobilePreset = FingerprintEngine.getMobileLoginPreset()
        val headers = RewardsForegroundService.buildExtraHeaders(mobilePreset, isDesktop = false)

        assertEquals("https://www.bing.com/", headers["Referer"])
        assertEquals("?1", headers["sec-ch-ua-mobile"])
        assertEquals("\"Android\"", headers["sec-ch-ua-platform"])
        assertEquals(mobilePreset.secChUa, headers["sec-ch-ua"])
        assertEquals(mobilePreset.secChUaModel, headers["sec-ch-ua-model"])

        // Kiểm tra cookie xác thực Edge
        assertEquals(
            "_EDGE_V=1; path=/; domain=.bing.com",
            RewardsForegroundService.EDGE_V_COOKIE
        )

        // Kiểm tra đối ứng Desktop headers
        val desktopPreset = FingerprintEngine.DESKTOP_EDGE[0]
        val desktopHeaders = RewardsForegroundService.buildExtraHeaders(desktopPreset, isDesktop = true)
        assertEquals("?0", desktopHeaders["sec-ch-ua-mobile"])
        assertEquals(desktopPreset.secChUaPlatform, desktopHeaders["sec-ch-ua-platform"])
        assertFalse("Desktop headers không được có sec-ch-ua-model", desktopHeaders.containsKey("sec-ch-ua-model"))
    }

    /**
     * Test 6: Verify AutomationEngine.createMobileTouchInteractionScript phát sinh Touch và Pointer Events.
     */
    @Test
    fun test6_verifyMobileTouchInteractionScript() {
        val script = AutomationEngine.createMobileTouchInteractionScript()
        assertTrue("Script phải chứa touchstart", script.contains("touchstart"))
        assertTrue("Script phải chứa touchmove", script.contains("touchmove"))
        assertTrue("Script phải chứa touchend", script.contains("touchend"))
        assertTrue("Script phải chứa pointerdown", script.contains("pointerdown"))
        assertTrue("Script phải chứa pointermove", script.contains("pointermove"))
        assertTrue("Script phải chứa pointerup", script.contains("pointerup"))
        assertTrue("Script phải chứa pointerType: 'touch'", script.contains("pointerType: 'touch'"))
        assertTrue("Script phải chứa cuộn scrollBy", script.contains("window.scrollBy"))
    }

    /**
     * Test 7: Verify AutomationEngine.createHumanTypingScript hỗ trợ đầy đủ selectors Mobile và Desktop.
     */
    @Test
    fun test7_verifyMobileHumanTypingScriptFallbackSelectors() {
        val mobileUrl = RewardsForegroundService.buildSearchUrl("khám phá vũ trụ", SearchPhase.PHASE_2_MOBILE)
        val script = AutomationEngine.createHumanTypingScript("khám phá vũ trụ", mobileUrl)

        assertTrue("Script phải chứa input[type=\"search\"] cho Mobile fallback", script.contains("input[type=\"search\"]"))
        assertTrue("Script phải chứa form[action*=\"search\"]", script.contains("form[action*=\"search\"]"))
        assertTrue("Script phải chứa button[type=\"submit\"]", script.contains("button[type=\"submit\"]"))
        assertTrue("Script phải chứa input.form fallback", script.contains("input ? input.form : null"))
        assertTrue("Script phải chứa requestSubmit hoặc submit", script.contains("requestSubmit"))
        assertTrue("Script phải chứa custom searchUrl mobile", script.contains("form=STARTR"))
    }
}
