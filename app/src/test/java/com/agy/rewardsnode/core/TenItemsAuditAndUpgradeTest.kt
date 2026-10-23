package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.service.RewardsForegroundService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TenItemsAuditAndUpgradeTest {

    @Test
    fun `test mergeCookieSnapshots preserves login live com and account microsoft com cookies`() {
        val existing = JSONObject().apply {
            put("https://login.live.com", "__Host-MSAAUTH=token_msa_123; MSPRequ=id=1; MSPOK=ok_1")
            put("https://account.microsoft.com", "AMCSecAuth=amc_sec_456")
            put("https://www.bing.com", "_U=old_u_cookie; MUID=muid_1")
        }.toString()

        // Giả lập snapshot mới lấy khi WebView chỉ vừa chạy search trên www.bing.com (thiếu login.live.com)
        val freshFromBingSearch = JSONObject().apply {
            put("https://www.bing.com", "_U=new_u_cookie_updated; SRCHHPGUSR=SRCHLANG=vi")
            put("https://login.live.com", "MSPRequ=id=2") // bị mất __Host-MSAAUTH trong CookieManager
        }.toString()

        val mergedStr = ProfileManager.mergeCookieSnapshots(existing, freshFromBingSearch)
        val mergedJson = JSONObject(mergedStr)

        // 1. Phải giữ nguyên AMCSecAuth của account.microsoft.com
        assertTrue(mergedJson.getString("https://account.microsoft.com").contains("AMCSecAuth=amc_sec_456"))
        // 2. Phải bảo toàn __Host-MSAAUTH của login.live.com đồng thời cập nhật MSPRequ=id=2
        val liveCookies = mergedJson.getString("https://login.live.com")
        assertTrue(liveCookies.contains("__Host-MSAAUTH=token_msa_123"))
        assertTrue(liveCookies.contains("MSPRequ=id=2"))
        // 3. Phải cập nhật _U mới và giữ MUID cũ trên www.bing.com
        val bingCookies = mergedJson.getString("https://www.bing.com")
        assertTrue(bingCookies.contains("_U=new_u_cookie_updated"))
        assertTrue(bingCookies.contains("MUID=muid_1"))
    }

    @Test
    fun `test parseTimeSlot and computeNextScheduleTriggerMillis with 1 to 3 daily slots`() {
        assertEquals(Pair(5, 0), EncryptedPreferences.parseTimeSlot("05:00"))
        assertEquals(Pair(12, 30), EncryptedPreferences.parseTimeSlot("12:30"))
        assertEquals(Pair(20, 15), EncryptedPreferences.parseTimeSlot("20:15"))
        assertNull(EncryptedPreferences.parseTimeSlot(""))
        assertNull(EncryptedPreferences.parseTimeSlot("25:99"))

        val slots = listOf(Pair(5, 0), Pair(12, 30), Pair(20, 0))

        // Giả lập thời điểm hiện tại là 10:00 sáng -> Khung giờ tiếp theo phải là 12:30 cùng ngày
        val calMorning = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nextFromMorning = RewardsForegroundService.computeNextScheduleTriggerMillis(slots, calMorning.timeInMillis)
        assertNotNull(nextFromMorning)
        val resMorning = Calendar.getInstance().apply { timeInMillis = nextFromMorning!!.first }
        assertEquals(12, resMorning.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, resMorning.get(Calendar.MINUTE))
        assertEquals(calMorning.get(Calendar.DAY_OF_YEAR), resMorning.get(Calendar.DAY_OF_YEAR))

        // Giả lập thời điểm hiện tại là 21:00 tối (đã qua cả 3 khung giờ hôm nay) -> Khung giờ tiếp theo phải là 05:00 sáng hôm sau
        val calNight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nextFromNight = RewardsForegroundService.computeNextScheduleTriggerMillis(slots, calNight.timeInMillis)
        assertNotNull(nextFromNight)
        val resNight = Calendar.getInstance().apply { timeInMillis = nextFromNight!!.first }
        assertEquals(5, resNight.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resNight.get(Calendar.MINUTE))
        assertTrue(nextFromNight!!.first > calNight.timeInMillis)
    }

    @Test
    fun `test parseDashboardRootJson extracts extra JSON metrics and filters locked or zero point promos`() {
        val sampleDashboardJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 1500)
                put("lifetimePoints", 5000)
                put(" isRewardsUserSuspended", false)
                put("levelInfo", JSONObject().apply {
                    put("activeLevel", "Level1")
                    put("progress", 210)
                    put("progressMax", 500)
                })
            })
            put("streakBonusPromotions", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("attributes", JSONObject().apply {
                        put("bonusPointsEarned", "45")
                    })
                })
            })
            put("redeemGoal", JSONObject().apply {
                put("title", "Thẻ quà tặng 50k")
                put("price", 4650)
            })
            put("morePromotions", org.json.JSONArray().apply {
                // 1. Promo 0 điểm -> Phải bị lọc bỏ khỏi danh sách thực thi
                put(JSONObject().apply {
                    put("name", "ZeroPointPromo")
                    put("title", "Khám phá Bing")
                    put("complete", false)
                    put("pointProgressMax", 0)
                    put("destinationUrl", "https://www.bing.com")
                })
                // 2. Promo khóa Level 2 (/levelbenefitexclusive) -> Phải bị lọc bỏ khi tài khoản ở Level 1
                put(JSONObject().apply {
                    put("name", "Level2Exclusive")
                    put("title", "Ưu đãi độc quyền Level 2")
                    put("complete", false)
                    put("pointProgressMax", 50)
                    put("destinationUrl", "https://rewards.bing.com/levelbenefitexclusive")
                })
                // 3. Promo hợp lệ (+10đ) -> Phải được giữ lại
                put(JSONObject().apply {
                    put("name", "ValidPromo10")
                    put("title", "Mẹo tìm kiếm hay")
                    put("complete", false)
                    put("pointProgressMax", 10)
                    put("promotionType", "urlreward")
                    put("destinationUrl", "https://www.bing.com/search?q=tips")
                })
            })
        }

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(sampleDashboardJson)
        assertTrue(balance.isSuccess)
        assertEquals(1, balance.level)
        assertEquals(210, balance.monthlyLevelProgress)
        assertEquals(500, balance.monthlyLevelMax)
        assertEquals(45, balance.streakBonusPoints)
        assertEquals("Thẻ quà tặng 50k", balance.redeemGoalTitle)
        assertEquals(4650, balance.redeemGoalMax)
        assertFalse(balance.isAccountSuspended)

        // Kiểm tra morePromotions: Chỉ giữ lại duy nhất ValidPromo10 (+10đ), loại bỏ 0đ và Level2Exclusive
        assertEquals(1, balance.dailyTasks.size)
        assertEquals("ValidPromo10", balance.dailyTasks[0].id)
        assertEquals(10, balance.dailyTasks[0].pointValue)
    }

    @Test
    fun `test RewardsStateMachine accepts configurable retry and operational limits`() {
        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            searchDelayMinSec = 12,
            searchDelayMaxSec = 25,
            maxClaimRetries = 4,
            dapiMaxRetries = 3,
            cooldownTriggerBatches = 3,
            frozenStopBatches = 4,
            maxReconciliationPasses = 3,
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = {}
        )
        assertNotNull(sm)
    }
}
