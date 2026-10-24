package com.agy.rewardsnode.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RewardsBalanceExtractorBreakdownTest {

    @Test
    fun `test parsePointsBreakdownHtml extracts desktop 12 of 90 and mobile 60 of 60 correctly`() {
        val sampleHtml = """
            <div class="points-breakdown-modal">
                <div class="c-heading">Today's activity</div>
                <div class="row">
                    <span>Desktop Bing search</span>
                    <span>12/90</span>
                </div>
                <div class="row">
                    <span>Mobile Bing search</span>
                    <span>60/60</span>
                </div>
                <div class="row">
                    <span>Offers</span>
                    <span>159</span>
                </div>
            </div>
        """.trimIndent()

        val result = RewardsBalanceExtractor.parsePointsBreakdownHtml(sampleHtml)
        assertTrue(result.isSuccess)
        assertEquals(12, result.pcProgress)
        assertEquals(90, result.pcMax)
        assertEquals(60, result.mobileProgress)
        assertEquals(60, result.mobileMax)
        assertEquals(159, result.offersPoints)
    }

    @Test
    fun `test parsePointsBreakdownHtml handles Vietnamese localization`() {
        val sampleViHtml = """
            <div class="breakdown">
                <div>Tìm kiếm trên máy tính: 12 / 90</div>
                <div>Tìm kiếm trên thiết bị di động: 60 / 60</div>
                <div>Hoạt động khác: 159</div>
            </div>
        """.trimIndent()

        val result = RewardsBalanceExtractor.parsePointsBreakdownHtml(sampleViHtml)
        assertTrue(result.isSuccess)
        assertEquals(12, result.pcProgress)
        assertEquals(90, result.pcMax)
        assertEquals(60, result.mobileProgress)
        assertEquals(60, result.mobileMax)
        assertEquals(159, result.offersPoints)
    }

    @Test
    fun `test parseFlyoutJsonPayload extracts pcSearch and mobileSearch objects directly without lumping`() {
        val flyoutJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 1396)
                put("level", 2)
                put("counters", JSONObject().apply {
                    put("pcSearch", JSONObject().apply {
                        put("pointProgress", 12)
                        put("pointProgressMax", 90)
                    })
                    put("mobileSearch", JSONObject().apply {
                        put("pointProgress", 60)
                        put("pointProgressMax", 60)
                    })
                })
            })
            put("dailySetPromotions", JSONObject())
        }

        val parsed = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson)
        assertTrue(parsed.isSuccess)
        assertEquals(12, parsed.pcProgress)
        assertEquals(90, parsed.pcMax)
        assertEquals(60, parsed.mobileProgress)
        assertEquals(60, parsed.mobileMax)
    }
}
