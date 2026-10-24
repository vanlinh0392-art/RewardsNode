package com.agy.rewardsnode

import com.agy.rewardsnode.core.AutomationEngine
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEngineTest {

    @Test
    fun testHumanTypingScript_ContainsRequiredSelectorsAndEvents() {
        val query = "tin tức công nghệ AI 2026"
        val script = AutomationEngine.createHumanTypingScript(query)

        // Form & Input selectors
        assertTrue(script.contains("sb_form_q"))
        assertTrue(script.contains("textarea[name=\"q\"]"))
        assertTrue(script.contains("input[name=\"q\"]"))
        assertTrue(script.contains("input[type=\"search\"]"))
        assertTrue(script.contains("form#sb_form"))
        assertTrue(script.contains("form[action*=\"search\"]"))
        assertTrue(script.contains("input.form"))

        // Buttons & Submit selectors
        assertTrue(script.contains("sb_form_go"))
        assertTrue(script.contains("button[type=\"submit\"]"))
        assertTrue(script.contains(".search.icon"))

        // Events
        assertTrue(script.contains("KeyboardEvent"))
        assertTrue(script.contains("InputEvent"))
        assertTrue(script.contains("keydown"))
        assertTrue(script.contains("keyup"))

        // Burst-typing Delays & Typo
        assertTrue(script.contains("65 - 35 + 1"))
        assertTrue(script.contains("200 - 120 + 1"))
        assertTrue(script.contains("Backspace"))
        assertTrue(script.contains("700 - 400 + 1"))

        // Fallback
        assertTrue(script.contains("window.location.href = fallbackUrl"))
    }

    @Test
    fun testHumanTypingScript_SanitizationAndCustomUrl() {
        val queryWithQuotes = "test 'single' and \"double\" and \\backslash\nand newline"
        val customUrl = "https://www.bing.com/search?q=test&form=STARTR&PC=EMMX01"
        val script = AutomationEngine.createHumanTypingScript(queryWithQuotes, customUrl)

        assertTrue(script.contains("STARTR"))
        assertTrue(script.contains("EMMX01"))
        assertTrue(!script.contains("\nand newline")) // newline sanitized
    }

    @Test
    fun testSmoothScrollScript() {
        val script = AutomationEngine.createSmoothScrollScript()
        assertTrue(script.contains("scrollBy"))
        assertTrue(script.contains("smooth"))
    }

    @Test
    fun testDiscreteScrollScript() {
        val script = AutomationEngine.createDiscreteScrollScript()
        assertTrue(script.contains("requestAnimationFrame"))
        assertTrue(script.contains("scrollBy"))
    }

    @Test
    fun testPiggybackExtractorScript() {
        val script = AutomationEngine.createPiggybackExtractorScript()
        assertTrue(script.contains("#b_results .b_rs a"))
        assertTrue(script.contains("JSON.stringify"))
    }

    @Test
    fun testComputeDynamicDwellTime() {
        val dwellClean = AutomationEngine.computeDynamicDwellTime(deltaPoints = 3, isRestricted = false)
        assertTrue(dwellClean in 3500L..5500L)

        val dwellZero = AutomationEngine.computeDynamicDwellTime(deltaPoints = 0, isRestricted = false)
        assertTrue(dwellZero in 6500L..8500L)

        val dwellRestricted = AutomationEngine.computeDynamicDwellTime(deltaPoints = 0, isRestricted = true)
        assertTrue(dwellRestricted in 10000L..14000L)
    }
}
