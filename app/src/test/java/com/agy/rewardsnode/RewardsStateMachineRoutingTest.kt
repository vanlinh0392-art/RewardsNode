package com.agy.rewardsnode

import com.agy.rewardsnode.core.KeywordEngine
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.core.RewardsStateMachine
import com.agy.rewardsnode.core.RewardsStateMachine.SearchPhase
import com.agy.rewardsnode.data.model.AccountProfile
import org.junit.Assert.*
import org.junit.Test

class RewardsStateMachineRoutingTest {

    @Test
    fun testLevel1SkipsMobileSearchDueToLevelGating() {
        val account = AccountProfile(
            id = "test-acc-1",
            email = "level1@example.com",
            profileName = "Level 1 User",
            level = 1,
            pcProgress = 30,
            pcMax = 30,
            mobileProgress = 0,
            mobileMax = 0,
            edgeProgress = 0,
            edgeMax = 12
        )

        val keywordEngine = KeywordEngine()
        val sm = RewardsStateMachine(
            keywordEngine = keywordEngine,
            profileManager = null,
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = { }
        )

        // PC is maxed (30/30), Level 1 (< 2) skips Phase 2 Mobile and advances to Phase 3 Edge
        val nextPhase = sm.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC)
        assertEquals("Level 1 with maxed PC must skip Phase 2 (Mobile) due to Level Gating (<2) and advance to Phase 3 (Edge)", SearchPhase.PHASE_3_EDGE, nextPhase)
    }

    @Test
    fun testSequentialRoutingPhases() {
        val account = AccountProfile(
            id = "test-acc-2",
            email = "level2@example.com",
            profileName = "Level 2 User",
            level = 2,
            pcProgress = 0,
            pcMax = 90,
            mobileProgress = 0,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 12
        )

        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            profileManager = null,
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = { }
        )

        // Step 1: Nothing done -> Phase 1 PC (Desktop)
        assertEquals(SearchPhase.PHASE_1_PC, sm.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC))
        assertTrue(sm.selectRoutingMode(account))

        // Step 2: PC done (90/90) -> Phase 2 Mobile (Mobile Edge)
        account.pcProgress = 90
        assertEquals(SearchPhase.PHASE_2_MOBILE, sm.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC))
        assertFalse(sm.selectRoutingMode(account)) // isDesktop == false

        // Step 3: Mobile done (60/60) -> Phase 3 Edge (Desktop Edge)
        account.mobileProgress = 60
        assertEquals(SearchPhase.PHASE_3_EDGE, sm.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC))
        assertTrue(sm.selectRoutingMode(account)) // isDesktop == true

        // Step 4: Edge done (12/12) -> DONE
        account.edgeProgress = 12
        assertEquals(SearchPhase.DONE, sm.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC))
    }

    @Test
    fun testAccountProfileIsAllMaxedOutToday() {
        val todayStr = "2026-10-21"
        val account = AccountProfile(
            id = "test-acc-3",
            email = "test@example.com",
            profileName = "Test",
            level = 1,
            lastActiveDate = todayStr,
            pcProgress = 30,
            pcMax = 30,
            mobileProgress = 0,
            mobileMax = 0,
            edgeProgress = 0,
            edgeMax = 12,
            targetSearches = 35,
            completedSearchesToday = 10
        )

        // Account has only done 10 PC searches, edge and mobile not done -> MUST NOT be maxed out!
        assertFalse("Account with only PC done must NOT be maxed out today", account.isAllMaxedOutToday(todayStr))

        // When all baskets truly done
        account.mobileProgress = 60
        account.edgeProgress = 12
        assertTrue("Account with all 3 baskets done must be maxed out today", account.isAllMaxedOutToday(todayStr))
    }

    @Test
    fun testRewardsBalanceExtractorDoesNotFalselyMaxOut() {
        val balance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 1500,
            pcProgress = 30,
            pcMax = 30,
            mobileProgress = 0,
            mobileMax = 0,
            edgeProgress = 0,
            edgeMax = 12,
            level = 1,
            isAllMaxedOut = false
        )

        assertFalse("Balance extractor MUST NOT set isAllMaxedOut=true when mobile/edge are 0", balance.isAllMaxedOut)
    }

    @Test
    fun testLevel1AdvancesToEdgeWhenMobileDone() {
        val account = AccountProfile(
            id = "test-acc-level1-edge",
            email = "level1_edge@example.com",
            profileName = "Level 1 Edge",
            level = 1,
            pcProgress = 30,
            pcMax = 30,
            mobileProgress = 60,
            mobileMax = 60,
            edgeProgress = 0,
            edgeMax = 12
        )

        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            profileManager = null,
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = { }
        )

        // PC and Mobile done -> Advances to Phase 3 (Edge)
        val nextPhase = sm.evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC)
        assertEquals("Level 1 with maxed PC and Mobile must advance to Phase 3 (Edge)", SearchPhase.PHASE_3_EDGE, nextPhase)
    }

    @Test
    fun testIsBingApp60Of60Done_allCombinations() {
        val todayStr = "2026-10-24"
        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = { }
        )

        // Case 1: dailySetDone = true and readProgress >= 30 -> TRUE
        val acc1 = AccountProfile(
            id = "acc1",
            email = "acc1@example.com",
            profileName = "Acc 1",
            dailySetDone = true,
            readProgress = 30
        )
        assertTrue(sm.isBingApp60Of60Done(acc1, todayStr))

        // Case 2: dailySetDone = false and readProgress < 30 -> FALSE
        val acc2 = AccountProfile(
            id = "acc2",
            email = "acc2@example.com",
            profileName = "Acc 2",
            dailySetDone = false,
            readProgress = 15
        )
        assertFalse(sm.isBingApp60Of60Done(acc2, todayStr))

        // Case 3: dailySetDone = true but readProgress < 30 -> FALSE
        val acc3 = AccountProfile(
            id = "acc3",
            email = "acc3@example.com",
            profileName = "Acc 3",
            dailySetDone = true,
            readProgress = 27
        )
        assertFalse(sm.isBingApp60Of60Done(acc3, todayStr))

        // Case 4: dailySetDone = false but readProgress >= 30 -> FALSE
        val acc4 = AccountProfile(
            id = "acc4",
            email = "acc4@example.com",
            profileName = "Acc 4",
            dailySetDone = false,
            readProgress = 30
        )
        assertFalse(sm.isBingApp60Of60Done(acc4, todayStr))

        // Case 5: readProgress > 30 (vượt trần) and dailySetDone = true -> TRUE
        val acc5 = AccountProfile(
            id = "acc5",
            email = "acc5@example.com",
            profileName = "Acc 5",
            dailySetDone = true,
            readProgress = 33
        )
        assertTrue(sm.isBingApp60Of60Done(acc5, todayStr))
    }
}
