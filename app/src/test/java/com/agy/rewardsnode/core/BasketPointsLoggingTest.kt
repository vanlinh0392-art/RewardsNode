package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.data.model.VlogEntry
import com.agy.rewardsnode.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BasketPointsLoggingTest {

    /**
     * Test 1: AccountProfile monotonic preservation khi flyout trả về mobileProgress=0 sau khi đã có mobileProgress=60.
     * Đảm bảo tính đơn điệu: trong cùng ngày (wasSameDay == true), điểm các rổ không bị sụt giảm về 0 bởi response flyout lỗi/thiếu rổ.
     */
    @Test
    fun testAccountProfileMonotonicPreservationWhenFlyoutReturnsZeroMobileProgress() {
        val today = "2026-10-25"
        val account = AccountProfile(
            id = "acc_001",
            email = "user@example.com",
            profileName = "User 1",
            level = 2,
            pcMax = 90,
            pcProgress = 90,
            mobileMax = 60,
            mobileProgress = 60,
            edgeMax = 0,
            edgeProgress = 0,
            lastActiveDate = today,
            availablePoints = 5000,
            totalPoints = 5000
        )

        // Mô phỏng response flyout bị khuyết rổ mobile (mobileProgress = 0)
        val flyoutBalanceWithZeroMobile = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            source = "flyout_http",
            availablePoints = 5003,
            pcProgress = 90,
            pcMax = 90,
            mobileProgress = 0,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 0,
            claimablePoints = 0
        )

        account.applyBalance(flyoutBalanceWithZeroMobile, todayStr = today)

        // Kiểm tra monotonic preservation: mobileProgress vẫn phải giữ 60, không bị hạ về 0
        assertEquals("Mobile progress phải duy trì 60 trong cùng ngày", 60, account.mobileProgress)
        assertEquals("PC progress phải duy trì 90", 90, account.pcProgress)
        assertEquals(5003, account.availablePoints)

        // Mô phỏng trường hợp trước đó có 40 điểm, flyout mới hợp lệ tăng lên 50 điểm
        account.mobileProgress = 40
        val progressiveBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            source = "flyout_http",
            availablePoints = 5013,
            pcProgress = 90,
            pcMax = 90,
            mobileProgress = 50,
            mobileMax = 60
        )
        account.applyBalance(progressiveBalance, todayStr = today)
        assertEquals("Mobile progress có thể tăng lên 50", 50, account.mobileProgress)

        // Response flyout tiếp theo lại bị khuyết (mobileProgress = 0) -> phải giữ 50
        account.applyBalance(flyoutBalanceWithZeroMobile, todayStr = today)
        assertEquals("Mobile progress không được tụt từ 50 về 0", 50, account.mobileProgress)
    }

    /**
     * Test 2: RewardsBalanceExtractor.parseFlyoutJsonPayload không bị ép pcProgress=0 khi partnerBingCompleted=false.
     * Đảm bảo không có logic phân biệt đối xử ép pcProgress = 0 nếu nhiệm vụ Check-In chưa click.
     */
    @Test
    fun testParseFlyoutJsonPayloadDoesNotForcePcProgressToZeroWhenPartnerBingCompletedFalse() {
        val flyoutJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 3500)
                put("counters", JSONObject().apply {
                    put("dailyPoint", org.json.JSONArray().apply {
                        // Không có pointProgressMax để ép extractor fallback sang bing_search_daily_points
                        put(JSONObject().apply {
                            put("pointProgress", 0)
                        })
                    })
                })
            })
            // levelInfoPromotion chứa bing_search_daily_points = 12
            put("levelInfoPromotion", JSONObject().apply {
                put("attributes", JSONObject().apply {
                    put("level", "1")
                    put("bing_search_daily_points", "12")
                    put("todays_points", "12")
                    put("points_per_pc_search", "3")
                })
            })
            // dailyCheckInPromotion có partner_bing_completed = "False"
            put("dailyCheckInPromotion", JSONObject().apply {
                put("attributes", JSONObject().apply {
                    put("partner_bing_completed", "False")
                    put("partner_bing_titleArg0", "0")
                })
            })
            put("dailySetPromotions", JSONObject())
        }

        val parsed = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson)

        assertTrue("Payload phải phân tích thành công", parsed.isSuccess)
        assertEquals("Level phải là 1", 1, parsed.level)
        assertEquals("pcProgress phải là 12, không bị ép về 0 dù partner_bing_completed = false", 12, parsed.pcProgress)
        assertEquals("Level 1 pcMax phải là 30", 30, parsed.pcMax)
        assertEquals("Level 1 mobileProgress phải là 0", 0, parsed.mobileProgress)
    }

    /**
     * Test 3: MainActivity classifyPointVlogEntry bóc tách trọn vẹn chuỗi log rổ điểm không bị cắt cụt.
     * Trước đây: msg.substringAfter(":", msg) làm cắt cụt sau chữ 'PC:'
     * Bây giờ: Tiêu đề bóc tách trọn vẹn toàn bộ chuỗi breakdown mà không bị cắt xén.
     */
    @Test
    fun testMainActivityClassifyPointVlogEntryPreservesFullBasketString() {
        val messageWithTag = "[POINT] +9đ (PC:+9, Mob:+0, Edge:+0, Bonus:+0) | Kho: 18/90 PC, 0/60 Mob, 0/0 Edge"
        val entry1 = VlogEntry(
            timeFormatted = "08:15:30.123",
            tag = "POINT",
            level = "INFO",
            message = messageWithTag,
            accountEmail = "alpha@example.com"
        )

        val record1 = MainActivity.classifyPointVlogEntry(entry1, listOf("alpha@example.com"))
        assertNotNull("Record không được null", record1)
        assertEquals("🔍 Search", record1!!.category)
        assertEquals(9, record1.pointsDelta)
        assertEquals("alpha@example.com", record1.accountEmail)
        assertEquals(
            "Tiêu đề phải giữ nguyên chuỗi rổ điểm đầy đủ, không bị cắt cụt sau 'PC:'",
            "+9đ (PC:+9, Mob:+0, Edge:+0, Bonus:+0) | Kho: 18/90 PC, 0/60 Mob, 0/0 Edge",
            record1.title
        )

        // Kiểm tra message không có tiền tố [POINT] nhưng tag là POINT
        val cleanMessage = "+15đ (PC:+9, Mob:+6, Edge:+0, Bonus:+0) | Kho: 27/90 PC, 6/60 Mob, 0/0 Edge"
        val entry2 = VlogEntry(
            timeFormatted = "08:30:00.456",
            tag = "POINT",
            level = "INFO",
            message = cleanMessage,
            accountEmail = "beta@example.com"
        )

        val record2 = MainActivity.classifyPointVlogEntry(entry2, listOf("beta@example.com"))
        assertNotNull("Record không được null", record2)
        assertEquals(15, record2!!.pointsDelta)
        assertEquals(
            cleanMessage,
            record2.title
        )
    }
}
