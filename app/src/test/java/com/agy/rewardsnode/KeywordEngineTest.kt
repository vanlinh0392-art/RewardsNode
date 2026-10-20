package com.agy.rewardsnode

import com.agy.rewardsnode.core.KeywordEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

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
}
