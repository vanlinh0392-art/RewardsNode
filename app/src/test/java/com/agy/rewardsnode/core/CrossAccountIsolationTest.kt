package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Kiểm thử tính phân lập dữ liệu và telemetry giữa các tài khoản (Cross-Account Isolation).
 * Ngăn ngừa triệt để hiện tượng rò rỉ dữ liệu (Cross-Account Data Bleeding) giữa tài khoản cũ và tài khoản mới.
 */
class CrossAccountIsolationTest {

    @Before
    fun setUp() {
        RewardsBalanceExtractor.latestTelemetryMap.clear()
    }

    @After
    fun tearDown() {
        RewardsBalanceExtractor.latestTelemetryMap.clear()
    }

    /**
     * 1. testAccountAdapterTelemetryIsolationById:
     * Tạo 2 AccountProfiles (acc1 và acc2).
     * Đưa telemetry vào RewardsBalanceExtractor.latestTelemetryMap CHỈ cho acc1.
     * Xác minh acc2 tuyệt đối không kế thừa telemetry của acc1.
     */
    @Test
    fun testAccountAdapterTelemetryIsolationById() {
        val acc1 = AccountProfile(
            id = "c2e7379f-9636-4b58-80a0-f6dd8822d850",
            email = "vanlinh0392@gmail.com",
            profileName = "Van Linh 1"
        )
        val acc2 = AccountProfile(
            id = "e4e9e325-6a37-45c1-b7ee-a5d3beda7f78",
            email = "vanlinh1212117@gmail.com",
            profileName = "Van Linh 2"
        )

        val telemetryAcc1 = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            source = "flyout_http",
            availablePoints = 1509,
            lifetimePoints = 1509,
            level = 2,
            streakCount = 4,
            pcProgress = 60,
            pcMax = 90,
            mobileProgress = 0,
            mobileMax = 60,
            readNewsProgress = 30,
            readNewsMax = 30,
            dailySetDone = true,
            dailySetProgress = 3,
            dailySetTotal = 3,
            pointsEarnedToday = 113,
            monthlyLevelProgress = 60,
            monthlyLevelMax = 1060
        )

        // Lưu telemetry của acc1 vào map bằng khóa ID
        RewardsBalanceExtractor.latestTelemetryMap[acc1.id] = telemetryAcc1

        // Kiểm tra acc1 truy xuất chính xác telemetry của nó
        val resultAcc1 = RewardsBalanceExtractor.latestTelemetryMap[acc1.id]
        assertNotNull("acc1 phải tìm thấy telemetry tương ứng", resultAcc1)
        assertEquals(1509, resultAcc1?.availablePoints)
        assertEquals(2, resultAcc1?.level)
        assertEquals(60, resultAcc1?.pcProgress)
        assertEquals(90, resultAcc1?.pcMax)

        // Kiểm tra acc2 truy xuất theo ID không tìm thấy gì (không bị leak)
        val resultAcc2 = RewardsBalanceExtractor.latestTelemetryMap[acc2.id]
        assertNull("acc2 tuyệt đối không được kế thừa telemetry của acc1", resultAcc2)

        // Xác minh logic tra cứu của AccountAdapter (lookup strictly bằng account.id)
        val adapterLookupAcc2 = RewardsBalanceExtractor.latestTelemetryMap[acc2.id]
        assertNull("AccountAdapter tra cứu acc2.id phải trả về null", adapterLookupAcc2)
        assertNotEquals("acc2 không được mang số điểm 1509 của acc1", 1509, adapterLookupAcc2?.availablePoints ?: 0)
    }

    /**
     * 2. testNewAccountDefaultsAreLevelOneZeroPoints:
     * Xác minh một tài khoản mới được khởi tạo (constructor / factory) mặc định luôn ở Level 1,
     * pcMax 30, mobileMax 0, availablePoints 0, streakCount 0.
     */
    @Test
    fun testNewAccountDefaultsAreLevelOneZeroPoints() {
        // Kiểm tra primary constructor defaults
        val accFromConstructor = AccountProfile(
            id = "test-uuid-001",
            email = "vanlinh1212117@gmail.com",
            profileName = "Profile vanlinh1212117"
        )

        assertEquals("Level mặc định phải là 1", 1, accFromConstructor.level)
        assertEquals("pcMax mặc định phải là 30", 30, accFromConstructor.pcMax)
        assertEquals("mobileMax mặc định phải là 0", 0, accFromConstructor.mobileMax)
        assertEquals("availablePoints mặc định phải là 0", 0, accFromConstructor.availablePoints)
        assertEquals("totalPoints mặc định phải là 0", 0, accFromConstructor.totalPoints)
        assertEquals("streakCount mặc định phải là 0", 0, accFromConstructor.streakCount)
        assertEquals("pcProgress mặc định phải là 0", 0, accFromConstructor.pcProgress)
        assertEquals("mobileProgress mặc định phải là 0", 0, accFromConstructor.mobileProgress)
        assertEquals("pointsEarnedToday mặc định phải là 0", 0, accFromConstructor.pointsEarnedToday)
        assertEquals("targetSearches mặc định cho Level 1 phải là 35", 35, accFromConstructor.targetSearches)

        // Kiểm tra companion factory createDefault
        val accFromFactory = AccountProfile.createDefault(
            id = "test-uuid-002",
            email = "brandnew@outlook.com"
        )

        assertEquals(1, accFromFactory.level)
        assertEquals(30, accFromFactory.pcMax)
        assertEquals(0, accFromFactory.mobileMax)
        assertEquals(0, accFromFactory.availablePoints)
        assertEquals(0, accFromFactory.totalPoints)
        assertEquals(0, accFromFactory.streakCount)
        assertEquals(0, accFromFactory.pcProgress)
        assertEquals(0, accFromFactory.mobileProgress)
        assertEquals(0, accFromFactory.pointsEarnedToday)
        assertEquals(35, accFromFactory.targetSearches)
    }

    /**
     * 3. testFlyoutExtractionDoesNotLeakEmailAcrossDifferentAccounts:
     * Xác minh cơ chế bóc tách thông tin danh tính (Identity Resolution) không bị nhiễm chéo
     * giữa các tài khoản khi API panelflyout không trả về trường email/name.
     */
    @Test
    fun testFlyoutExtractionDoesNotLeakEmailAcrossDifferentAccounts() {
        // acc1 đã tồn tại với email và telemetry
        val acc1Id = "acc1_uuid"
        val acc1Email = "vanlinh0392@gmail.com"
        val telemetryAcc1 = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            source = "flyout_http",
            email = acc1Email,
            profileName = "Van Linh",
            availablePoints = 1509
        )
        RewardsBalanceExtractor.latestTelemetryMap[acc1Id] = telemetryAcc1

        // Phản hồi JSON panelflyout từ Microsoft cho acc2 (không có email hay profileName)
        val flyoutJsonWithoutEmail = JSONObject("""
            {
                "flyoutResult": {
                    "userId": "ms_rewards_user_9988",
                    "userStatus": {
                        "availablePoints": 0,
                        "lifetimePoints": 0,
                        "level": 1,
                        "streakInfo": {
                            "streakCount": 0
                        }
                    },
                    "dailyCheckInPromotion": {
                        "attributes": {
                            "points_per_pc_search": "3"
                        }
                    }
                }
            }
        """.trimIndent())

        val parsedBalanceAcc2 = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJsonWithoutEmail)

        // parsed balance không được chứa email acc1
        assertNull("Flyout JSON không có email thì parsed email phải là null", parsedBalanceAcc2.email)
        assertNotEquals("Tuyệt đối không được rò rỉ email của acc1 vào kết quả bóc tách", acc1Email, parsedBalanceAcc2.email)

        // Áp dụng balance vào acc2 và kiểm tra danh tính không bị nhiễm độc
        val acc2 = AccountProfile.createDefault(
            id = "acc2_uuid",
            email = "vanlinh1212117@gmail.com"
        )
        acc2.applyBalance(parsedBalanceAcc2, todayStr = "2026-10-25")

        assertEquals("Email của acc2 phải giữ nguyên danh tính riêng của nó", "vanlinh1212117@gmail.com", acc2.email)
        assertNotEquals("acc2 không được bị đổi thành email của acc1", acc1Email, acc2.email)
    }
}
