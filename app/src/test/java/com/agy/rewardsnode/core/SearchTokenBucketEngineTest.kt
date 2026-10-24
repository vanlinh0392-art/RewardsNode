package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchTokenBucketEngineTest {

    private lateinit var account: AccountProfile

    @Before
    fun setUp() {
        account = AccountProfile(
            id = "test-acc-01",
            email = "test01@gmail.com",
            profileName = "Tester",
            level = 2,
            targetSearches = 55
        )
    }

    @Test
    fun testInitialAccountStateAllowsSearch() {
        val t0 = 1000000L
        val canSearch = SearchTokenBucketEngine.canExecuteSearch(account, t0)
        assertTrue("Lần đầu tiên khởi chạy phải cho phép tìm kiếm", canSearch)
        assertEquals("searchWindowStartMs phải được gán mốc t0", t0, account.searchWindowStartMs)
        assertEquals("searchesInWindow ban đầu phải bằng 0", 0, account.searchesInWindow)
    }

    @Test
    fun testWindowAllowsExactlyThreeSearches() {
        val t0 = 1000000L

        // Lượt 1
        assertTrue(SearchTokenBucketEngine.canExecuteSearch(account, t0))
        val count1 = SearchTokenBucketEngine.recordSearch(account, t0)
        assertEquals(1, count1)
        assertEquals(0L, SearchTokenBucketEngine.getRemainingCooldownMs(account, t0))

        // Lượt 2
        assertTrue(SearchTokenBucketEngine.canExecuteSearch(account, t0 + 15000L))
        val count2 = SearchTokenBucketEngine.recordSearch(account, t0 + 15000L)
        assertEquals(2, count2)
        assertEquals(0L, SearchTokenBucketEngine.getRemainingCooldownMs(account, t0 + 15000L))

        // Lượt 3
        assertTrue(SearchTokenBucketEngine.canExecuteSearch(account, t0 + 30000L))
        val count3 = SearchTokenBucketEngine.recordSearch(account, t0 + 30000L)
        assertEquals(3, count3)
        assertEquals(
            "Lượt 3 chạm trần phải chốt cooldownUntilTimestamp = t0 + 920s",
            t0 + SearchTokenBucketEngine.WINDOW_DURATION_MS,
            account.cooldownUntilTimestamp
        )

        // Lượt 4 (Cố gắng thực hiện trong cùng window) -> BỊ TỪ CHỐI!
        assertFalse(
            "Lượt 4 trong cùng window phải bị từ chối",
            SearchTokenBucketEngine.canExecuteSearch(account, t0 + 45000L)
        )
        val remaining = SearchTokenBucketEngine.getRemainingCooldownMs(account, t0 + 45000L)
        assertEquals(
            "Thời gian cooldown còn lại phải là 920s - 45s = 875s",
            SearchTokenBucketEngine.WINDOW_DURATION_MS - 45000L,
            remaining
        )
    }

    @Test
    fun testWindowResetsAfter920Seconds() {
        val t0 = 1000000L
        // Cày hết quota 3 lượt ở t0
        for (i in 1..3) {
            SearchTokenBucketEngine.recordSearch(account, t0 + (i * 10000L))
        }
        val windowStart = account.searchWindowStartMs
        assertEquals(t0 + 10000L, windowStart)
        assertFalse(SearchTokenBucketEngine.canExecuteSearch(account, windowStart + 50000L))

        // Thời điểm t1 sau 920s + 1ms tính từ windowStart (đã hết Cooldown)
        val t1 = windowStart + SearchTokenBucketEngine.WINDOW_DURATION_MS + 1L
        val canSearchAfterCooldown = SearchTokenBucketEngine.canExecuteSearch(account, t1)
        assertTrue("Sau khi hết 920s phải tự động reset window và cho phép tìm kiếm", canSearchAfterCooldown)
        assertEquals("Mốc windowStartMs mới phải là t1", t1, account.searchWindowStartMs)
        assertEquals("searchesInWindow phải được reset về 0", 0, account.searchesInWindow)
        assertEquals("cooldownUntilTimestamp phải được reset về 0", 0L, account.cooldownUntilTimestamp)
    }

    @Test
    fun testMultipleWindowsLifecycle() {
        var currentTime = 2000000L

        // Window 1
        for (i in 1..3) {
            assertTrue(SearchTokenBucketEngine.canExecuteSearch(account, currentTime))
            SearchTokenBucketEngine.recordSearch(account, currentTime)
            currentTime += 16000L
        }
        assertFalse("Hết quota window 1", SearchTokenBucketEngine.canExecuteSearch(account, currentTime))

        // Bước sang Window 2 (nhảy vọt qua 920s)
        currentTime = account.searchWindowStartMs + SearchTokenBucketEngine.WINDOW_DURATION_MS + 1000L
        for (i in 1..3) {
            assertTrue("Window 2 - Lượt $i", SearchTokenBucketEngine.canExecuteSearch(account, currentTime))
            SearchTokenBucketEngine.recordSearch(account, currentTime)
            currentTime += 16000L
        }
        assertFalse("Hết quota window 2", SearchTokenBucketEngine.canExecuteSearch(account, currentTime))
    }
}
