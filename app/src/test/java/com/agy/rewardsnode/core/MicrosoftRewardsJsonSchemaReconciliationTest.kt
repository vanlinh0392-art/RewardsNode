package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Unit Test Suite kiểm thử đồng bộ và tương thích đa hình Schema JSON Microsoft Rewards:
 * 1. Polymorphic `counters` parsing:
 *    - `pcSearch` & `mobileSearch` dạng JSONArray vs JSONObject.
 *    - Các alias của Edge Bonus (`edgeBonus`, `bonusPoints`, `edgeSearch`, `bonusSearch`).
 *    - Các alias trường điểm tiến độ (`progress`, `pointProgress`, `points`).
 * 2. `selectTodayDailySetKey`:
 *    - Lựa chọn key ở giữa `sortedKeys[1]` khi có 3 ngày trong `dailySetPromotions`.
 * 3. `AccountProfile.applyBalance()`:
 *    - Cập nhật chính xác `dailySetDone`, `dailySetProgress`, `dailySetTotal`.
 *    - Reset `readProgress` khi sang ngày mới (`wasSameDay == false`) vs giữ nguyên/tiến độ khi cùng ngày (`wasSameDay == true`).
 *    - Chốt chặn (latch) `pointsEarnedToday` an toàn khi chuyển đổi từ Flyout sang SERP.
 *    - Đồng bộ `puzzleProgress`.
 * 4. Truyền `description` vào `DailyTask` và nhận diện Quiz/Poll tương tác thông qua từ khóa trong mô tả.
 */
class MicrosoftRewardsJsonSchemaReconciliationTest {

    // =========================================================================
    // 1. Polymorphic `counters` Parsing
    // =========================================================================

    @Test
    fun testCountersPcAndMobileSearchAsJsonObject() {
        val json = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 5200)
                put("counters", JSONObject().apply {
                    put("pcSearch", JSONObject().apply {
                        put("pointProgress", 90)
                        put("pointProgressMax", 90)
                    })
                    put("mobileSearch", JSONObject().apply {
                        put("pointProgress", 60)
                        put("pointProgressMax", 60)
                    })
                })
            })
        }

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(json)
        assertEquals("PC Progress phải bóc tách đúng từ JSONObject", 90, balance.pcProgress)
        assertEquals("PC Max phải bóc tách đúng từ JSONObject", 90, balance.pcMax)
        assertEquals("Mobile Progress phải bóc tách đúng từ JSONObject", 60, balance.mobileProgress)
        assertEquals("Mobile Max phải bóc tách đúng từ JSONObject", 60, balance.mobileMax)
    }

    @Test
    fun testCountersPcAndMobileSearchAsJsonArray() {
        val json = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 4500)
                put("counters", JSONObject().apply {
                    put("pcSearch", JSONArray().apply {
                        put(JSONObject().apply {
                            put("pointProgress", 30)
                            put("pointProgressMax", 90)
                        })
                    })
                    put("mobileSearch", JSONArray().apply {
                        put(JSONObject().apply {
                            put("pointProgress", 20)
                            put("pointProgressMax", 60)
                        })
                    })
                })
            })
        }

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(json)
        assertEquals("PC Progress phải bóc tách đúng từ JSONArray phần tử đầu tiên", 30, balance.pcProgress)
        assertEquals("PC Max phải bóc tách đúng từ JSONArray", 90, balance.pcMax)
        assertEquals("Mobile Progress phải bóc tách đúng từ JSONArray phần tử đầu tiên", 20, balance.mobileProgress)
        assertEquals("Mobile Max phải bóc tách đúng từ JSONArray", 60, balance.mobileMax)
    }

    @Test
    fun testEdgeBonusKeyAliases() {
        val aliases = listOf("edgeBonus", "bonusPoints", "edgeSearch", "bonusSearch")

        for (alias in aliases) {
            // Test dạng JSONObject
            val jsonObj = JSONObject().apply {
                put("userStatus", JSONObject().apply {
                    put("availablePoints", 3000)
                    put("counters", JSONObject().apply {
                        put(alias, JSONObject().apply {
                            put("pointProgress", 12)
                            put("pointProgressMax", 12)
                        })
                    })
                })
            }
            val balanceObj = RewardsBalanceExtractor.parseFlyoutJsonPayload(jsonObj)
            assertEquals("Edge bonus alias '$alias' (JSONObject) phải bóc tách đúng progress", 12, balanceObj.edgeProgress)
            assertEquals("Edge bonus alias '$alias' (JSONObject) phải bóc tách đúng max", 12, balanceObj.edgeMax)

            // Test dạng JSONArray
            val jsonArr = JSONObject().apply {
                put("userStatus", JSONObject().apply {
                    put("availablePoints", 3000)
                    put("counters", JSONObject().apply {
                        put(alias, JSONArray().apply {
                            put(JSONObject().apply {
                                put("pointProgress", 12)
                                put("pointProgressMax", 12)
                            })
                        })
                    })
                })
            }
            val balanceArr = RewardsBalanceExtractor.parseFlyoutJsonPayload(jsonArr)
            assertEquals("Edge bonus alias '$alias' (JSONArray) phải bóc tách đúng progress", 12, balanceArr.edgeProgress)
            assertEquals("Edge bonus alias '$alias' (JSONArray) phải bóc tách đúng max", 12, balanceArr.edgeMax)
        }
    }

    @Test
    fun testProgressFieldAliasesInCountersAndDailyPoint() {
        // 1. Kiểm tra alias "progress" và "max"
        val jsonProgressAlias = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 2000)
                put("counters", JSONObject().apply {
                    put("pcSearch", JSONObject().apply {
                        put("progress", 45)
                        put("max", 90)
                    })
                    put("mobileSearch", JSONObject().apply {
                        put("progress", 30)
                        put("max", 60)
                    })
                })
            })
        }
        val balanceProgress = RewardsBalanceExtractor.parseFlyoutJsonPayload(jsonProgressAlias)
        assertEquals("PC Progress alias 'progress' phải đạt 45", 45, balanceProgress.pcProgress)
        assertEquals("PC Max alias 'max' phải đạt 90", 90, balanceProgress.pcMax)
        assertEquals("Mobile Progress alias 'progress' phải đạt 30", 30, balanceProgress.mobileProgress)
        assertEquals("Mobile Max alias 'max' phải đạt 60", 60, balanceProgress.mobileMax)

        // 2. Kiểm tra alias "points" và "maxPoint"
        val jsonPointsAlias = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 2000)
                put("counters", JSONObject().apply {
                    put("pcSearch", JSONObject().apply {
                        put("points", 60)
                        put("maxPoint", 90)
                    })
                    put("edgeBonus", JSONObject().apply {
                        put("points", 12)
                        put("maxPoint", 12)
                    })
                })
            })
        }
        val balancePoints = RewardsBalanceExtractor.parseFlyoutJsonPayload(jsonPointsAlias)
        assertEquals("PC Progress alias 'points' phải đạt 60", 60, balancePoints.pcProgress)
        assertEquals("PC Max alias 'maxPoint' phải đạt 90", 90, balancePoints.pcMax)
        assertEquals("Edge Progress alias 'points' phải đạt 12", 12, balancePoints.edgeProgress)
        assertEquals("Edge Max alias 'maxPoint' phải đạt 12", 12, balancePoints.edgeMax)

        // 3. Kiểm tra alias trong dailyPoint array
        val jsonDailyPoint = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 2000)
                put("counters", JSONObject().apply {
                    put("dailyPoint", JSONArray().apply {
                        put(JSONObject().apply {
                            put("name", "PC search")
                            put("points", 30)
                            put("maxPoint", 90)
                        })
                        put(JSONObject().apply {
                            put("name", "Microsoft Edge search")
                            put("progress", 9)
                            put("max", 12)
                        })
                    })
                })
            })
        }
        val balanceDailyPoint = RewardsBalanceExtractor.parseFlyoutJsonPayload(jsonDailyPoint)
        assertEquals("dailyPoint PC search bóc tách chính xác", 30, balanceDailyPoint.pcProgress)
        assertEquals("dailyPoint Edge search bóc tách chính xác", 9, balanceDailyPoint.edgeProgress)
    }

    // =========================================================================
    // 2. selectTodayDailySetKey: Selecting Middle Key sortedKeys[1]
    // =========================================================================

    @Test
    fun testSelectTodayDailySetKeyPicksMiddleWhenThreeKeysPresent() {
        // Ba ngày tùy ý không trùng ngày máy hiện tại để kích hoạt fallback sortedKeys[sortedKeys.size / 2]
        val dailySetPromos = JSONObject().apply {
            put("01/10/2024", JSONArray())
            put("01/12/2024", JSONArray())
            put("01/11/2024", JSONArray())
        }

        val selectedKey = RewardsBalanceExtractor.selectTodayDailySetKey(dailySetPromos)
        assertNotNull("Key trả về không được null", selectedKey)
        assertEquals(
            "Khi có 3 ngày, thuật toán phải sắp xếp theo thời gian và chọn ngày ở giữa (sortedKeys[1] = 01/11/2024)",
            "01/11/2024",
            selectedKey
        )
    }

    @Test
    fun testSelectTodayDailySetKeyIsoFormatMiddleKey() {
        val dailySetPromosIso = JSONObject().apply {
            put("2024-05-03", JSONArray())
            put("2024-05-01", JSONArray())
            put("2024-05-02", JSONArray())
        }

        val selectedKey = RewardsBalanceExtractor.selectTodayDailySetKey(dailySetPromosIso)
        assertEquals(
            "Định dạng ISO yyyy-MM-dd phải chọn ngày ở giữa 2024-05-02",
            "2024-05-02",
            selectedKey
        )
    }

    @Test
    fun testSelectTodayDailySetKeyMatchesTodayWhenPresentInThreeKeys() {
        val tzDefault = TimeZone.getDefault()
        val fmt = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply { timeZone = tzDefault }
        val cal = Calendar.getInstance(tzDefault)
        val todayStr = fmt.format(cal.time)

        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = fmt.format(cal.time)

        cal.add(Calendar.DAY_OF_YEAR, 2)
        val tomorrowStr = fmt.format(cal.time)

        val dailySetPromos = JSONObject().apply {
            put(yesterdayStr, JSONArray())
            put(todayStr, JSONArray())
            put(tomorrowStr, JSONArray())
        }

        val selectedKey = RewardsBalanceExtractor.selectTodayDailySetKey(dailySetPromos)
        assertEquals("Phải chọn chính xác ngày hôm nay trong bộ 3 ngày", todayStr, selectedKey)
    }

    // =========================================================================
    // 3. AccountProfile.applyBalance()
    // =========================================================================

    @Test
    fun testApplyBalanceUpdatesDailySetDoneProgressAndTotal() {
        val profile = AccountProfile.createDefault("test_id", "user@msn.com")
        assertEquals(false, profile.dailySetDone)
        assertEquals(0, profile.dailySetProgress)
        assertEquals(3, profile.dailySetTotal)

        // Lần cập nhật 1: Đang làm nhiệm vụ 2/3
        val balance1 = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 1500,
            dailySetTotal = 3,
            dailySetProgress = 2,
            dailySetDone = false,
            isSuccess = true
        )
        profile.applyBalance(balance1, todayStr = "2026-10-25")
        assertEquals("dailySetTotal phải cập nhật thành 3", 3, profile.dailySetTotal)
        assertEquals("dailySetProgress phải cập nhật thành 2", 2, profile.dailySetProgress)
        assertEquals("dailySetDone phải là false khi chưa hoàn thành 3/3", false, profile.dailySetDone)

        // Lần cập nhật 2: Đã hoàn thành 3/3
        val balance2 = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 1530,
            dailySetTotal = 3,
            dailySetProgress = 3,
            dailySetDone = true,
            isSuccess = true
        )
        profile.applyBalance(balance2, todayStr = "2026-10-25")
        assertEquals("dailySetProgress phải đạt 3", 3, profile.dailySetProgress)
        assertEquals("dailySetDone phải là true khi hoàn thành", true, profile.dailySetDone)
    }

    @Test
    fun testApplyBalanceReadProgressResetOnNewDayVsPreservedOnSameDay() {
        val profile = AccountProfile.createDefault("test_id", "user@msn.com").apply {
            lastActiveDate = "2026-10-24"
            readProgress = 30
            readMax = 30
        }

        // Ngày mới: todayStr = 2026-10-25 khác lastActiveDate 2026-10-24 -> wasSameDay = false
        val balanceNewDay = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 2000,
            readNewsProgress = 0,
            readNewsMax = 30,
            isSuccess = true
        )
        profile.applyBalance(balanceNewDay, todayStr = "2026-10-25")
        assertEquals("readProgress phải được reset về 0 khi sang ngày mới", 0, profile.readProgress)

        // Cập nhật lastActiveDate về ngày hôm nay
        profile.lastActiveDate = "2026-10-25"
        profile.readProgress = 20

        // Cùng ngày: nếu balance trả về readNewsProgress = 10 (thấp hơn hoặc glitch), phải giữ nguyên giá trị cao nhất 20
        val balanceGlitch = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 2010,
            readNewsProgress = 10,
            readNewsMax = 30,
            isSuccess = true
        )
        profile.applyBalance(balanceGlitch, todayStr = "2026-10-25")
        assertEquals("Cùng ngày, readProgress không được tụt lùi khi gặp dữ liệu thấp hơn", 20, profile.readProgress)

        // Cùng ngày: khi đọc thêm bài báo và đạt 30, phải tiến độ lên 30
        val balanceFull = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 2030,
            readNewsProgress = 30,
            readNewsMax = 30,
            isSuccess = true
        )
        profile.applyBalance(balanceFull, todayStr = "2026-10-25")
        assertEquals("Cùng ngày, readProgress cập nhật tăng lên 30", 30, profile.readProgress)
    }

    @Test
    fun testApplyBalanceLatchesPointsEarnedTodayWhenSwitchingFromFlyoutToSerp() {
        val profile = AccountProfile.createDefault("test_id", "user@msn.com")

        // Nguồn Flyout: điểm kiếm được hôm nay là 150
        val flyoutBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 5150,
            pointsEarnedToday = 150,
            source = "flyout_http",
            isSuccess = true
        )
        profile.applyBalance(flyoutBalance, todayStr = "2026-10-25")
        profile.lastActiveDate = "2026-10-25"
        assertEquals("pointsEarnedToday ban đầu từ Flyout phải là 150", 150, profile.pointsEarnedToday)

        // Nguồn SERP (#id_rc): SERP chỉ có availablePoints = 5153, pointsEarnedToday = 0
        val serpBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 5153,
            pointsEarnedToday = 0,
            source = "serp_id_rc",
            isSuccess = true
        )
        profile.applyBalance(serpBalance, todayStr = "2026-10-25")

        assertTrue(
            "pointsEarnedToday phải chốt chặn (latch) không bị rơi về 0 khi chuyển từ flyout sang serp",
            profile.pointsEarnedToday >= 150
        )
    }

    @Test
    fun testApplyBalanceSyncsPuzzleProgress() {
        val profile = AccountProfile.createDefault("test_id", "user@msn.com")
        assertEquals(0, profile.puzzleProgress)

        val balance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 3000,
            puzzlePiecesCurrent = 8,
            isSuccess = true
        )
        profile.applyBalance(balance, todayStr = "2026-10-25")
        assertEquals("puzzleProgress phải được đồng bộ chính xác từ balance (8 mảnh)", 8, profile.puzzleProgress)
    }

    // =========================================================================
    // 4. Description Passed into DailyTask & Quiz Detection with Description Keywords
    // =========================================================================

    @Test
    fun testDescriptionPassedIntoDailyTaskFromFlyoutJson() {
        val tzDefault = TimeZone.getDefault()
        val fmt = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply { timeZone = tzDefault }
        val todayKey = fmt.format(Calendar.getInstance(tzDefault).time)

        val taskDesc = "Kiểm tra kiến thức của bạn trong bài trắc nghiệm nhanh 3 câu hỏi"
        val json = JSONObject().apply {
            put("userStatus", JSONObject().apply {
                put("availablePoints", 1000)
                put("dailySetPromotions", JSONObject().apply {
                    put(todayKey, JSONArray().apply {
                        put(JSONObject().apply {
                            put("offerId", "daily_set_quiz_vn")
                            put("title", "Khám phá thế giới")
                            put("description", taskDesc)
                            put("destinationUrl", "https://www.bing.com/search?q=quiz_world")
                            put("pointProgressMax", 10)
                            put("pointProgress", 0)
                            put("complete", false)
                        })
                    })
                })
            })
        }

        val balance = RewardsBalanceExtractor.parseFlyoutJsonPayload(json)
        val task = balance.dailyTasks.find { it.id == "daily_set_quiz_vn" }
        assertNotNull("Nhiệm vụ daily set phải được parse thành công", task)
        assertEquals("Description phải được lưu vào DailyTask", taskDesc, task!!.description)
        assertEquals("taskType phải được nhận diện là quiz dựa vào từ khóa trong description", "quiz", task.taskType)
        assertTrue("isInteractiveDomTask phải trả về true cho task quiz", RewardsActivitiesEngine.isInteractiveDomTask(task))
    }

    @Test
    fun testQuizDetectionWithVariousDescriptionKeywords() {
        val testCases = listOf(
            Pair("Khám phá", "Tham gia câu đố thú vị") to "quiz",
            Pair("Vũ trụ bí ẩn", "Thử thách kiến thức khoa học") to "quiz",
            Pair("Văn hóa", "Đố vui về ẩm thực Việt Nam") to "quiz",
            Pair("Lịch sử", "Trắc nghiệm kiến thức lịch sử thế giới") to "quiz",
            Pair("Thủ đô thế giới", "Kiểm tra kiến thức địa lý") to "quiz",
            Pair("Cộng đồng", "Tham gia bình chọn điểm đến du lịch") to "poll",
            Pair("Ý kiến người dùng", "Khảo sát nhanh về xu hướng AI") to "poll",
            Pair("Tin vắn", "Đọc bài báo và tin tức hôm nay") to "news"
        )

        for ((input, expectedType) in testCases) {
            val (title, description) = input
            val detected = RewardsActivitiesEngine.detectTaskType(
                url = "https://www.bing.com/search",
                title = title,
                description = description
            )
            assertEquals("detectTaskType cho '$title' với description '$description' phải ra '$expectedType'", expectedType, detected)
        }
    }

    @Test
    fun testInteractiveDomTaskFlagWithDescriptionKeywordsFallback() {
        // Kịch bản khi taskType là 'urlreward' nhưng mô tả chứa từ khóa tương tác
        val task = DailyTask(
            id = "task_dom_fallback",
            title = "Điểm đến kỳ thú",
            pointValue = 10,
            destinationUrl = "https://www.bing.com/travel",
            taskType = "urlreward",
            isCompleted = false,
            description = "Tham gia trả lời câu đố và trắc nghiệm để nhận quà"
        )

        val isInteractive = RewardsActivitiesEngine.isInteractiveDomTask(task)
        assertTrue(
            "isInteractiveDomTask phải nhận diện được từ khóa 'trắc nghiệm' / 'câu đố' trong description ngay cả khi taskType là urlreward",
            isInteractive
        )
    }
}
