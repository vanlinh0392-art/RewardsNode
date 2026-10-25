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

    @Test
    fun `test selectTodayDailySetKey selects today in GMT+7 instead of yesterday in Los Angeles`() {
        val now = java.util.Date()
        val tzDefault = java.util.TimeZone.getDefault()
        val fmt = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply { timeZone = tzDefault }
        val todayStr = fmt.format(now)

        val cal = java.util.Calendar.getInstance(tzDefault).apply { time = now }
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = fmt.format(cal.time)

        cal.add(java.util.Calendar.DAY_OF_YEAR, 2)
        val tomorrowStr = fmt.format(cal.time)

        val promosJson = JSONObject().apply {
            put(yesterdayStr, org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("offerId", "Global_DailySet_yesterday_1")
                    put("complete", true)
                })
            })
            put(todayStr, org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("offerId", "Global_DailySet_today_1")
                    put("complete", false)
                })
            })
            put(tomorrowStr, org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("offerId", "Global_DailySet_tomorrow_1")
                    put("complete", false)
                })
            })
        }

        val selectedKey = RewardsBalanceExtractor.selectTodayDailySetKey(promosJson)
        assertEquals("Phải chọn chính xác key ngày hôm nay theo múi giờ thiết bị/GMT+7", todayStr, selectedKey)
    }

    @Test
    fun `test Daily Set is NOT marked done when 0 of 3 cards completed even if dailySet complete is true`() {
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        val todayKey = fmt.format(now)

        val flyoutJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 500)
                put("level", 2)
                put("dailySet", JSONObject().apply {
                    put("complete", true) // Fake flag / yesterday leftover
                    put("progress", 3)
                })
            })
            put("dailySetPromotions", JSONObject().apply {
                put(todayKey, org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "daily_set_1")
                        put("title", "Daily Card 1")
                        put("destinationUrl", "https://bing.com/search?q=1")
                        put("pointProgress", 0)
                        put("pointProgressMax", 10)
                        put("complete", false)
                    })
                    put(JSONObject().apply {
                        put("id", "daily_set_2")
                        put("title", "Daily Card 2 - Donate info")
                        put("destinationUrl", "https://bing.com/search?q=2")
                        put("pointProgress", 0)
                        put("pointProgressMax", 10)
                        put("complete", false)
                    })
                    put(JSONObject().apply {
                        put("id", "daily_set_3")
                        put("title", "Daily Card 3 - Quiz")
                        put("destinationUrl", "https://bing.com/search?q=3")
                        put("pointProgress", 0)
                        put("pointProgressMax", 10)
                        put("complete", false)
                    })
                })
            })
        }

        val parsed = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson)
        assertTrue(parsed.isSuccess)
        assertFalse("dailySetDone phải là FALSE khi chưa hoàn thành 3 thẻ hôm nay", parsed.dailySetDone)
        assertEquals(0, parsed.dailySetProgress)
        assertEquals(3, parsed.dailySetTotal)
        assertEquals(3, parsed.dailyTasks.size)
        assertTrue(parsed.dailyTasks.all { !it.isCompleted })
    }

    @Test
    fun `test Daily Set is marked done when all 3 cards completed`() {
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        val todayKey = fmt.format(now)

        val flyoutJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 530)
                put("level", 2)
            })
            put("dailySetPromotions", JSONObject().apply {
                put(todayKey, org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "daily_set_1")
                        put("pointProgress", 10)
                        put("pointProgressMax", 10)
                        put("complete", true)
                    })
                    put(JSONObject().apply {
                        put("id", "daily_set_2")
                        put("pointProgress", 10)
                        put("pointProgressMax", 10)
                        put("complete", true)
                    })
                    put(JSONObject().apply {
                        put("id", "daily_set_3")
                        put("pointProgress", 10)
                        put("pointProgressMax", 10)
                        put("complete", true)
                    })
                })
            })
        }

        val parsed = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson)
        assertTrue(parsed.isSuccess)
        assertTrue("dailySetDone phải là TRUE khi cả 3 thẻ hôm nay đã hoàn thành", parsed.dailySetDone)
        assertEquals(3, parsed.dailySetProgress)
        assertEquals(3, parsed.dailySetTotal)
        assertEquals(3, parsed.dailyTasks.size)
        assertTrue(parsed.dailyTasks.all { it.isCompleted })
    }

    @Test
    fun `test parseFlyoutJsonPayload parses pcSearch and mobileSearch from JSONArray`() {
        val flyoutJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 1396)
                put("level", 2)
                put("counters", JSONObject().apply {
                    put("pcSearch", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("pointProgress", 3)
                            put("pointProgressMax", 90)
                        })
                    })
                    put("mobileSearch", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("pointProgress", 60)
                            put("pointProgressMax", 60)
                        })
                    })
                })
            })
            put("dailySetPromotions", JSONObject())
        }

        val parsed = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson)
        assertTrue(parsed.isSuccess)
        assertEquals(3, parsed.pcProgress)
        assertEquals(90, parsed.pcMax)
        assertEquals(60, parsed.mobileProgress)
        assertEquals(60, parsed.mobileMax)
    }

    @Test
    fun `test combined 63 search points does not swallow mobile into PC`() {
        val testAccountId = "test_account_63"
        RewardsBalanceExtractor.latestTelemetryMap[testAccountId] = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            mobileProgress = 60,
            mobileMax = 60,
            pcProgress = 0,
            pcMax = 90
        )
        try {
            val flyoutJson = JSONObject().apply {
                put("userStatus", JSONObject().apply {
                    put("availablePoints", 1396)
                    put("level", 2)
                    put("levelInfoPromotion", JSONObject().apply {
                        put("attributes", JSONObject().apply {
                            put("bing_search_daily_points", "63")
                            put("level", "2")
                        })
                    })
                })
                put("dailySetPromotions", JSONObject())
            }

            val parsed = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson, accountId = testAccountId)
            assertTrue(parsed.isSuccess)
            assertEquals("mobileProgress phải được bảo toàn 60 thay vì bị gộp hết vào PC", 60, parsed.mobileProgress)
            assertEquals("pcProgress chỉ được gán phần dư còn lại (63 - 60 = 3)", 3, parsed.pcProgress)
        } finally {
            RewardsBalanceExtractor.latestTelemetryMap.remove(testAccountId)
        }
    }

    @Test
    fun `test parsePointsBreakdownHtml with nested span tags and extra numbers`() {
        val sampleFluentHtml = """
            <div class="c-heading">Today's points</div>
            <div class="row">
                <span>Desktop Bing search</span> Earn 3 pts <span>3</span> / <span>90</span>
            </div>
            <div class="row">
                <span>Mobile Bing search</span> Earn 3 pts <span>60</span> / <span>60</span>
            </div>
            <div class="row">
                <span>Other activities</span> Earn up to 159 pts <span>159</span>
            </div>
        """.trimIndent()

        val result = RewardsBalanceExtractor.parsePointsBreakdownHtml(sampleFluentHtml)
        assertTrue(result.isSuccess)
        assertEquals(3, result.pcProgress)
        assertEquals(90, result.pcMax)
        assertEquals(60, result.mobileProgress)
        assertEquals(60, result.mobileMax)
    }
}
