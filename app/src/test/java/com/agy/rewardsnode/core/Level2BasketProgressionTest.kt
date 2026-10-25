package com.agy.rewardsnode.core

import com.agy.rewardsnode.core.RewardsStateMachine.SearchPhase
import com.agy.rewardsnode.data.model.AccountProfile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit Test Suite kiểm chứng giải pháp Level 2 Basket Progression:
 * 1. Tài khoản Level 2 có pcProgress = 60, pcMax = 90. Khi gặp delta = 0 (cooldown 15m),
 *    xác nhận evaluateNextSearchPhase() vẫn trả về PHASE_1_PC (không bị skip sang Mobile hay DONE).
 * 2. Chỉ khi pcProgress >= 90 (đạt max), evaluateNextSearchPhase() mới chuyển sang PHASE_2_MOBILE.
 * 3. Khi mobileProgress >= 60 (đạt max), evaluateNextSearchPhase() mới chuyển sang PHASE_3_EDGE hoặc DONE.
 */
class Level2BasketProgressionTest {

    private lateinit var stateMachine: RewardsStateMachine

    @Before
    fun setUp() {
        val keywordEngine = KeywordEngine()
        stateMachine = RewardsStateMachine(
            keywordEngine = keywordEngine,
            profileManager = null,
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = { }
        )
    }

    /**
     * Test 1: Tài khoản Level 2 có pcProgress = 60, pcMax = 90. Khi gặp delta = 0 (cooldown 15m),
     * xác nhận evaluateNextSearchPhase() vẫn trả về PHASE_1_PC (không bị skip sang Mobile hay DONE).
     */
    @Test
    fun testLevel2PcCooldownDoesNotSkipToMobileOrDone() {
        val account = AccountProfile(
            id = "test-l2-acc-1",
            email = "level2_user1@example.com",
            profileName = "Level 2 User",
            level = 2,
            pcProgress = 60,
            pcMax = 90,
            mobileProgress = 0,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 12
        )

        // Mô phỏng tài khoản gặp delta = 0 liên tiếp dẫn tới Cooldown 15 phút (Restricted)
        stateMachine.recordSearchDelta(account.id, 0)
        stateMachine.recordSearchDelta(account.id, 0)
        stateMachine.markAccountRestricted(account.id, true)

        assertTrue(
            "Tài khoản phải được ghi nhận đang trong tình trạng cooldown restricted",
            stateMachine.isAccountCooldownRestricted(account.id)
        )

        // Đánh giá phase tìm kiếm tiếp theo
        val nextPhase = stateMachine.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC)

        assertEquals(
            "Tài khoản Level 2 chưa đạt trần PC (60/90) dù gặp delta = 0 (cooldown 15m) vẫn phải ở PHASE_1_PC, tuyệt đối không được skip sang Mobile hay DONE",
            SearchPhase.PHASE_1_PC,
            nextPhase
        )

        // Đối chiếu với latest telemetry balance
        RewardsBalanceExtractor.latestTelemetryMap[account.id] = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            pcProgress = 60,
            pcMax = 90,
            mobileProgress = 0,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 12
        )

        val nextPhaseWithTelemetry = stateMachine.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC)
        assertEquals(
            "Có telemetry balance (60/90) thì evaluateNextSearchPhase() vẫn phải là PHASE_1_PC",
            SearchPhase.PHASE_1_PC,
            nextPhaseWithTelemetry
        )
    }

    /**
     * Test 2: Chỉ khi pcProgress >= 90 (đạt max), evaluateNextSearchPhase() mới chuyển sang PHASE_2_MOBILE.
     */
    @Test
    fun testLevel2AdvancesToMobileOnlyWhenPcMaxReached() {
        val account = AccountProfile(
            id = "test-l2-acc-2",
            email = "level2_user2@example.com",
            profileName = "Level 2 User",
            level = 2,
            pcProgress = 89,
            pcMax = 90,
            mobileProgress = 0,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 12
        )

        // Khi PC còn thiếu (89/90) -> vẫn là PHASE_1_PC
        val phaseBeforeMax = stateMachine.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC)
        assertEquals(
            "Khi pcProgress = 89 < 90, phase tiếp theo vẫn phải là PHASE_1_PC",
            SearchPhase.PHASE_1_PC,
            phaseBeforeMax
        )

        // Khi PC đạt chuẩn 90/90 -> chuyển sang PHASE_2_MOBILE
        account.pcProgress = 90
        val phaseAtMax = stateMachine.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC)
        assertEquals(
            "Khi pcProgress = 90 >= 90, phase tiếp theo phải chuyển sang PHASE_2_MOBILE",
            SearchPhase.PHASE_2_MOBILE,
            phaseAtMax
        )

        // Khi PC vượt nhẹ hoặc đạt max (ví dụ 93/90 do batch cuối 3 điểm) -> vẫn chuyển sang PHASE_2_MOBILE
        account.pcProgress = 93
        val phaseOverMax = stateMachine.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC)
        assertEquals(
            "Khi pcProgress >= 90, phase tiếp theo phải là PHASE_2_MOBILE",
            SearchPhase.PHASE_2_MOBILE,
            phaseOverMax
        )
    }

    /**
     * Test 3: Khi mobileProgress >= 60 (đạt max), evaluateNextSearchPhase() mới chuyển sang PHASE_3_EDGE hoặc DONE.
     */
    @Test
    fun testLevel2AdvancesToEdgeOrDoneOnlyWhenMobileMaxReached() {
        // Trường hợp A: Có rổ Edge Bonus (edgeMax = 12, edgeProgress = 0)
        val accountWithEdge = AccountProfile(
            id = "test-l2-acc-3a",
            email = "level2_user3a@example.com",
            profileName = "Level 2 User with Edge",
            level = 2,
            pcProgress = 90,
            pcMax = 90,
            mobileProgress = 30,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 12
        )

        // Khi mobileProgress = 30 < 60 -> vẫn ở PHASE_2_MOBILE
        val phaseBeforeMobMax = stateMachine.evaluateNextSearchPhase(accountWithEdge, SearchPhase.PHASE_1_PC)
        assertEquals(
            "Khi mobileProgress = 30 < 60, phase tiếp theo vẫn phải là PHASE_2_MOBILE",
            SearchPhase.PHASE_2_MOBILE,
            phaseBeforeMobMax
        )

        // Khi mobileProgress đạt 60/60 -> chuyển sang PHASE_3_EDGE
        accountWithEdge.mobileProgress = 60
        val phaseAtMobMax = stateMachine.evaluateNextSearchPhase(accountWithEdge, SearchPhase.PHASE_1_PC)
        assertEquals(
            "Khi mobileProgress = 60 >= 60 và edgeMax > 0, phase tiếp theo phải chuyển sang PHASE_3_EDGE",
            SearchPhase.PHASE_3_EDGE,
            phaseAtMobMax
        )

        // Trường hợp B: Không có rổ Edge Bonus (edgeMax = 0)
        val accountWithoutEdge = AccountProfile(
            id = "test-l2-acc-3b",
            email = "level2_user3b@example.com",
            profileName = "Level 2 User no Edge",
            level = 2,
            pcProgress = 90,
            pcMax = 90,
            mobileProgress = 60,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 0
        )

        val phaseDoneNoEdge = stateMachine.evaluateNextSearchPhase(accountWithoutEdge, SearchPhase.PHASE_1_PC)
        assertEquals(
            "Khi mobileProgress >= 60 và không có Edge bonus (edgeMax = 0), phase tiếp theo phải là DONE",
            SearchPhase.DONE,
            phaseDoneNoEdge
        )

        // Trường hợp C: Đã hoàn tất cả rổ Edge (edgeProgress = 12 >= 12)
        accountWithEdge.edgeProgress = 12
        val phaseDoneWithEdge = stateMachine.evaluateNextSearchPhase(accountWithEdge, SearchPhase.PHASE_1_PC)
        assertEquals(
            "Khi cả PC (90/90), Mobile (60/60) và Edge (12/12) đều hoàn thành, phase tiếp theo phải là DONE",
            SearchPhase.DONE,
            phaseDoneWithEdge
        )
    }
}
