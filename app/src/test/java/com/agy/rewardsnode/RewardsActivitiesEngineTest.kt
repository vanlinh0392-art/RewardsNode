package com.agy.rewardsnode

import com.agy.rewardsnode.core.DailyTask
import com.agy.rewardsnode.core.RewardsActivitiesEngine
import org.junit.Assert.*
import org.junit.Test

class RewardsActivitiesEngineTest {

    @Test
    fun testScriptsAreIIFE() {
        val scripts = listOf(
            RewardsActivitiesEngine.createReadyToClaimScript(),
            RewardsActivitiesEngine.createDailySetExtractorScript(),
            RewardsActivitiesEngine.createCardClickScript("token_test", "Global_DailySet_1"),
            RewardsActivitiesEngine.createPollSolverScript(),
            RewardsActivitiesEngine.createQuizSolverScript(),
            RewardsActivitiesEngine.createStreakProtectionScript()
        )

        for (script in scripts) {
            assertTrue(script.isNotBlank())
            assertTrue("Script must start with IIFE syntax", script.trim().startsWith("(function()"))
            assertTrue("Script must end with IIFE invocation", script.trim().endsWith(")();"))
        }
    }

    @Test
    fun testParseBoolean() {
        assertTrue(RewardsActivitiesEngine.parseBoolean("true"))
        assertTrue(RewardsActivitiesEngine.parseBoolean("\"true\""))
        assertTrue(RewardsActivitiesEngine.parseBoolean("TRUE"))
        assertTrue(RewardsActivitiesEngine.parseBoolean("1"))
        assertTrue(RewardsActivitiesEngine.parseBoolean("\"1\""))

        assertFalse(RewardsActivitiesEngine.parseBoolean("false"))
        assertFalse(RewardsActivitiesEngine.parseBoolean("\"false\""))
        assertFalse(RewardsActivitiesEngine.parseBoolean("0"))
        assertFalse(RewardsActivitiesEngine.parseBoolean(null))
        assertFalse(RewardsActivitiesEngine.parseBoolean(""))
        assertFalse(RewardsActivitiesEngine.parseBoolean("null"))
        assertFalse(RewardsActivitiesEngine.parseBoolean("\"null\""))
    }

    @Test
    fun testParseDailyTasks() {
        val rawJson = """
            [
                {
                    "id": "Global_DailySet_20261021_Child1",
                    "title": "Prague Bohemian Autumn",
                    "pointValue": 10,
                    "destinationUrl": "https://www.bing.com/search?q=Trip+to+Prague",
                    "taskType": "quiz",
                    "isCompleted": false
                },
                {
                    "id": "Global_DailySet_20261021_Child2",
                    "title": "Ocean Currents",
                    "pointValue": 10,
                    "destinationUrl": "https://www.bing.com/search?q=Ocean",
                    "taskType": "urlreward",
                    "isCompleted": true
                }
            ]
        """.trimIndent()

        val tasks = RewardsActivitiesEngine.parseDailyTasks(rawJson)
        assertEquals(2, tasks.size)

        val t1 = tasks[0]
        assertEquals("Global_DailySet_20261021_Child1", t1.id)
        assertEquals("Prague Bohemian Autumn", t1.title)
        assertEquals(10, t1.pointValue)
        assertEquals("https://www.bing.com/search?q=Trip+to+Prague", t1.destinationUrl)
        assertEquals("quiz", t1.taskType)
        assertFalse(t1.isCompleted)

        val t2 = tasks[1]
        assertEquals("Global_DailySet_20261021_Child2", t2.id)
        assertTrue(t2.isCompleted)
    }

    @Test
    fun testParseDailyTasksEscapedQuotes() {
        val escaped = "\"[{\\\"id\\\":\\\"task1\\\",\\\"title\\\":\\\"Daily Poll\\\",\\\"pointValue\\\":10,\\\"destinationUrl\\\":\\\"https://bing.com\\\",\\\"taskType\\\":\\\"poll\\\",\\\"isCompleted\\\":false}]\""
        val tasks = RewardsActivitiesEngine.parseDailyTasks(escaped)
        assertEquals(1, tasks.size)
        assertEquals("task1", tasks[0].id)
        assertEquals("Daily Poll", tasks[0].title)
        assertEquals("poll", tasks[0].taskType)
        assertFalse(tasks[0].isCompleted)
    }

    @Test
    fun testDailyTaskModel() {
        val task = DailyTask(
            id = "test_id",
            title = "Test Task",
            pointValue = 30,
            destinationUrl = "https://example.com",
            taskType = "poll",
            isCompleted = false
        )
        assertEquals("test_id", task.id)
        assertEquals("Test Task", task.title)
        assertEquals(30, task.pointValue)
        assertEquals("https://example.com", task.destinationUrl)
        assertEquals("poll", task.taskType)
        assertFalse(task.isCompleted)
    }

    @Test
    fun testCreateCardClickScript() {
        val script = RewardsActivitiesEngine.createCardClickScript("test_q", "offer_123")
        assertTrue(script.isNotBlank())
        assertTrue(script.trim().startsWith("(function()"))
        assertTrue(script.trim().endsWith(")();"))
        assertTrue(script.contains("PointerEvent"))
        assertTrue(script.contains("targetAnchor.setAttribute('target', '_self')") || script.contains("targetAnchor.target = '_self'"))
        assertTrue(script.contains("test_q"))
        assertTrue(script.contains("offer_123"))
    }

    @Test
    fun testParseDynamicDailyTasksCountMoreThan3() {
        val rawJson = """
            [
                {"id":"task1","title":"Task 1","pointValue":10,"destinationUrl":"https://bing.com/1","taskType":"quiz","isCompleted":true},
                {"id":"task2","title":"Task 2","pointValue":10,"destinationUrl":"https://bing.com/2","taskType":"poll","isCompleted":true},
                {"id":"task3","title":"Task 3","pointValue":10,"destinationUrl":"https://bing.com/3","taskType":"urlreward","isCompleted":false},
                {"id":"task4","title":"Task 4","pointValue":10,"destinationUrl":"https://bing.com/4","taskType":"urlreward","isCompleted":false},
                {"id":"task5","title":"Task 5","pointValue":10,"destinationUrl":"https://bing.com/5","taskType":"urlreward","isCompleted":false}
            ]
        """.trimIndent()

        val tasks = RewardsActivitiesEngine.parseDailyTasks(rawJson)
        assertEquals("Must parse all 5 tasks without hardcoded limit of 3", 5, tasks.size)
        assertEquals("task1", tasks[0].id)
        assertEquals("task5", tasks[4].id)
        assertEquals(2, tasks.count { it.isCompleted })
        assertEquals(3, tasks.count { !it.isCompleted })
    }

    @Test
    fun testParseDailyTasksFromNextDataJsonObject() {
        val nextDataJson = """
            {
                "props": {
                    "pageProps": {
                        "dailySetPromotions": {
                            "10/22/2026": [
                                {
                                    "offerId": "ds_1",
                                    "title": "Daily Set Quiz",
                                    "pointProgressMax": 10,
                                    "pointProgress": 10,
                                    "destinationUrl": "https://www.bing.com/quiz",
                                    "complete": true
                                },
                                {
                                    "offerId": "ds_2",
                                    "title": "Daily Set Poll",
                                    "pointProgressMax": 10,
                                    "pointProgress": 0,
                                    "destinationUrl": "https://www.bing.com/poll",
                                    "complete": false
                                }
                            ]
                        },
                        "morePromotions": [
                            {
                                "offerId": "more_1",
                                "title": "Explore Paris",
                                "pointProgressMax": 5,
                                "pointProgress": 0,
                                "destinationUrl": "https://www.bing.com/paris",
                                "complete": false
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val tasks = RewardsActivitiesEngine.parseDailyTasks(nextDataJson)
        assertEquals(3, tasks.size)
        assertEquals("ds_1", tasks[0].id)
        assertEquals("Daily Set Quiz", tasks[0].title)
        assertEquals("quiz", tasks[0].taskType)
        assertTrue(tasks[0].isCompleted)

        assertEquals("ds_2", tasks[1].id)
        assertEquals("poll", tasks[1].taskType)
        assertFalse(tasks[1].isCompleted)

        assertEquals("more_1", tasks[2].id)
        assertEquals("urlreward", tasks[2].taskType)
        assertFalse(tasks[2].isCompleted)
    }

    @Test
    fun testParseDailyTasksFromJsonWithEscapedQuotes() {
        val nextDataJson = "{\"props\":{\"pageProps\":{\"dailySetPromotions\":{\"20261022\":[{\"offerId\":\"esc_1\",\"title\":\"Escaped Task\",\"pointProgressMax\":10,\"complete\":false,\"destinationUrl\":\"https://bing.com\"}]}}}}"
        val escaped = "\"${nextDataJson.replace("\"", "\\\"")}\""

        val tasks = RewardsActivitiesEngine.parseDailyTasks(escaped)
        assertEquals(1, tasks.size)
        assertEquals("esc_1", tasks[0].id)
        assertEquals("Escaped Task", tasks[0].title)
        assertFalse(tasks[0].isCompleted)
    }

    @Test
    fun testIsInteractiveDomTask() {
        val urlRewardTask = DailyTask(
            id = "ds_url",
            title = "Khám phá địa danh",
            pointValue = 10,
            destinationUrl = "https://www.bing.com/search?q=Vietnam",
            taskType = "urlreward",
            isCompleted = false
        )
        assertFalse("urlreward must NOT require DOM interaction", RewardsActivitiesEngine.isInteractiveDomTask(urlRewardTask))

        val newsTask = DailyTask(
            id = "ds_news",
            title = "Tin tức buổi sáng",
            pointValue = 10,
            destinationUrl = "https://www.bing.com/news",
            taskType = "news",
            isCompleted = false
        )
        assertFalse("news task must NOT require DOM interaction", RewardsActivitiesEngine.isInteractiveDomTask(newsTask))

        val quizTask = DailyTask(
            id = "ds_quiz",
            title = "Thử thách trắc nghiệm",
            pointValue = 10,
            destinationUrl = "https://www.bing.com/search?q=Quiz&form=dsetqu",
            taskType = "quiz",
            isCompleted = false
        )
        assertTrue("quiz task MUST require DOM interaction", RewardsActivitiesEngine.isInteractiveDomTask(quizTask))

        val pollTask = DailyTask(
            id = "ds_poll",
            title = "Bình chọn hàng ngày",
            pointValue = 10,
            destinationUrl = "https://www.bing.com/search?q=Poll&btoption=1",
            taskType = "poll",
            isCompleted = false
        )
        assertTrue("poll task MUST require DOM interaction", RewardsActivitiesEngine.isInteractiveDomTask(pollTask))
    }

    @Test
    fun testDailySetPipelineErrorIsolation() = kotlinx.coroutines.runBlocking {
        // 3 Daily Set tasks: 1 completed, 1 urlreward with blank url (fails gracefully), 1 completed
        val tasks = listOf(
            DailyTask("ds_1", "Thẻ 1 (Completed)", 10, "https://example.com/1", "urlreward", isCompleted = true),
            DailyTask("ds_2", "Thẻ 2 (Blank URL)", 10, "", "urlreward", isCompleted = false),
            DailyTask("ds_3", "Thẻ 3 (Completed)", 10, "https://example.com/3", "quiz", isCompleted = true)
        )

        val results = RewardsActivitiesEngine.executeDailySetPipelineSafe(
            tasks = tasks,
            cookiesJson = "",
            delayBetweenCardsMs = 0L
        )

        assertEquals(3, results.size)
        assertTrue("Completed task 1 must remain true", results["ds_1"] == true)
        assertFalse("Blank task 2 must result in false without throwing", results["ds_2"] == true)
        assertTrue("Completed task 3 must still execute and return true (Error isolation)", results["ds_3"] == true)
    }

    @Test
    fun testExecuteSingleTaskHttpSafeOnBlankUrl() = kotlinx.coroutines.runBlocking {
        val task = DailyTask(
            id = "",
            title = "Blank Task",
            pointValue = 10,
            destinationUrl = "",
            taskType = "urlreward",
            isCompleted = false
        )
        val result = RewardsActivitiesEngine.executeSingleTaskHttp(task, cookiesJson = "")
        assertFalse("Should safely return false on blank URL without throwing exception", result)
    }

    @Test
    fun testDailyTaskDescriptionExtraction() {
        val jsonArray = """
            [
                {
                    "id": "task_desc_1",
                    "title": "Kiểm tra kiến thức",
                    "description": "Tham gia bài đố vui để nhận 30 điểm",
                    "points": 30,
                    "destination": "https://www.bing.com/search?q=test",
                    "complete": false
                },
                {
                    "id": "task_desc_2",
                    "title": "Khảo sát tuần",
                    "desc": "Bình chọn chủ đề tuần này",
                    "pointProgressMax": 10,
                    "destinationUrl": "https://www.bing.com/search?q=poll",
                    "complete": false
                }
            ]
        """.trimIndent()

        val tasksFromArray = RewardsActivitiesEngine.parseDailyTasksFromArray(jsonArray)
        assertEquals(2, tasksFromArray.size)
        assertEquals("Tham gia bài đố vui để nhận 30 điểm", tasksFromArray[0].description)
        assertEquals("Bình chọn chủ đề tuần này", tasksFromArray[1].description)
        assertTrue(RewardsActivitiesEngine.isInteractiveDomTask(tasksFromArray[0]))
        assertTrue(RewardsActivitiesEngine.isInteractiveDomTask(tasksFromArray[1]))

        val jsonObject = """
            {
                "dailySetPromotions": {
                    "today": [
                        {
                            "offerId": "ds_obj_1",
                            "title": "Thủ đô các nước",
                            "description": "Câu đố về các thủ đô trên thế giới",
                            "points": 10,
                            "destinationUrl": "https://www.bing.com/search?q=quiz",
                            "complete": false
                        }
                    ]
                },
                "morePromotions": [
                    {
                        "id": "more_promo_1",
                        "title": "Thử thách trắc nghiệm",
                        "desc": "Trả lời câu đố hàng tuần",
                        "points": 10,
                        "destination": "https://www.bing.com/search?q=more",
                        "complete": false
                    }
                ]
            }
        """.trimIndent()

        val tasksFromObj = RewardsActivitiesEngine.parseDailyTasksFromJson(jsonObject)
        assertEquals(2, tasksFromObj.size)
        assertEquals("Câu đố về các thủ đô trên thế giới", tasksFromObj[0].description)
        assertEquals("Trả lời câu đố hàng tuần", tasksFromObj[1].description)
        assertTrue(RewardsActivitiesEngine.isInteractiveDomTask(tasksFromObj[0]))
        assertTrue(RewardsActivitiesEngine.isInteractiveDomTask(tasksFromObj[1]))
    }

    @Test
    fun testIsInteractiveDomTaskVietnameseKeywords() {
        val keywords = listOf(
            "trắc nghiệm", "đố vui", "câu đố", "thử thách", "thủ đô", "kiểm tra kiến thức",
            "quiz", "trivia", "poll", "khảo sát", "bình chọn", "form=dsetqu", "btoption"
        )

        for (kw in keywords) {
            val taskInTitle = DailyTask(
                id = "task_t",
                title = "Nhiệm vụ $kw hôm nay",
                pointValue = 10,
                destinationUrl = "https://www.bing.com/search",
                taskType = "urlreward",
                isCompleted = false
            )
            assertTrue("Should detect interactive DOM task for keyword '$kw' in title", RewardsActivitiesEngine.isInteractiveDomTask(taskInTitle))

            val taskInDesc = DailyTask(
                id = "task_d",
                title = "Nhiệm vụ thường",
                description = "Chi tiết về $kw tại đây",
                pointValue = 10,
                destinationUrl = "https://www.bing.com/search",
                taskType = "urlreward",
                isCompleted = false
            )
            assertTrue("Should detect interactive DOM task for keyword '$kw' in description", RewardsActivitiesEngine.isInteractiveDomTask(taskInDesc))

            val taskInUrl = DailyTask(
                id = "task_u",
                title = "Nhiệm vụ URL",
                description = "Chi tiết",
                pointValue = 10,
                destinationUrl = "https://www.bing.com/search?query=$kw",
                taskType = "urlreward",
                isCompleted = false
            )
            assertTrue("Should detect interactive DOM task for keyword '$kw' in url", RewardsActivitiesEngine.isInteractiveDomTask(taskInUrl))
        }

        val pureUrlTask = DailyTask(
            id = "task_pure",
            title = "Khám phá phong cảnh",
            description = "Xem hình ảnh thiên nhiên đẹp",
            pointValue = 10,
            destinationUrl = "https://www.bing.com/search?q=nature",
            taskType = "urlreward",
            isCompleted = false
        )
        assertFalse("Pure urlreward without keywords must NOT require DOM interaction", RewardsActivitiesEngine.isInteractiveDomTask(pureUrlTask))
    }

    @Test
    fun testCreateQuizSolverScriptVietnameseSelectors() {
        val script = RewardsActivitiesEngine.createQuizSolverScript()
        assertTrue(script.contains("input[value*=\"Bắt đầu\" i]"))
        assertTrue(script.contains("input[value*=\"Làm bài\" i]"))
        assertFalse("BUG-11: Không được chứa invalid jQuery selector button:contains('Bắt đầu')", script.contains("button:contains('Bắt đầu')"))
        assertTrue(script.contains("btnTxt.indexOf('bắt đầu')"))
        assertTrue(script.contains(".bt_option"))
        assertTrue(script.contains(".wk_ans"))
        assertTrue(script.contains("input[value*=\"Tiếp\" i]"))
        assertTrue(script.contains("button[aria-label*=\"Tiếp\" i]"))
    }
}
