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
}
