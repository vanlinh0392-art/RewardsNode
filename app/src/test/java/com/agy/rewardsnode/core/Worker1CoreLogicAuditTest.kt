package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Worker1CoreLogicAuditTest {

    @Before
    fun setup() {
        RewardsBalanceExtractor.clearTelemetryMap()
    }

    @Test
    fun testBug05InvertCombinedSearchPriorityPcFirstRemainderToMobile() {
        // Level 2 user: effPcMax = 90, effMobMax = 60
        // Case: effectiveCombined = 100 points, neither existing PC nor Mobile recorded
        val json = JSONObject("""
            {
                "userStatus": {
                    "level": 2,
                    "counters": {}
                },
                "levelInfoPromotion": {
                    "attributes": {
                        "bing_search_daily_points": "100"
                    }
                }
            }
        """.trimIndent())

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(json)
        // BUG-05: PC gets priority first: minOf(100, 90) = 90. Remainder to mobile: minOf(maxOf(0, 100 - 90), 60) = 10.
        // Old buggy behavior would have allocated Mobile = 60, PC = 40!
        assertEquals("PC phải được ưu tiên trước đạt 90/90", 90, balance.pcProgress)
        assertEquals("Số điểm tìm kiếm còn lại (10) phân bổ cho Mobile", 10, balance.mobileProgress)
        assertEquals(90, balance.pcMax)
        assertEquals(60, balance.mobileMax)
    }

    @Test
    fun testBug08ClearTelemetryMapOnDayRolloverAndReset() {
        val testId = "acc_test_rollover"
        RewardsBalanceExtractor.latestTelemetryMap[testId] = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 500,
            isSuccess = true
        )
        assertNotNull(RewardsBalanceExtractor.latestTelemetryMap[testId])

        // Chạy checkDayRollover với ngày mới
        RewardsBalanceExtractor.checkDayRollover("2026-10-25")
        // Vẫn cùng ngày 2026-10-25 -> telemetry giữ nguyên
        RewardsBalanceExtractor.latestTelemetryMap[testId] = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 500,
            isSuccess = true
        )
        RewardsBalanceExtractor.checkDayRollover("2026-10-25")
        assertNotNull(RewardsBalanceExtractor.latestTelemetryMap[testId])

        // Sang ngày mới 2026-10-26 -> phải tự động clear telemetryMap
        RewardsBalanceExtractor.checkDayRollover("2026-10-26")
        assertTrue("latestTelemetryMap phải bị xóa khi sang ngày mới", RewardsBalanceExtractor.latestTelemetryMap.isEmpty())

        // resetForNewDay cũng phải xóa
        RewardsBalanceExtractor.latestTelemetryMap[testId] = RewardsBalanceExtractor.ExtractedRewardsBalance(availablePoints = 600)
        RewardsBalanceExtractor.resetForNewDay("2026-10-27")
        assertTrue(RewardsBalanceExtractor.latestTelemetryMap.isEmpty())
    }

    @Test
    fun testBug13PromoCardWithoutPointsHasZeroPointsFallback() {
        val json = JSONObject("""
            {
                "userStatus": { "level": 1 },
                "morePromotions": [
                    {
                        "id": "promo_no_points",
                        "title": "Welcome to Bing",
                        "destinationUrl": "https://bing.com"
                    },
                    {
                        "id": "promo_with_points",
                        "title": "Explore News",
                        "destinationUrl": "https://bing.com/news",
                        "points": 30
                    }
                ]
            }
        """.trimIndent())

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(json)
        // BUG-13: promo_no_points không có pointProgressMax hay points -> pMax = 0 -> bị skip
        // promo_with_points có points = 30 -> được thêm
        val promoNoPoints = balance.dailyTasks.firstOrNull { it.id == "promo_no_points" }
        assertNull("Promo card không có points không được gán 10đ ảo mà phải bị bỏ qua", promoNoPoints)
        val promoWithPoints = balance.dailyTasks.firstOrNull { it.id == "promo_with_points" }
        assertNotNull("Promo card có points phải được nhận diện", promoWithPoints)
        assertEquals(30, promoWithPoints?.pointValue)
    }

    @Test
    fun testBug06ApplyBalanceOverwritesMobileProgressDirectlyWhenExactCounters() {
        val todayStr = "2026-10-26"
        val profile = AccountProfile.createDefault("test_user", "test@msn.com").apply {
            level = 2
            mobileMax = 60
            mobileProgress = 40 // Điểm mobile trước đó có thể do ước lượng cao
            lastActiveDate = todayStr
        }

        // Exact breakdown từ server xác nhận mobile thực tế chỉ có 20 điểm
        val exactBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 1500,
            mobileProgress = 20,
            mobileMax = 60,
            source = "flyout_http+breakdown",
            hasExactCounters = true,
            isSuccess = true
        )

        profile.applyBalance(exactBalance, todayStr = todayStr)
        // BUG-06: Nguồn breakdown/hasExactCounters phải ghi đè trực tiếp, không bị kẹt maxOf(40, 20) = 40!
        assertEquals("mobileProgress phải được cập nhật chính xác về 20 từ breakdown", 20, profile.mobileProgress)
    }

    @Test
    fun testBug07FlyoutHttpDoesNotResetEdgeMaxAndEdgeProgress() {
        val todayStr = "2026-10-26"
        val profile = AccountProfile.createDefault("test_user", "test@msn.com").apply {
            edgeMax = 12
            edgeProgress = 6
            lastActiveDate = todayStr
        }

        // flyout_http không trả về Edge counter (edgeMax = 0, edgeProgress = 0)
        val flyoutBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 1500,
            edgeMax = 0,
            edgeProgress = 0,
            source = "flyout_http",
            isSuccess = true
        )

        profile.applyBalance(flyoutBalance, todayStr = todayStr)
        // BUG-07: edgeMax không bị reset về 0
        assertEquals("edgeMax không được bị flyout_http reset về 0", 12, profile.edgeMax)
        assertEquals("edgeProgress không được bị flyout_http reset về 0", 6, profile.edgeProgress)
    }

    @Test
    fun testBug08AndSpeed004IsAllMaxedOutNoSideEffectsAndApplyBalanceSetsLastActiveDate() {
        val todayStr = "2026-10-26"
        val profile = AccountProfile.createDefault("test_user", "test@msn.com").apply {
            lastActiveDate = "2026-10-25" // Hôm qua
            dailySetDone = true
            dailySetProgress = 3
        }

        // isAllMaxedOutToday là query method: khi kiểm tra ngày khác không được xóa dailySetDone/dailySetProgress của profile!
        val maxed = profile.isAllMaxedOutToday(todayStr)
        assertFalse(maxed)
        assertTrue("isAllMaxedOutToday không được có side-effect làm biến đổi dailySetDone", profile.dailySetDone)
        assertEquals("isAllMaxedOutToday không được có side-effect làm biến đổi dailySetProgress", 3, profile.dailySetProgress)

        // applyBalance khi isSuccess phải cập nhật lastActiveDate = todayStr
        val balance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 2000,
            isSuccess = true,
            source = "flyout_http"
        )
        profile.applyBalance(balance, todayStr = todayStr)
        assertEquals("applyBalance phải cập nhật lastActiveDate về todayStr khi thành công", todayStr, profile.lastActiveDate)
    }
}
