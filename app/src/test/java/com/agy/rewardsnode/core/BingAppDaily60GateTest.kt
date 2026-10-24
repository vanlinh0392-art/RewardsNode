package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit Test Suite kiểm thử logic Gate rổ 60/60 điểm Bing App:
 * 1. Daily Set 30đ (thẻ web/app hàng ngày)
 * 2. MSN Read to Earn 30đ (10 bài báo x 3đ qua DAPI)
 *
 * Kiểm tra đầy đủ 4 trạng thái Gate ma trận nhị phân (0/0, 30/0, 0/30, 30/30),
 * cùng cơ chế tính tiến độ, trần điểm, và điều kiện dừng đọc báo của DapiActivitiesEngine.
 */
class BingAppDaily60GateTest {

    private lateinit var stateMachine: RewardsStateMachine
    private val todayStr = "2026-10-24"
    private val yesterdayStr = "2026-10-23"

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

    // =========================================================================
    // 1. Kiểm tra logic Gate rổ 60/60 (Daily Set 30đ + MSN Read to Earn 30đ)
    // =========================================================================

    @Test
    fun testGate60Of60_neitherCompleted_returnsFalse() {
        // Trạng thái: Chưa hoàn thành thẻ nào (Daily Set 0đ, Read to Earn 0đ)
        val account = AccountProfile(
            id = "acc_daily60_1",
            email = "user1@example.com",
            profileName = "User 1",
            dailySetDone = false,
            dailySetProgress = 0,
            dailySetTotal = 3,
            readProgress = 0,
            readMax = 30
        )

        val isDone = stateMachine.isBingApp60Of60Done(account, todayStr)
        assertFalse("Chưa hoàn thành thẻ nào (0/60) -> Gate phải trả về false", isDone)
    }

    @Test
    fun testGate60Of60_onlyDailySetCompleted_returnsFalse() {
        // Trạng thái: Chỉ hoàn thành Daily Set 30đ, chưa đọc báo (30/0)
        val account = AccountProfile(
            id = "acc_daily60_2",
            email = "user2@example.com",
            profileName = "User 2",
            dailySetDone = true,
            dailySetProgress = 3,
            dailySetTotal = 3,
            readProgress = 0,
            readMax = 30
        )

        val isDone = stateMachine.isBingApp60Of60Done(account, todayStr)
        assertFalse("Chỉ hoàn thành Daily Set (30/0) -> Chưa đủ 60đ, Gate phải trả về false", isDone)
    }

    @Test
    fun testGate60Of60_onlyReadToEarnCompleted_returnsFalse() {
        // Trạng thái: Chỉ hoàn thành Read to Earn 30đ, chưa làm Daily Set (0/30)
        val account = AccountProfile(
            id = "acc_daily60_3",
            email = "user3@example.com",
            profileName = "User 3",
            dailySetDone = false,
            dailySetProgress = 0,
            dailySetTotal = 3,
            readProgress = 30,
            readMax = 30
        )

        val isDone = stateMachine.isBingApp60Of60Done(account, todayStr)
        assertFalse("Chỉ hoàn thành Read to Earn (0/30) -> Chưa đủ 60đ, Gate phải trả về false", isDone)
    }

    @Test
    fun testGate60Of60_bothCompleted_returnsTrue() {
        // Trạng thái: Hoàn thành cả 2 rổ điểm (Daily Set 30đ + Read to Earn 30đ = 60/60đ)
        val account = AccountProfile(
            id = "acc_daily60_4",
            email = "user4@example.com",
            profileName = "User 4",
            dailySetDone = true,
            dailySetProgress = 3,
            dailySetTotal = 3,
            readProgress = 30,
            readMax = 30
        )

        val isDone = stateMachine.isBingApp60Of60Done(account, todayStr)
        assertTrue("Hoàn thành cả Daily Set (30đ) và Read to Earn (30đ) -> 60/60đ, Gate phải trả về true", isDone)
    }

    @Test
    fun testGate60Of60_partialReadProgress_returnsFalse() {
        // Trạng thái: Daily Set đã xong nhưng Read to Earn mới được 9 bài = 27đ
        val account = AccountProfile(
            id = "acc_daily60_partial",
            email = "user_partial@example.com",
            profileName = "User Partial",
            dailySetDone = true,
            readProgress = 27,
            readMax = 30
        )

        assertFalse("Read to Earn mới đạt 27/30đ -> Chưa đủ 60đ, Gate phải trả về false", stateMachine.isBingApp60Of60Done(account, todayStr))
    }

    @Test
    fun testGate60Of60_readProgressExceedingThirty_returnsTrue() {
        // Trạng thái: Read to Earn vượt 30đ (ví dụ 33đ do bonus server) và Daily Set đã xong
        val account = AccountProfile(
            id = "acc_daily60_overflow",
            email = "user_overflow@example.com",
            profileName = "User Overflow",
            dailySetDone = true,
            readProgress = 33,
            readMax = 30
        )

        assertTrue("Read to Earn >= 30 và Daily Set xong -> Gate phải trả về true", stateMachine.isBingApp60Of60Done(account, todayStr))
    }

    // =========================================================================
    // 2. Kiểm tra Gate kết hợp Date Tracking trong StateMachine (phase0/phase05)
    // =========================================================================

    @Test
    fun testGate60Of60_viaCompletedDateMaps_reflection() {
        val account = AccountProfile(
            id = "acc_daily60_date_tracking",
            email = "datetrack@example.com",
            profileName = "Date Track User",
            dailySetDone = false, // profile flag chưa bật
            readProgress = 0       // profile progress chưa cập nhật
        )

        val phase0Field = RewardsStateMachine::class.java.getDeclaredField("phase0CompletedDateMap").apply { isAccessible = true }
        val phase05Field = RewardsStateMachine::class.java.getDeclaredField("phase05CompletedDateMap").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val phase0Map = phase0Field.get(stateMachine) as ConcurrentHashMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val phase05Map = phase05Field.get(stateMachine) as ConcurrentHashMap<String, String>

        // Case A: Chưa có map nào ghi nhận hôm nay -> false
        assertFalse(stateMachine.isBingApp60Of60Done(account, todayStr))

        // Case B: Chỉ Phase 0 xong hôm nay (Daily Set) -> false
        phase0Map[account.id] = todayStr
        assertFalse(stateMachine.isBingApp60Of60Done(account, todayStr))

        // Case C: Cả Phase 0 và Phase 0.5 đều ghi nhận hôm nay -> true
        phase05Map[account.id] = todayStr
        assertTrue("Cả phase0 và phase05 hoàn thành trong ngày -> Gate phải trả về true", stateMachine.isBingApp60Of60Done(account, todayStr))

        // Case D: Hoàn thành hôm qua, hôm nay ngày mới -> false (Reset gate)
        phase0Map[account.id] = yesterdayStr
        phase05Map[account.id] = yesterdayStr
        assertFalse("Dữ liệu ngày hôm qua không hợp lệ cho ngày hôm nay -> Gate phải trả về false", stateMachine.isBingApp60Of60Done(account, todayStr))
    }

    // =========================================================================
    // 3. Test DapiActivitiesEngine.calculateNextProgress (tiến độ 3đ, trần 30đ)
    // =========================================================================

    @Test
    fun testCalculateNextProgress_standardIncrementThreePointsPerArticle() {
        var currentProgress = 0
        val pointsPerArticle = 3
        val maxPoints = 30

        for (articleIndex in 1..10) {
            currentProgress = DapiActivitiesEngine.calculateNextProgress(currentProgress, pointsPerArticle, maxPoints)
            assertEquals("Sau khi đọc bài #$articleIndex tiến độ phải là ${articleIndex * 3}đ", articleIndex * 3, currentProgress)
        }
        assertEquals("Sau 10 bài đọc, tiến độ phải đạt chính xác 30đ", 30, currentProgress)
    }

    @Test
    fun testCalculateNextProgress_strictlyCappedAtThirty() {
        val maxPoints = 30
        val pointsPerArticle = 3

        // Từ mốc 28đ + 3đ -> Phải khóa ở trần 30đ (không được thành 31đ)
        val from28 = DapiActivitiesEngine.calculateNextProgress(28, pointsPerArticle, maxPoints)
        assertEquals("28đ + 3đ phải bị chặn ở trần 30đ", 30, from28)

        // Từ mốc 29đ + 3đ -> Phải khóa ở trần 30đ (không được thành 32đ)
        val from29 = DapiActivitiesEngine.calculateNextProgress(29, pointsPerArticle, maxPoints)
        assertEquals("29đ + 3đ phải bị chặn ở trần 30đ", 30, from29)

        // Từ mốc 30đ (đã max) + 3đ -> Vẫn giữ nguyên 30đ
        val from30 = DapiActivitiesEngine.calculateNextProgress(30, pointsPerArticle, maxPoints)
        assertEquals("30đ + 3đ phải giữ nguyên 30đ", 30, from30)

        // Từ mốc vượt trần 35đ + 3đ -> Vẫn khóa ở 30đ
        val from35 = DapiActivitiesEngine.calculateNextProgress(35, pointsPerArticle, maxPoints)
        assertEquals("35đ + 3đ phải khóa ở 30đ", 30, from35)
    }

    // =========================================================================
    // 4. Test DapiActivitiesEngine.isReadToEarnComplete (ngưỡng 30đ, tối đa 10 bài)
    // =========================================================================

    @Test
    fun testIsReadToEarnComplete_variousProgressStates() {
        val maxPoints = 30
        val maxArticles = 10

        // Chưa hoàn thành
        assertFalse("0đ, 0 bài -> false", DapiActivitiesEngine.isReadToEarnComplete(0, 0, maxPoints, maxArticles))
        assertFalse("15đ, 5 bài -> false", DapiActivitiesEngine.isReadToEarnComplete(15, 5, maxPoints, maxArticles))
        assertFalse("27đ, 9 bài -> false", DapiActivitiesEngine.isReadToEarnComplete(27, 9, maxPoints, maxArticles))

        // Hoàn thành chuẩn (30đ, 10 bài)
        assertTrue("30đ, 10 bài -> true", DapiActivitiesEngine.isReadToEarnComplete(30, 10, maxPoints, maxArticles))

        // Hoàn thành sớm (đạt 30đ khi mới đọc 8 bài do có bài bonus) -> true
        assertTrue("30đ, 8 bài -> true (đã đạt trần điểm)", DapiActivitiesEngine.isReadToEarnComplete(30, 8, maxPoints, maxArticles))

        // Hoàn thành do chạm trần số bài (10 bài, dù chỉ được 24đ) để chống lặp vô tận
        assertTrue("24đ, 10 bài -> true (chạm trần số bài tối đa)", DapiActivitiesEngine.isReadToEarnComplete(24, 10, maxPoints, maxArticles))

        // Vượt ngưỡng điểm (33đ, 0 bài) -> true
        assertTrue("33đ -> true", DapiActivitiesEngine.isReadToEarnComplete(33, 0, maxPoints, maxArticles))
    }

    @Test
    fun testIsReadToEarnComplete_simulationLoopFromPartialProgress() {
        // Giả lập tài khoản đã đọc 7 bài trước đó (21đ / 30đ)
        var currentProgress = 21
        var articlesRead = 0
        val maxPoints = 30
        val maxArticles = 10

        while (!DapiActivitiesEngine.isReadToEarnComplete(currentProgress, articlesRead, maxPoints, maxArticles)) {
            articlesRead++
            currentProgress = DapiActivitiesEngine.calculateNextProgress(currentProgress, 3, maxPoints)
        }

        // Từ 21đ lên 30đ: 21 -> 24 (bài 1) -> 27 (bài 2) -> 30 (bài 3) -> Dừng ngay
        assertEquals("Chỉ cần đọc thêm đúng 3 bài để cán đích 30đ", 3, articlesRead)
        assertEquals("Tiến độ đạt đúng trần 30đ", 30, currentProgress)
    }
}
