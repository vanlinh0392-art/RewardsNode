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

    @Test
    fun `test payload bing_search_daily_points 60 when pcProgress and mobileProgress are 0 is not erroneously assigned to PC 60 of 90`() {
        val testAccountId = "test_no_lump_pc_60"
        RewardsBalanceExtractor.latestTelemetryMap.remove(testAccountId)
        try {
            val flyoutJson = JSONObject().apply {
                put("userStatus", JSONObject().apply {
                    put("availablePoints", 1694)
                    put("level", 2)
                    put("levelInfoPromotion", JSONObject().apply {
                        put("attributes", JSONObject().apply {
                            put("bing_search_daily_points", "60")
                            put("level", "2")
                        })
                    })
                })
                put("dailySetPromotions", JSONObject())
            }

            val parsed = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson, accountId = testAccountId)
            assertTrue(parsed.isSuccess)
            assertNotEquals("bing_search_daily_points = 60 không được gán nhầm PC 60/90 khi pcProgress = 0, mobileProgress = 0", 60, parsed.pcProgress)
            assertEquals("pcProgress phải giữ nguyên 0 để enrich exact breakdown hoặc tránh ghi đè", 0, parsed.pcProgress)
            assertEquals("mobileProgress giữ nguyên 0 khi chưa có breakdown rõ ràng", 0, parsed.mobileProgress)
        } finally {
            RewardsBalanceExtractor.latestTelemetryMap.remove(testAccountId)
        }
    }

    @Test
    fun `test parsePointsBreakdownHtml extracts Desktop 0 of 90, Mobile 60 of 60, and Offers 85 with 100 percent accuracy`() {
        val sampleHtml = """
            <div class="points-breakdown-modal">
                <div class="c-heading">Today's points</div>
                <div class="row">
                    <span>Desktop Bing search</span>
                    <span>0/90</span>
                </div>
                <div class="row">
                    <span>Mobile Bing search</span>
                    <span>60/60</span>
                </div>
                <div class="row">
                    <span>Offers</span>
                    <span>85</span>
                </div>
            </div>
        """.trimIndent()

        val result = RewardsBalanceExtractor.parsePointsBreakdownHtml(sampleHtml)
        assertTrue(result.isSuccess)
        assertEquals(0, result.pcProgress)
        assertEquals(90, result.pcMax)
        assertEquals(60, result.mobileProgress)
        assertEquals(60, result.mobileMax)
        assertEquals(85, result.offersPoints)
        assertNotNull("pointsBreakdownJson không được null", result.pointsBreakdownJson)
        val json = JSONObject(result.pointsBreakdownJson!!)
        assertEquals(0, json.getJSONObject("desktopSearch").getInt("progress"))
        assertEquals(90, json.getJSONObject("desktopSearch").getInt("max"))
        assertEquals(60, json.getJSONObject("mobileSearch").getInt("progress"))
        assertEquals(60, json.getJSONObject("mobileSearch").getInt("max"))
        assertEquals(85, json.getJSONObject("offers").getInt("progress"))
    }

    @Test
    fun `test AccountProfile applyBalance does not overwrite existing mobileProgress 60 to 0 on same day`() {
        val profile = com.agy.rewardsnode.data.model.AccountProfile(
            id = "test_profile_mob_protect",
            email = "user@example.com",
            profileName = "Test User",
            level = 2,
            lastActiveDate = "2026-10-26",
            mobileProgress = 60,
            mobileMax = 60,
            pcProgress = 0,
            pcMax = 90
        )

        // Mô phỏng server trả về kết quả chưa có breakdown (mobileProgress = 0, pcProgress = 60 do gộp sai hoặc chưa có)
        val serverBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            availablePoints = 1754,
            pointsEarnedToday = 145,
            level = 2,
            pcProgress = 60,
            pcMax = 90,
            mobileProgress = 0,
            mobileMax = 60,
            source = "flyout_http",
            hasExactCounters = false
        )

        profile.applyBalance(serverBalance, todayStr = "2026-10-26")

        assertEquals("mobileProgress của AccountProfile không được bị ghi đè thành 0 khi cùng ngày", 60, profile.mobileProgress)
        assertEquals("pcProgress được hiệu chỉnh trừ mobileProgress đã có", 0, profile.pcProgress)
    }

    @Test
    fun `test AccountProfile applyBalance synchronously updates mobileProgress when server has exact breakdown`() {
        val profile = com.agy.rewardsnode.data.model.AccountProfile(
            id = "test_profile_breakdown_sync",
            email = "user@example.com",
            profileName = "Test User",
            level = 2,
            lastActiveDate = "2026-10-26",
            mobileProgress = 0,
            mobileMax = 60,
            pcProgress = 0,
            pcMax = 90
        )

        val breakdownJsonStr = RewardsBalanceExtractor.buildSanitizedBreakdownJson(
            pcProgress = 0,
            pcMax = 90,
            mobileProgress = 60,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 0,
            offersPoints = 85
        )

        val serverBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            availablePoints = 1754,
            pointsEarnedToday = 145,
            level = 2,
            pcProgress = 0,
            pcMax = 90,
            mobileProgress = 60,
            mobileMax = 60,
            source = "flyout_http+breakdown",
            hasExactCounters = true,
            pointsBreakdownJson = breakdownJsonStr
        )

        profile.applyBalance(serverBalance, todayStr = "2026-10-26")

        assertEquals("mobileProgress phải cập nhật thành 60 từ server", 60, profile.mobileProgress)
        assertEquals("pcProgress phải cập nhật thành 0 từ server", 0, profile.pcProgress)
        assertEquals("pointsBreakdownJson phải được cập nhật trên AccountProfile", breakdownJsonStr, profile.pointsBreakdownJson)
    }

    @Test
    fun `test prepareBreakdownCookies merges bing cookies and critical auth tokens safely`() {
        val snapshotJson = JSONObject().apply {
            put("https://login.live.com/", "RPSTAuth=token123; MSPRequ=req1; OtherLive=ignore")
            put("https://rewards.bing.com/", "_RwBf=rwb1; _U=u1; MUID=muid1; KievRPSToken=kiev1")
            put("https://www.bing.com/", "SRCHUSR=srch1; SRCHS=s1")
        }.toString()

        val cookies = RewardsBalanceExtractor.prepareBreakdownCookies(snapshotJson)
        assertTrue(cookies.contains("_U=u1"))
        assertTrue(cookies.contains("_RwBf=rwb1"))
        assertTrue(cookies.contains("MUID=muid1"))
        assertTrue(cookies.contains("KievRPSToken=kiev1"))
        assertTrue(cookies.contains("SRCHUSR=srch1"))
        assertTrue(cookies.contains("RPSTAuth=token123"))
        assertFalse("Không được nhồi cookie không liên quan", cookies.contains("OtherLive"))
    }
}
