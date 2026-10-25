package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit Test Suite kiểm thử & chứng minh hệ thống tự động hóa:
 * 1. Nhận diện nhiệm vụ Daily Set và Quiz trắc nghiệm bằng mô tả tiếng Việt.
 * 2. Phân loại chuẩn xác Interactive DOM Task (Quiz/Poll) vs URL-Reward thuần túy.
 * 3. Ngăn chặn Gate Skip sớm (đạt 30đ MSN nhưng chưa xong Daily Set -> Không được skip).
 * 4. Kiểm tra mã nhúng JS Quiz Solver chứa đầy đủ bộ Selector song ngữ vi-VN.
 */
class DailySetAndQuizSolverTest {

    private lateinit var stateMachine: RewardsStateMachine
    private val todayStr = "2026-10-24"

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
    // 1. Kiểm tra nhận diện thẻ Quiz trắc nghiệm tiếng Việt ("Thủ đô Croatia?")
    // =========================================================================

    @Test
    fun testQuizTaskWithVietnameseDescriptionIdentifiedAsInteractiveDomTask() {
        // Thẻ mô phỏng chính xác từ ảnh người dùng:
        // Tiêu đề: "Thủ đô Croatia?" (không có chữ quiz tiếng Anh)
        // Mô tả: "Kiểm tra kiến thức của bạn về Zagreb trong bài trắc nghiệm ngắn này"
        val title = "Thủ đô Croatia?"
        val description = "Kiểm tra kiến thức của bạn về Zagreb trong bài trắc nghiệm ngắn này"
        val destUrl = "https://www.bing.com/search?q=Zagreb+Croatia"

        val taskType = RewardsActivitiesEngine.detectTaskType(destUrl, title, description)
        assertEquals("Thẻ có mô tả 'trắc nghiệm'/'kiểm tra kiến thức' phải được phân loại là quiz", "quiz", taskType)

        val task = DailyTask(
            id = "daily_set_quiz_croatia",
            title = title,
            pointValue = 10,
            destinationUrl = destUrl,
            taskType = taskType,
            isCompleted = false,
            description = description
        )

        val isInteractive = RewardsActivitiesEngine.isInteractiveDomTask(task)
        assertTrue("Nhiệm vụ trắc nghiệm tiếng Việt phải là Interactive DOM Task để mở WebView giải đố", isInteractive)
    }

    @Test
    fun testUrlRewardTasksNotInteractive() {
        // 2 Thẻ còn lại từ ảnh người dùng (chỉ là đọc tin / xem trang):
        // 1. "Các sự kiện hài kịch sắp tới"
        // 2. "Vẻ đẹp mùa thu se lạnh của Seattle"
        val task1 = DailyTask(
            id = "daily_set_comedy",
            title = "Các sự kiện hài kịch sắp tới",
            pointValue = 10,
            destinationUrl = "https://www.bing.com/search?q=comedy+events",
            taskType = RewardsActivitiesEngine.detectTaskType(
                "https://www.bing.com/search?q=comedy+events",
                "Các sự kiện hài kịch sắp tới",
                "Khám phá các buổi biểu diễn hài kịch cuối tuần"
            ),
            isCompleted = false,
            description = "Khám phá các buổi biểu diễn hài kịch cuối tuần"
        )

        val task2 = DailyTask(
            id = "daily_set_seattle",
            title = "Vẻ đẹp mùa thu se lạnh của Seattle",
            pointValue = 10,
            destinationUrl = "https://www.bing.com/search?q=seattle+autumn",
            taskType = RewardsActivitiesEngine.detectTaskType(
                "https://www.bing.com/search?q=seattle+autumn",
                "Vẻ đẹp mùa thu se lạnh của Seattle",
                "Ngắm nhìn cảnh sắc lá phong tại thành phố ngọc bích"
            ),
            isCompleted = false,
            description = "Ngắm nhìn cảnh sắc lá phong tại thành phố ngọc bích"
        )

        assertEquals("urlreward", task1.taskType)
        assertFalse("Thẻ thông tin không phải interactive task", RewardsActivitiesEngine.isInteractiveDomTask(task1))

        assertEquals("urlreward", task2.taskType)
        assertFalse("Thẻ phong cảnh không phải interactive task", RewardsActivitiesEngine.isInteractiveDomTask(task2))
    }

    // =========================================================================
    // 2. Kiểm tra Gate 60/60 ngăn chặn Skip sớm khi chưa hoàn thành Daily Set
    // =========================================================================

    @Test
    fun testGate60Of60ReturnsFalseWhenReadDoneButDailySetIncomplete() {
        // Tình huống từ ảnh chụp: Đã đọc báo 30/30đ, nhưng Daily Set 3 thẻ x 10đ chưa làm
        val account = AccountProfile(
            id = "acc_gate_test",
            email = "user@example.com",
            profileName = "Test User",
            readProgress = 30,
            readMax = 30,
            dailySetDone = false,
            dailySetProgress = 0,
            dailySetTotal = 3,
            lastActiveDate = todayStr
        )

        val isDone = stateMachine.isBingApp60Of60Done(account, todayStr)
        assertFalse(
            "Khi Daily Set chưa xong (0/3), dù đã đọc báo 30/30đ thì Gate 60/60 vẫn phải là FALSE (không skip sớm)",
            isDone
        )
    }

    @Test
    fun testGate60Of60ReturnsTrueWhenBothDailySetAndReadAreComplete() {
        val account = AccountProfile(
            id = "acc_gate_done",
            email = "user_done@example.com",
            profileName = "Done User",
            readProgress = 30,
            readMax = 30,
            dailySetDone = true,
            dailySetProgress = 3,
            dailySetTotal = 3,
            lastActiveDate = todayStr
        )

        val isDone = stateMachine.isBingApp60Of60Done(account, todayStr)
        assertTrue("Khi cả Daily Set và Read to Earn đều hoàn tất -> Gate 60/60 phải là TRUE", isDone)
    }

    // =========================================================================
    // 3. Kiểm tra trích xuất DailyTask từ JSON payload có kèm trường description
    // =========================================================================

    @Test
    fun testDailySetExtractionFromJsonWithDescription() {
        val jsonStr = """
            {
                "dailySetPromotions": {
                    "2026-10-24": [
                        {
                            "name": "daily_set_1",
                            "title": "Thủ đô Croatia?",
                            "description": "Kiểm tra kiến thức của bạn về Zagreb trong bài trắc nghiệm ngắn này",
                            "pointProgress": 0,
                            "pointProgressMax": 10,
                            "destinationUrl": "https://www.bing.com/search?q=Zagreb",
                            "complete": false
                        },
                        {
                            "name": "daily_set_2",
                            "title": "Các sự kiện hài kịch sắp tới",
                            "description": "Xem danh sách nghệ sĩ",
                            "pointProgress": 0,
                            "pointProgressMax": 10,
                            "destinationUrl": "https://www.bing.com/search?q=comedy",
                            "complete": false
                        }
                    ]
                }
            }
        """.trimIndent()

        val tasks = RewardsActivitiesEngine.parseDailyTasksFromJson(jsonStr)

        assertEquals("Phải bóc tách được 2 thẻ Daily Set", 2, tasks.size)

        val quizTask = tasks.first { it.id == "daily_set_1" }
        assertEquals("Thủ đô Croatia?", quizTask.title)
        assertEquals("Kiểm tra kiến thức của bạn về Zagreb trong bài trắc nghiệm ngắn này", quizTask.description)
        assertEquals("quiz", quizTask.taskType)
        assertTrue("Task phải được đánh dấu là interactive", RewardsActivitiesEngine.isInteractiveDomTask(quizTask))

        val infoTask = tasks.first { it.id == "daily_set_2" }
        assertEquals("urlreward", infoTask.taskType)
        assertFalse("Task info không phải interactive", RewardsActivitiesEngine.isInteractiveDomTask(infoTask))
    }

    // =========================================================================
    // 4. Kiểm tra Script giải Quiz chứa Selector song ngữ tiếng Việt
    // =========================================================================

    @Test
    fun testQuizSolverScriptContainsVietnameseSelectors() {
        val script = RewardsActivitiesEngine.createQuizSolverScript()

        // 1. Selector nút bắt đầu tiếng Việt
        assertTrue("Script phải hỗ trợ nút 'Bắt đầu'", script.contains("Bắt đầu") || script.contains("B\\u1EAFt \\u0111\\u1EA7u"))
        assertTrue("Script phải hỗ trợ nút 'Làm bài'", script.contains("Làm bài") || script.contains("L\\u00E0m b\\u00E0i"))

        // 2. Selector đáp án
        assertTrue("Script phải chứa selector .bt_option", script.contains(".bt_option"))
        assertTrue("Script phải chứa selector .wk_ans", script.contains(".wk_ans"))

        // 3. Selector nút tiếp theo
        assertTrue("Script phải hỗ trợ nút 'Tiếp theo'", script.contains("Tiếp theo", ignoreCase = true) || script.contains("Ti\\u1EBFp theo", ignoreCase = true))
    }
}
