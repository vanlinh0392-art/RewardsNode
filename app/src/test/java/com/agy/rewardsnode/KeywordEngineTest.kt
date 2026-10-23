package com.agy.rewardsnode

import com.agy.rewardsnode.core.KeywordEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class KeywordEngineTest {

    private lateinit var keywordEngine: KeywordEngine

    @Before
    fun setUp() {
        keywordEngine = KeywordEngine()
    }

    @Test
    fun testGenerateKeywordNotEmpty() {
        val keyword = keywordEngine.generateNextKeyword()
        assertNotNull(keyword)
        assertTrue(keyword.isNotBlank())
        assertTrue("Từ khóa phải chứa ít nhất 2 từ", keyword.split(" ").size >= 2)
    }

    @Test
    fun testRingBufferDeduplication() {
        val generatedKeywords = mutableSetOf<String>()
        val count = 200

        for (i in 1..count) {
            val kw = keywordEngine.generateNextKeyword()
            assertFalse("Từ khóa không được trùng lặp trong đợt sinh: $kw", generatedKeywords.contains(kw))
            generatedKeywords.add(kw)
        }

        assertEquals(count, generatedKeywords.size)
        assertTrue(keywordEngine.getHistoryCount() >= count)
    }

    @Test
    fun testSemanticPurity() {
        for (i in 1..50) {
            val kw = keywordEngine.generateNextKeyword()
            // Không được chứa các ký tự lạ hoặc thẻ html
            assertFalse(kw.contains("<"))
            assertFalse(kw.contains(">"))
            assertFalse(kw.contains("http"))
        }
    }

    @Test
    fun testGenerateKeywordVietnameseLocale() {
        val generatedKeywords = mutableSetOf<String>()
        for (i in 1..50) {
            val kw = keywordEngine.generateNextKeyword("VN")
            assertNotNull(kw)
            assertTrue("Từ khóa VN phải có ít nhất 2 từ", kw.split(" ").size >= 2)
            assertFalse(kw.contains("<"))
            assertFalse(kw.contains(">"))
            assertFalse(kw.contains("http"))
            generatedKeywords.add(kw)
        }
        assertEquals(50, generatedKeywords.size)
    }

    @Test
    fun testGenerateKeywordEnglishLocale() {
        val generatedKeywords = mutableSetOf<String>()
        for (i in 1..50) {
            val kw = keywordEngine.generateNextKeyword("US")
            assertNotNull(kw)
            assertTrue("Từ khóa US phải có ít nhất 2 từ", kw.split(" ").size >= 2)
            assertFalse(kw.contains("<"))
            assertFalse(kw.contains(">"))
            assertFalse(kw.contains("http"))
            generatedKeywords.add(kw)
        }
        assertEquals(50, generatedKeywords.size)
    }

    @Test
    fun testParseTrendsXml() {
        val mockRssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Daily Search Trends</title>
                <description>Recent searches</description>
                <item>
                  <title>Champions League Final</title>
                  <description>Match summary</description>
                </item>
                <item>
                  <title><![CDATA[Nvidia &amp; AI Revolution]]></title>
                  <description>Tech trends</description>
                </item>
                <item>
                  <title>Taylor&#39;s New Album</title>
                </item>
                <item>
                  <title><![CDATA[<span>Bitcoin Halving</span>]]></title>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = keywordEngine.parseTrendsXml(mockRssXml)
        assertEquals(4, parsed.size)
        // Channel title "Daily Search Trends" should NOT be included
        assertFalse(parsed.contains("Daily Search Trends"))

        assertEquals("Champions League Final", parsed[0])
        assertEquals("Nvidia & AI Revolution", parsed[1])
        assertEquals("Taylor's New Album", parsed[2])
        assertEquals("Bitcoin Halving", parsed[3])
    }

    @Test
    fun testTrendsCachePriorityAndFormatting() {
        val mockTrends = listOf("Bitcoin", "James Webb Deep Field", "Nvidia Stock")
        keywordEngine.setTrendsCache("US", mockTrends)

        val retrievedTrends = keywordEngine.getCachedTrends("US")
        assertEquals(3, retrievedTrends.size)

        // Lấy từ khóa ưu tiên từ Trends cache
        val kw1 = keywordEngine.generateNextKeyword("US")
        assertTrue(kw1.isNotBlank())
        // Từ khóa đơn từ "Bitcoin" phải được định dạng tự nhiên thành ít nhất 2 từ
        assertTrue("Từ khóa phải chứa ít nhất 2 từ", kw1.split(" ").size >= 2)
    }

    @Test
    fun testTrendsCacheExpiration() {
        val mockTrends = listOf("Trending Topic A", "Trending Topic B")
        val now = System.currentTimeMillis()
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

        // Cache mới tinh -> hợp lệ
        val validCache = KeywordEngine.TrendsCache(mockTrends, now, currentDay)
        assertTrue(keywordEngine.isCacheValid(validCache))

        // Cache đã quá 6 giờ -> hết hạn
        val expiredByTimeCache = KeywordEngine.TrendsCache(mockTrends, now - (7 * 3600 * 1000L), currentDay)
        assertFalse(keywordEngine.isCacheValid(expiredByTimeCache))

        // Cache khác ngày -> hết hạn
        val expiredByDayCache = KeywordEngine.TrendsCache(mockTrends, now, currentDay - 1)
        assertFalse(keywordEngine.isCacheValid(expiredByDayCache))

        // Cache rỗng -> không hợp lệ
        val emptyCache = KeywordEngine.TrendsCache(emptyList(), now, currentDay)
        assertFalse(keywordEngine.isCacheValid(emptyCache))
    }

    @Test
    fun testFetchTrendsAsyncDoesNotCrash() {
        runBlocking {
        // Kiểm tra gọi hàm async không gây crash hoặc ném exception khi offline
        val job = keywordEngine.fetchTrendsAsync("US")
        assertNotNull(job)
        job?.join()
        }
    }
}
