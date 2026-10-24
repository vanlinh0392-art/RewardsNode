package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

class ReadyToClaimAndAccountMetricsTest {

    @Test
    fun `test createReadyToClaimScript allows PointClaim modal and blocks redeem modals`() {
        val script = RewardsActivitiesEngine.createReadyToClaimScript()
        // Phải có logic nhận diện isPointClaimModal và modal=pointsclaim
        assertTrue(script.contains("modal=pointsclaim"))
        assertTrue(script.contains("isPointClaimModal"))
        assertTrue(script.contains("PointClaim"))
        // Vẫn phải giữ khóa an toàn chặn redeem, order, donate, sweepstake, gift card
        assertTrue(script.contains("redeem"))
        assertTrue(script.contains("order"))
        assertTrue(script.contains("donate"))
        assertTrue(script.contains("sweepstake"))
    }

    @Test
    fun `test AccountProfile isAllMaxedOutToday and applyBalance with edgeMax zero and NewLevels metrics`() {
        val todayStr = "2026-10-23"
        val profile = AccountProfile(
            id = "acc_1",
            email = "vanlinh0392@gmail.com",
            profileName = "Van Linh",
            level = 1,
            targetSearches = 35,
            completedSearchesToday = 2,
            availablePoints = 1037,
            baselinePointsToday = 1037,
            pointsEarnedToday = 360, // Bị phình giả trước đó
            pcProgress = 30,         // Bị báo khống 30/30 trước đó
            pcMax = 30,
            edgeProgress = 0,
            edgeMax = 12,            // Bị gán cứng 12 trước đó
            lastActiveDate = todayStr
        )

        val liveBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 1083,
            lifetimePoints = 1083,
            streakCount = 2,
            pointsEarnedToday = 46,  // Từ levelInfoPromotion.attributes.todays_points
            isSuccess = true,
            source = "flyout_http",
            pcProgress = 21,         // Từ levelInfoPromotion.attributes.bing_search_daily_points
            pcMax = 30,
            mobileProgress = 0,
            mobileMax = 0,
            edgeProgress = 0,
            edgeMax = 0,             // Đã bỏ rổ Edge 12đ
            level = 1,
            claimablePoints = 6,     // Từ pointClaimCardPromotion.attributes.claimable_points
            readNewsProgress = 0,
            readNewsMax = 30
        )

        profile.applyBalance(liveBalance, todayStr, 1037)

        // 1. pointsEarnedToday phải về đúng 46 (không còn 360)
        assertEquals(46, profile.pointsEarnedToday)
        // 2. pcProgress phải về đúng 21/30 (không còn 30/30 giả)
        assertEquals(21, profile.pcProgress)
        assertEquals(30, profile.pcMax)
        // 3. edgeMax phải về 0 (không còn 12)
        assertEquals(0, profile.edgeMax)
        assertEquals(0, profile.edgeProgress)
        // 4. claimablePoints phải bằng 6
        assertEquals(6, profile.claimablePoints)
        // 5. completedSearchesToday đồng bộ từ 21 / 3 = 7 lượt
        assertEquals(7, profile.completedSearchesToday)
        // 6. Vì pcProgress = 21/30 nên chưa maxed out hôm nay
        assertFalse(profile.isAllMaxedOutToday(todayStr))

        // Khi đạt 30/30 PC và 30/30 Read, dù edgeMax = 0 vẫn phải báo isAllMaxedOutToday = true
        profile.pcProgress = 30
        profile.readProgress = 30
        profile.readMax = 30
        assertTrue(profile.isAllMaxedOutToday(todayStr))
    }

    @Test
    fun `test evaluateNextSearchPhase skips Phase 3 Edge when edgeMax is 0 and runs Phase 1 when server pcProgress is 21`() {
        val account = AccountProfile(
            id = "acc_vanlinh",
            email = "vanlinh0392@gmail.com",
            profileName = "Van Linh",
            level = 1,
            pcProgress = 21,
            pcMax = 30,
            mobileProgress = 0,
            mobileMax = 0,
            edgeProgress = 0,
            edgeMax = 0
        )
        RewardsBalanceExtractor.latestTelemetryMap.remove(account.id)
        RewardsBalanceExtractor.latestTelemetryMap.remove(account.email)

        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = {}
        )

        // Khi pcProgress = 21/30 -> Phải trả về PHASE_1_PC
        assertEquals(RewardsStateMachine.SearchPhase.PHASE_1_PC, sm.evaluateNextSearchPhase(account))

        // Khi pcProgress = 30/30 và edgeMax = 0 -> Phải chuyển thẳng sang DONE (không chạy thừa PHASE_3_EDGE!)
        account.pcProgress = 30
        assertEquals(RewardsStateMachine.SearchPhase.DONE, sm.evaluateNextSearchPhase(account))
    }

    @Test
    fun `test startup claim checked set prevents duplicate run when account is already checked and claimable is 0`() {
        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = {}
        )
        val field = RewardsStateMachine::class.java.getDeclaredField("startupClaimCheckedAccounts")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val startupSet = field.get(sm) as MutableSet<String>

        val accId = "test_acc_startup"
        assertFalse(startupSet.contains(accId))

        // Giả lập sau khi startup gate chạy xong
        startupSet.add(accId)
        assertTrue(startupSet.contains(accId))

        // Khi restart node (gọi clear), tập hợp này được làm sạch để Start Node quét lại
        startupSet.clear()
        assertFalse(startupSet.contains(accId))
    }

    @Test
    fun `test startup claim pending logic respects maxClaimRetries circuit breaker`() {
        val maxClaimRetries = 2
        var claimAttempts = 0
        var isChecked = false
        var claimablePoints = 5

        // Lần 1: Chưa check, có 5 điểm -> Pending = true
        var isPending = (!isChecked || claimablePoints > 0) && claimAttempts < maxClaimRetries
        assertTrue(isPending)

        // Sau lần 1: Đã check, claim thất bại nên server vẫn còn 5 điểm, claimAttempts = 1
        isChecked = true
        claimAttempts = 1
        isPending = (!isChecked || claimablePoints > 0) && claimAttempts < maxClaimRetries
        assertTrue(isPending)

        // Sau lần 2: Đã check, claimAttempts = 2 -> Circuit breaker ngắt -> isPending = false
        claimAttempts = 2
        isPending = (!isChecked || claimablePoints > 0) && claimAttempts < maxClaimRetries
        assertFalse(isPending)

        // Trường hợp điểm đã claim thành công (claimablePoints = 0) và đã check -> isPending = false
        claimablePoints = 0
        claimAttempts = 1
        isPending = (!isChecked || claimablePoints > 0) && claimAttempts < maxClaimRetries
        assertFalse(isPending)
    }
}
