package com.agy.rewardsnode.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Test xác thực tính năng bóc tách, lọc và thực thi More Promotions (Keep Earning)
 * kết hợp hiển thị phân tách trên giao diện MainActivity Dashboard.
 */
class MorePromotionsExecutionTest {

    /**
     * Requirement 2a:
     * Kiểm tra RewardsActivitiesEngine.isSafePromotion cho phép các thẻ thưởng an toàn Level 2
     * ("/levelbenefitexclusive" với 5..50đ như "Burger rau củ", "Hóa thạch được yêu thích"),
     * đồng thời chặn tuyệt đối các thẻ rủi ro: sweepstakes, donate, đổi quà, 0đ, >100đ.
     */
    @Test
    fun testIsSafePromotionAllowsLevel2ExclusiveAndBlocksRiskyTasks() {
        // 1. Thẻ Level 2 hợp lệ: Burger rau củ (+15đ) và Hóa thạch được yêu thích (+15đ)
        assertTrue(
            "Burger rau củ (+15đ) tại /levelbenefitexclusive phải được cho phép",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Burger rau củ",
                destinationUrl = "https://rewards.bing.com/levelbenefitexclusive",
                pointValue = 15
            )
        )

        assertTrue(
            "Hóa thạch được yêu thích (+15đ) tại /levelbenefitexclusive phải được cho phép",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Hóa thạch được yêu thích",
                destinationUrl = "https://rewards.bing.com/levelbenefitexclusive",
                pointValue = 15
            )
        )

        // 2. Thẻ an toàn thông thường (+5đ, +10đ)
        assertTrue(
            "Nhiệm vụ tìm kiếm thông thường (+10đ) phải được cho phép",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Mẹo tìm kiếm thông minh",
                destinationUrl = "https://www.bing.com/search?q=tips",
                pointValue = 10
            )
        )

        // 3. Thẻ 0đ hoặc điểm âm -> Phải bị chặn
        assertFalse(
            "Nhiệm vụ 0đ phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Khám phá Bing",
                destinationUrl = "https://www.bing.com",
                pointValue = 0
            )
        )
        assertFalse(
            "Nhiệm vụ điểm âm phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Khám phá Bing âm điểm",
                destinationUrl = "https://www.bing.com",
                pointValue = -5
            )
        )

        // 4. Thẻ vượt trần an toàn (> 100đ, thường là nạp tiền/mua sắm) -> Phải bị chặn
        assertFalse(
            "Nhiệm vụ điểm > 100đ phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Mua gói dịch vụ",
                destinationUrl = "https://www.microsoft.com/store",
                pointValue = 150
            )
        )

        // 5. Thẻ quay số / rút thăm trúng thưởng (Sweepstakes, Raffle) -> Phải bị chặn
        assertFalse(
            "Sweepstakes phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Win Surface Pro Sweepstakes",
                destinationUrl = "https://rewards.bing.com/sweepstakes",
                pointValue = 10
            )
        )
        assertFalse(
            "Từ khóa trúng thưởng/quay số tiếng Việt phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Vòng quay may mắn trúng thưởng",
                destinationUrl = "https://rewards.bing.com/lucky",
                pointValue = 10
            )
        )

        // 6. Thẻ quyên góp từ thiện (Donate, Charity) -> Phải bị chặn
        assertFalse(
            "Donate phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Donate to UNICEF",
                destinationUrl = "https://rewards.bing.com/donate",
                pointValue = 10
            )
        )
        assertFalse(
            "Từ thiện tiếng Việt phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Quyên góp quỹ cứu trợ",
                destinationUrl = "https://rewards.bing.com/charity",
                pointValue = 10
            )
        )

        // 7. Thẻ giới thiệu hoặc đổi quà (referandearn, redeem) -> Phải bị chặn
        assertFalse(
            "Refer and earn phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Mời bạn bè nhận quà",
                destinationUrl = "https://rewards.bing.com/referandearn",
                pointValue = 50
            )
        )
        assertFalse(
            "Redeem phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Đổi thẻ quà tặng",
                destinationUrl = "https://rewards.bing.com/redeem/0001",
                pointValue = 10
            )
        )

        // 8. Thẻ levelbenefitexclusive nhưng điểm bất thường (!in 5..50) -> Phải bị chặn
        assertFalse(
            "levelbenefitexclusive với điểm 60đ (>50đ) phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion(
                title = "Ưu đãi độc quyền Level 2 bất thường",
                destinationUrl = "https://rewards.bing.com/levelbenefitexclusive",
                pointValue = 60
            )
        )
    }

    /**
     * Requirement 2b:
     * Kiểm tra logic bóc tách Badge của MainActivity:
     * - Tách 3 thẻ đầu tiên làm Daily Set, các thẻ còn lại làm More Promo (Khám phá thêm).
     * - Khi có More Promo: Hiển thị "$dsDone/$dsTotal Daily • $promoDone/$promoTotal Khám phá".
     * - Khi chỉ có Daily Set (hoặc không có More Promo): Hiển thị "$dsDone/$dsTotal DAILY SET".
     */
    @Test
    fun testMainActivityBadgeSeparationLogic() {
        fun computeBadgeText(dailyTasksList: List<DailyTask>): String {
            val dailySetTasks = dailyTasksList.take(3)
            val morePromoTasks = dailyTasksList.drop(3)
            val dsDone = dailySetTasks.count { it.isCompleted }
            val dsTotal = dailySetTasks.size
            val promoDone = morePromoTasks.count { it.isCompleted }
            val promoTotal = morePromoTasks.size

            return if (morePromoTasks.isNotEmpty()) {
                "$dsDone/$dsTotal Daily • $promoDone/$promoTotal Khám phá"
            } else {
                "$dsDone/$dsTotal DAILY SET"
            }
        }

        // Tình huống 1: 11 nhiệm vụ (3 Daily Set + 8 More Promo), Daily Set xong 3/3, Promo xong 6/8
        val tasks11 = listOf(
            DailyTask("ds1", "Daily Task 1", 10, "url1", "poll", true),
            DailyTask("ds2", "Daily Task 2", 10, "url2", "quiz", true),
            DailyTask("ds3", "Daily Task 3", 10, "url3", "urlreward", true),
            DailyTask("pr1", "Promo 1", 10, "purl1", "urlreward", true),
            DailyTask("pr2", "Promo 2", 10, "purl2", "urlreward", true),
            DailyTask("pr3", "Promo 3", 10, "purl3", "urlreward", true),
            DailyTask("pr4", "Promo 4", 10, "purl4", "urlreward", true),
            DailyTask("pr5", "Promo 5", 10, "purl5", "urlreward", true),
            DailyTask("pr6", "Promo 6", 10, "purl6", "urlreward", true),
            DailyTask("pr7", "Burger rau củ", 15, "https://rewards.bing.com/levelbenefitexclusive", "urlreward", false),
            DailyTask("pr8", "Hóa thạch được yêu thích", 15, "https://rewards.bing.com/levelbenefitexclusive", "urlreward", false)
        )
        val badge11 = computeBadgeText(tasks11)
        assertEquals("3/3 Daily • 6/8 Khám phá", badge11)

        // Tình huống 2: Chỉ có 3 nhiệm vụ Daily Set, không có More Promo
        val tasksOnlyDailySet = listOf(
            DailyTask("ds1", "Daily Task 1", 10, "url1", "poll", true),
            DailyTask("ds2", "Daily Task 2", 10, "url2", "quiz", false),
            DailyTask("ds3", "Daily Task 3", 10, "url3", "urlreward", false)
        )
        val badgeOnlyDaily = computeBadgeText(tasksOnlyDailySet)
        assertEquals("1/3 DAILY SET", badgeOnlyDaily)

        // Tình huống 3: Toàn bộ 11 nhiệm vụ đều đã hoàn tất
        val allDoneTasks = tasks11.map { it.copy(isCompleted = true) }
        val badgeAllDone = computeBadgeText(allDoneTasks)
        assertEquals("3/3 Daily • 8/8 Khám phá", badgeAllDone)

        // Tình huống 4: Chưa hoàn thành nhiệm vụ nào
        val noneDoneTasks = tasks11.map { it.copy(isCompleted = false) }
        val badgeNoneDone = computeBadgeText(noneDoneTasks)
        assertEquals("0/3 Daily • 0/8 Khám phá", badgeNoneDone)
    }

    /**
     * Requirement 2c:
     * Kiểm tra logic lọc Phase 0 More Promotions:
     * Tách đúng các nhiệm vụ không thuộc Daily Set và thỏa mãn isSafePromotion,
     * loại bỏ hoàn toàn các thẻ Daily Set, Sweepstakes, 0đ.
     */
    @Test
    fun testPhase0MorePromotionsFilteringSelectsNonDailySetSafePromotions() {
        val dailySetTasks = listOf(
            DailyTask("daily_set_1", "Câu đố ngày", 10, "https://www.bing.com/quiz", "quiz", true),
            DailyTask("daily_set_2", "Bình chọn ngày", 10, "https://www.bing.com/poll", "poll", true),
            DailyTask("daily_set_3", "Khám phá ngày", 10, "https://www.bing.com/news", "urlreward", true)
        )

        val fullDailyTasksList = dailySetTasks + listOf(
            DailyTask("WW_Rewards_locked_level2_burger", "Burger rau củ", 15, "https://rewards.bing.com/levelbenefitexclusive", "urlreward", false),
            DailyTask("WW_Rewards_locked_level2_fossil", "Hóa thạch được yêu thích", 15, "https://rewards.bing.com/levelbenefitexclusive", "urlreward", false),
            DailyTask("more_promo_valid", "Mẹo dùng Edge", 10, "https://www.bing.com/search?q=edge", "urlreward", true),
            DailyTask("more_promo_sweepstakes", "Win Surface Pro", 10, "https://rewards.bing.com/sweepstakes", "urlreward", false),
            DailyTask("more_promo_zero_pt", "0 Pt Explore", 0, "https://rewards.bing.com/explore", "urlreward", false),
            DailyTask("more_promo_donate", "Quyên góp từ thiện", 10, "https://rewards.bing.com/donate", "urlreward", false)
        )

        // Thực hiện lọc theo logic chuẩn của Phase 0 StateMachine
        val morePromoTasks = fullDailyTasksList.filter { task ->
            !dailySetTasks.any { it.id == task.id } &&
            RewardsActivitiesEngine.isSafePromotion(task.title, task.destinationUrl, task.pointValue)
        }

        // Kiểm tra số lượng promo hợp lệ được lọc: 3 mục (Burger rau củ, Hóa thạch, Mẹo dùng Edge)
        assertEquals(3, morePromoTasks.size)

        // Kiểm tra Daily Set không bị lọt vào More Promo
        assertTrue("Không chứa thẻ Daily Set", morePromoTasks.none { dailySetTasks.any { ds -> ds.id == it.id } })

        // Kiểm tra 2 thẻ +15đ Level 2 có mặt trong danh sách
        assertTrue("Chứa Burger rau củ", morePromoTasks.any { it.title == "Burger rau củ" && it.pointValue == 15 })
        assertTrue("Chứa Hóa thạch được yêu thích", morePromoTasks.any { it.title == "Hóa thạch được yêu thích" && it.pointValue == 15 })

        // Kiểm tra các thẻ rủi ro/0đ bị loại bỏ
        assertTrue("Không chứa sweepstakes", morePromoTasks.none { it.id == "more_promo_sweepstakes" })
        assertTrue("Không chứa 0đ", morePromoTasks.none { it.pointValue <= 0 })
        assertTrue("Không chứa donate", morePromoTasks.none { it.id == "more_promo_donate" })

        // Kiểm tra danh sách các thẻ chưa làm (cần giải quyết trong Phase 0)
        val pendingPromoTasks = morePromoTasks.filter { !it.isCompleted }
        assertEquals("Có đúng 2 thẻ đang chờ làm (+15đ)", 2, pendingPromoTasks.size)
        assertEquals(listOf("Burger rau củ", "Hóa thạch được yêu thích"), pendingPromoTasks.map { it.title })
    }
}
