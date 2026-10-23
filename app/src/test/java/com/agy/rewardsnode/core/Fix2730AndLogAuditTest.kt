package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.model.AccountProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Fix2730AndLogAuditTest {

    private fun buildCompletedDailySetItems(): JSONArray {
        val compactDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return JSONArray().apply {
            for (i in 1..3) {
                put(JSONObject().apply {
                    put("offerId", "Global_DailySet_${compactDate}_$i")
                    put("name", "Global_DailySet_${compactDate}_$i")
                    put("title", "Daily Activity $i")
                    put("complete", true)
                    put("pointProgress", 10)
                    put("pointProgressMax", 10)
                    put("points", 10)
                    put("destinationUrl", "https://www.bing.com/search?q=daily+set+$i")
                })
            }
        }
    }

    private fun buildNewLevelsFlyoutJson(
        todaysPoints: String,
        bingSearchDailyPoints: String = "27",
        pointsPerPcSearchNewLevels: String = "3",
        level: String = "newLevel1",
        searchCapLifted: String = "True",
        partnerBingCompleted: String = "True",
        partnerBingTitleArg0: String = "1",
        partnerBingClaimingPending: String = "False",
        dailySetItems: JSONArray? = null,
        availablePoints: Int = 1250,
        lifetimePoints: Int = 4500
    ): JSONObject {
        val todaySlashKey = SimpleDateFormat("MM/dd/yyyy", Locale.US).format(Date())
        val flyoutResult = JSONObject().apply {
            put("userInfo", JSONObject().apply {
                put("balance", availablePoints)
                put("lifetimePoints", lifetimePoints)
                put("profile", JSONObject().apply {
                    put("attributes", JSONObject().apply {
                        put("level", level)
                    })
                })
            })
            put("userStatus", JSONObject().apply {
                put("availablePoints", availablePoints)
                put("lifetimePoints", lifetimePoints)
                put("counters", JSONObject().apply {
                    put("DailyPoint", JSONArray().apply {
                        put(JSONObject().apply {
                            put("pointProgress", 540)
                            put("pointProgressMax", 1360)
                        })
                    })
                })
            })
            put("levelInfoPromotion", JSONObject().apply {
                put("attributes", JSONObject().apply {
                    put("level", level)
                    put("is_new_levels_feature_with_searchcap_lifted", searchCapLifted)
                    put("bing_search_daily_points", bingSearchDailyPoints)
                    put("points_per_pc_search_new_levels", pointsPerPcSearchNewLevels)
                    put("todays_points", todaysPoints)
                })
            })
            put("dailyCheckInPromotion", JSONObject().apply {
                put("attributes", JSONObject().apply {
                    put("partner_bing_completed", partnerBingCompleted)
                    put("partner_bing_titleArg0", partnerBingTitleArg0)
                    put("partner_bing_titleArg1", "1")
                    put("partner_bing_claimingPending", partnerBingClaimingPending)
                    put("partner_bing_points", "[3,3,3,3,3,3,100]")
                })
            })
            if (dailySetItems != null) {
                put("dailySetPromotions", JSONObject().apply {
                    put(todaySlashKey, dailySetItems)
                })
            }
        }
        return JSONObject().apply {
            put("flyoutResult", flyoutResult)
        }
    }

    @Test
    fun `test parseFlyoutJsonPayload with 2026 New Levels JSON resolves 30 of 30 and isAllMaxedOut`() {
        val payload41 = buildNewLevelsFlyoutJson(
            todaysPoints = "41",
            bingSearchDailyPoints = "27",
            pointsPerPcSearchNewLevels = "3",
            level = "newLevel1",
            searchCapLifted = "True",
            partnerBingCompleted = "True",
            partnerBingTitleArg0 = "1",
            partnerBingClaimingPending = "False"
        )
        val res41 = RewardsBalanceExtractor.parseFlyoutJsonPayload(payload41)
        assertTrue(res41.isSuccess)
        assertEquals(30, res41.pcProgress)
        assertEquals(30, res41.pcMax)
        assertTrue(res41.isAllMaxedOut)

        val payload88 = buildNewLevelsFlyoutJson(
            todaysPoints = "88",
            bingSearchDailyPoints = "27",
            pointsPerPcSearchNewLevels = "3",
            level = "newLevel1",
            searchCapLifted = "True",
            partnerBingCompleted = "True",
            partnerBingTitleArg0 = "1",
            partnerBingClaimingPending = "False",
            dailySetItems = buildCompletedDailySetItems()
        )
        val res88 = RewardsBalanceExtractor.parseFlyoutJsonPayload(payload88)
        assertTrue(res88.isSuccess)
        assertEquals(30, res88.pcProgress)
        assertEquals(30, res88.pcMax)
        assertTrue(res88.isAllMaxedOut)
    }

    @Test
    fun `test start of day safety returns pcProgress 0 even when bing_search_daily_points is 27`() {
        val startOfDayPayload = buildNewLevelsFlyoutJson(
            todaysPoints = "0",
            bingSearchDailyPoints = "27",
            partnerBingCompleted = "False",
            partnerBingTitleArg0 = "0",
            partnerBingClaimingPending = "False"
        )
        val res = RewardsBalanceExtractor.parseFlyoutJsonPayload(startOfDayPayload)
        assertTrue(res.isSuccess)
        assertEquals(0, res.pcProgress)
        assertEquals(30, res.pcMax)
        assertFalse(res.isAllMaxedOut)
    }

    @Test
    fun `test Daily Set completed first without Bing search keeps pcProgress at 0`() {
        val dailySetOnlyPayload = buildNewLevelsFlyoutJson(
            todaysPoints = "30",
            bingSearchDailyPoints = "27",
            partnerBingCompleted = "False",
            partnerBingTitleArg0 = "0",
            partnerBingClaimingPending = "False",
            dailySetItems = buildCompletedDailySetItems()
        )
        val res = RewardsBalanceExtractor.parseFlyoutJsonPayload(dailySetOnlyPayload)
        assertTrue(res.isSuccess)
        assertEquals(0, res.pcProgress)
        assertEquals(30, res.pcMax)
        assertFalse(res.isAllMaxedOut)
    }

    @Test
    fun `test mid day partial search progress with completed Daily Set computes pcProgress 9 of 30`() {
        val midDayPayload = buildNewLevelsFlyoutJson(
            todaysPoints = "39",
            bingSearchDailyPoints = "27",
            partnerBingCompleted = "True",
            partnerBingTitleArg0 = "1",
            partnerBingClaimingPending = "False",
            dailySetItems = buildCompletedDailySetItems()
        )
        val res = RewardsBalanceExtractor.parseFlyoutJsonPayload(midDayPayload)
        assertTrue(res.isSuccess)
        assertEquals(9, res.pcProgress)
        assertEquals(30, res.pcMax)
        assertFalse(res.isAllMaxedOut)
    }

    @Test
    fun `test partner_bing_claimingPending True surfaces claimablePoints at least 3`() {
        val pendingClaimPayload = buildNewLevelsFlyoutJson(
            todaysPoints = "27",
            bingSearchDailyPoints = "27",
            pointsPerPcSearchNewLevels = "3",
            partnerBingCompleted = "True",
            partnerBingTitleArg0 = "1",
            partnerBingClaimingPending = "True"
        )
        val res = RewardsBalanceExtractor.parseFlyoutJsonPayload(pendingClaimPayload)
        assertTrue(res.isSuccess)
        assertTrue("Expected claimablePoints >= 3, got ${res.claimablePoints}", res.claimablePoints >= 3)
    }

    @Test
    fun `test AccountProfile applyBalance monotonic same day guard keeps 30 of 30`() {
        val todayStr = "2026-10-23"
        val profile = AccountProfile(
            id = "acc_mono_guard",
            email = "thienduongvangem0392@gmail.com",
            profileName = "Thien Duong",
            level = 1,
            completedSearchesToday = 12,
            availablePoints = 1500,
            baselinePointsToday = 1450,
            baselineDate = todayStr,
            pcProgress = 30,
            pcMax = 30,
            lastActiveDate = todayStr
        )

        val partial27Balance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 1500,
            lifetimePoints = 5000,
            streakCount = 5,
            pointsEarnedToday = 50,
            isSuccess = true,
            source = "rewards_dashboard_http",
            pcProgress = 27,
            pcMax = 30,
            mobileProgress = 0,
            mobileMax = 0,
            edgeProgress = 0,
            edgeMax = 0,
            level = 1
        )

        profile.applyBalance(partial27Balance, todayStr, 1450)

        assertEquals(30, profile.pcProgress)
        assertEquals(30, profile.pcMax)
    }

    @Test
    fun `test resolveEnabledScheduleSlots respects individual slot ON OFF switches`() {
        val onlySlot1 = EncryptedPreferences.resolveEnabledScheduleSlots(
            slot1 = "05:00",
            en1 = true,
            slot2 = "12:30",
            en2 = false,
            slot3 = "20:00",
            en3 = false
        )
        assertEquals(listOf(Pair(5, 0)), onlySlot1)

        val slots2And3Sorted = EncryptedPreferences.resolveEnabledScheduleSlots(
            slot1 = "05:00",
            en1 = false,
            slot2 = "20:00",
            en2 = true,
            slot3 = "12:30",
            en3 = true
        )
        assertEquals(listOf(Pair(12, 30), Pair(20, 0)), slots2And3Sorted)

        val allOff = EncryptedPreferences.resolveEnabledScheduleSlots(
            slot1 = "05:00",
            en1 = false,
            slot2 = "12:30",
            en2 = false,
            slot3 = "20:00",
            en3 = false
        )
        assertTrue(allOff.isEmpty())
    }

    @Test
    fun `test AccountProfile applyBalance self heals fallback account email and profileName`() {
        val todayStr = "2026-10-23"
        val profile = AccountProfile(
            id = "499daae4-a321-4211-988e-aefcf9514e97",
            email = "account_499daa@outlook.com",
            profileName = "Profile 499daa",
            level = 1,
            availablePoints = 1500,
            lastActiveDate = todayStr
        )

        val resolvedBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 1527,
            lifetimePoints = 5000,
            streakCount = 5,
            pointsEarnedToday = 27,
            isSuccess = true,
            source = "flyout_http+dashboard",
            email = "thienduongvangem0392@gmail.com",
            profileName = "Thien Duong",
            pcProgress = 30,
            pcMax = 30,
            level = 1
        )

        profile.applyBalance(resolvedBalance, todayStr, 1500)

        assertEquals("thienduongvangem0392@gmail.com", profile.email)
        assertEquals("Thien Duong", profile.profileName)
        assertEquals(30, profile.pcProgress)
    }
}
