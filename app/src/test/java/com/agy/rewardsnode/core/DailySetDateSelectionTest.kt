package com.agy.rewardsnode.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Unit Test Suite kiểm chứng giải pháp Daily Set Date Selection & Bóc tách đúng ngày:
 * 1. selectTodayDailySetKey() với JSON dailySetPromotions chứa 3 ngày [hôm qua, hôm nay, ngày mai],
 *    xác nhận chọn đúng ngày hôm nay theo múi giờ local (GMT+7 / default).
 * 2. Khi 3 thẻ của ngày hôm nay có complete = false, parseFlyoutJsonPayload() trả về
 *    dailySetDone = false và dailySetProgress = 0.
 */
class DailySetDateSelectionTest {

    /**
     * Test 1: selectTodayDailySetKey() với JSON dailySetPromotions chứa 3 ngày [hôm qua, hôm nay, ngày mai],
     * xác nhận chọn đúng ngày hôm nay theo múi giờ local (GMT+7 / default).
     */
    @Test
    fun testSelectTodayDailySetKeyChoosesTodayInLocalTimeZone() {
        val tzDefault = TimeZone.getDefault()
        val fmt = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply { timeZone = tzDefault }

        val cal = Calendar.getInstance(tzDefault)
        val todayStr = fmt.format(cal.time)

        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = fmt.format(cal.time)

        cal.add(Calendar.DAY_OF_YEAR, 2)
        val tomorrowStr = fmt.format(cal.time)

        // Tạo JSON dailySetPromotions với thứ tự bất kỳ (ví dụ: hôm qua, ngày mai, hôm nay)
        val dailySetPromos = JSONObject().apply {
            put(yesterdayStr, JSONArray().apply {
                put(JSONObject().apply {
                    put("offerId", "promo_yesterday")
                    put("complete", true)
                })
            })
            put(todayStr, JSONArray().apply {
                put(JSONObject().apply {
                    put("offerId", "promo_today")
                    put("complete", false)
                })
            })
            put(tomorrowStr, JSONArray().apply {
                put(JSONObject().apply {
                    put("offerId", "promo_tomorrow")
                    put("complete", false)
                })
            })
        }

        val selectedKey = RewardsBalanceExtractor.selectTodayDailySetKey(dailySetPromos)

        assertNotNull("Key trả về không được null", selectedKey)
        assertEquals(
            "selectTodayDailySetKey() phải chọn chính xác ngày hôm nay ($todayStr) theo múi giờ local, không được chọn nhầm ngày hôm qua ($yesterdayStr)",
            todayStr,
            selectedKey
        )
    }

    /**
     * Test 1b: Kiểm tra độ ổn định của selectTodayDailySetKey() đối với múi giờ GMT+7 cụ thể
     * (Việt Nam / Bangkok) khi API trả về 3 ngày.
     */
    @Test
    fun testSelectTodayDailySetKeyWithGmt7ExplicitTimeZone() {
        val tzGmt7 = TimeZone.getTimeZone("GMT+7")
        val fmtGmt7 = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply { timeZone = tzGmt7 }

        val cal = Calendar.getInstance(tzGmt7)
        val todayGmt7 = fmtGmt7.format(cal.time)

        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayGmt7 = fmtGmt7.format(cal.time)

        cal.add(Calendar.DAY_OF_YEAR, 2)
        val tomorrowGmt7 = fmtGmt7.format(cal.time)

        val promosGmt7 = JSONObject().apply {
            put(yesterdayGmt7, JSONArray())
            put(todayGmt7, JSONArray())
            put(tomorrowGmt7, JSONArray())
        }

        val selected = RewardsBalanceExtractor.selectTodayDailySetKey(promosGmt7)
        assertEquals(
            "selectTodayDailySetKey() phải chọn đúng ngày GMT+7 ($todayGmt7)",
            todayGmt7,
            selected
        )
    }

    /**
     * Test 2: Khi 3 thẻ của ngày hôm nay có complete = false, parseFlyoutJsonPayload()
     * trả về dailySetDone = false và dailySetProgress = 0.
     */
    @Test
    fun testParseFlyoutJsonPayloadReturnsDailySetIncompleteWhenThreeCardsNotDone() {
        val tz = TimeZone.getDefault()
        val fmt = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply { timeZone = tz }

        val cal = Calendar.getInstance(tz)
        val todayStr = fmt.format(cal.time)

        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = fmt.format(cal.time)

        cal.add(Calendar.DAY_OF_YEAR, 2)
        val tomorrowStr = fmt.format(cal.time)

        // Mô phỏng đúng payload thực tế của Bing Rewards API với 3 ngày
        val flyoutJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 5000)
                put("lifetimePoints", 50000)
                put("streakInfo", JSONObject().apply {
                    put("streakCount", 5)
                })
                put("counters", JSONObject().apply {
                    put("dailyPoint", JSONArray().apply {
                        put(JSONObject().apply {
                            put("pointProgress", 60)
                            put("pointProgressMax", 90)
                        })
                    })
                })
            })
            put("dailySetPromotions", JSONObject().apply {
                // Ngày hôm qua: Người dùng đã hoàn thành 3/3
                put(yesterdayStr, JSONArray().apply {
                    put(JSONObject().apply {
                        put("offerId", "task_y1")
                        put("title", "Hôm qua Thẻ 1")
                        put("pointProgressMax", 10)
                        put("pointProgress", 10)
                        put("complete", true)
                    })
                    put(JSONObject().apply {
                        put("offerId", "task_y2")
                        put("title", "Hôm qua Thẻ 2")
                        put("pointProgressMax", 10)
                        put("pointProgress", 10)
                        put("complete", true)
                    })
                    put(JSONObject().apply {
                        put("offerId", "task_y3")
                        put("title", "Hôm qua Thẻ 3")
                        put("pointProgressMax", 10)
                        put("pointProgress", 10)
                        put("complete", true)
                    })
                })
                // Ngày hôm nay: Cả 3 thẻ đều chưa làm (complete = false, pointProgress = 0)
                // Giống hệt trường hợp vanlinh0392@gmail.com: Hài kịch, Seattle, Croatia
                put(todayStr, JSONArray().apply {
                    put(JSONObject().apply {
                        put("offerId", "daily_set_comedy")
                        put("title", "Các sự kiện hài kịch sắp tới")
                        put("destinationUrl", "https://www.bing.com/search?q=comedy")
                        put("pointProgressMax", 10)
                        put("pointProgress", 0)
                        put("complete", false)
                    })
                    put(JSONObject().apply {
                        put("offerId", "daily_set_seattle")
                        put("title", "Vẻ đẹp mùa thu se lạnh của Seattle")
                        put("destinationUrl", "https://www.bing.com/search?q=seattle")
                        put("pointProgressMax", 10)
                        put("pointProgress", 0)
                        put("complete", false)
                    })
                    put(JSONObject().apply {
                        put("offerId", "daily_set_croatia")
                        put("title", "Thủ đô Croatia?")
                        put("destinationUrl", "https://www.bing.com/search?q=croatia")
                        put("pointProgressMax", 10)
                        put("pointProgress", 0)
                        put("complete", false)
                    })
                })
                // Ngày mai: Chưa mở
                put(tomorrowStr, JSONArray().apply {
                    put(JSONObject().apply {
                        put("offerId", "task_t1")
                        put("title", "Ngày mai Thẻ 1")
                        put("pointProgressMax", 10)
                        put("pointProgress", 0)
                        put("complete", false)
                    })
                })
            })
        }

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson, "test_account_vanlinh")

        assertFalse(
            "Khi 3 thẻ của ngày hôm nay đều có complete = false, dailySetDone phải là FALSE",
            balance.dailySetDone
        )
        assertEquals(
            "Khi chưa làm thẻ nào của ngày hôm nay, dailySetProgress phải là 0",
            0,
            balance.dailySetProgress
        )
        assertEquals(
            "Tổng số thẻ Daily Set ngày hôm nay phải là 3",
            3,
            balance.dailySetTotal
        )
        assertEquals(
            "Danh sách dailyTasks phải bóc tách đúng 3 thẻ của ngày hôm nay",
            3,
            balance.dailyTasks.size
        )
    }

    /**
     * Test 2b: Khi 1 trong 3 thẻ của ngày hôm nay đã làm (complete = true),
     * dailySetDone = false và dailySetProgress = 1.
     */
    @Test
    fun testParseFlyoutJsonPayloadPartialDailySetProgress() {
        val tz = TimeZone.getDefault()
        val fmt = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply { timeZone = tz }
        val cal = Calendar.getInstance(tz)
        val todayStr = fmt.format(cal.time)

        val flyoutJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 5010)
            })
            put("dailySetPromotions", JSONObject().apply {
                put(todayStr, JSONArray().apply {
                    put(JSONObject().apply {
                        put("offerId", "task_1")
                        put("pointProgressMax", 10)
                        put("pointProgress", 10)
                        put("complete", true)
                    })
                    put(JSONObject().apply {
                        put("offerId", "task_2")
                        put("pointProgressMax", 10)
                        put("pointProgress", 0)
                        put("complete", false)
                    })
                    put(JSONObject().apply {
                        put("offerId", "task_3")
                        put("pointProgressMax", 10)
                        put("pointProgress", 0)
                        put("complete", false)
                    })
                })
            })
        }

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson, "test_partial_ds")
        assertFalse("1/3 thẻ hoàn thành thì dailySetDone vẫn phải là false", balance.dailySetDone)
        assertEquals("Tiến độ phải là 1 thẻ", 1, balance.dailySetProgress)
        assertEquals(3, balance.dailySetTotal)
    }

    /**
     * Test 2c: Khi cả 3 thẻ của ngày hôm nay đều hoàn thành (complete = true),
     * dailySetDone = true và dailySetProgress = 3.
     */
    @Test
    fun testParseFlyoutJsonPayloadAllThreeCardsComplete() {
        val tz = TimeZone.getDefault()
        val fmt = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply { timeZone = tz }
        val cal = Calendar.getInstance(tz)
        val todayStr = fmt.format(cal.time)

        val flyoutJson = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 5030)
            })
            put("dailySetPromotions", JSONObject().apply {
                put(todayStr, JSONArray().apply {
                    put(JSONObject().apply {
                        put("offerId", "task_1")
                        put("pointProgressMax", 10)
                        put("pointProgress", 10)
                        put("complete", true)
                    })
                    put(JSONObject().apply {
                        put("offerId", "task_2")
                        put("pointProgressMax", 10)
                        put("pointProgress", 10)
                        put("complete", true)
                    })
                    put(JSONObject().apply {
                        put("offerId", "task_3")
                        put("pointProgressMax", 10)
                        put("pointProgress", 10)
                        put("complete", true)
                    })
                })
            })
        }

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(flyoutJson, "test_all_ds_done")
        assertTrue("3/3 thẻ hoàn thành thì dailySetDone phải là true", balance.dailySetDone)
        assertEquals("Tiến độ phải là 3 thẻ", 3, balance.dailySetProgress)
        assertEquals(3, balance.dailySetTotal)
    }
}
