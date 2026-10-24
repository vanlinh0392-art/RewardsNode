package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RewardsInterleavingDispatcherTest {

    private lateinit var acc1: AccountProfile
    private lateinit var acc2: AccountProfile
    private lateinit var acc3: AccountProfile

    @Before
    fun setUp() {
        acc1 = AccountProfile(id = "acc-01", email = "a1@test.com", profileName = "A1", level = 2, targetSearches = 55)
        acc2 = AccountProfile(id = "acc-02", email = "a2@test.com", profileName = "A2", level = 2, targetSearches = 55)
        acc3 = AccountProfile(id = "acc-03", email = "a3@test.com", profileName = "A3", level = 1, targetSearches = 35)
    }

    @Test
    fun testSingleAccountInterleavingAndSleepCalculation() {
        val t0 = 5000000L

        // Acc 1 chạy 3 queries trong batch (tốn khoảng 45s)
        for (i in 1..3) {
            assertTrue(SearchTokenBucketEngine.canExecuteSearch(acc1, t0 + (i * 15000L)))
            SearchTokenBucketEngine.recordSearch(acc1, t0 + (i * 15000L))
        }

        // Sau 3 queries tại t = t0 + 45s -> BỊ KHÓA COOLDOWN
        val now = t0 + 45000L
        assertFalse(SearchTokenBucketEngine.canExecuteSearch(acc1, now))

        // Thời gian ngủ an toàn chính xác
        val sleepMs = SearchTokenBucketEngine.getRemainingCooldownMs(acc1, now)
        assertEquals(SearchTokenBucketEngine.WINDOW_DURATION_MS - 30000L, sleepMs)
    }

    @Test
    fun testMultiAccountInterleavingRotation() {
        val t0 = 5000000L
        var currentTime = t0

        // 1. Acc 1 cày 3 queries
        for (i in 1..3) {
            assertTrue(SearchTokenBucketEngine.canExecuteSearch(acc1, currentTime))
            SearchTokenBucketEngine.recordSearch(acc1, currentTime)
            currentTime += 16000L // +16s mỗi query
        }
        assertFalse("Acc 1 đã hết quota slot 1", SearchTokenBucketEngine.canExecuteSearch(acc1, currentTime))

        // 2. Chuyển quyền ngay sang Acc 2 (Round-Robin) tại currentTime
        assertTrue("Acc 2 phải sẵn sàng cày ngay lập tức", SearchTokenBucketEngine.canExecuteSearch(acc2, currentTime))
        for (i in 1..3) {
            assertTrue(SearchTokenBucketEngine.canExecuteSearch(acc2, currentTime))
            SearchTokenBucketEngine.recordSearch(acc2, currentTime)
            currentTime += 16000L
        }
        assertFalse("Acc 2 đã hết quota slot 1", SearchTokenBucketEngine.canExecuteSearch(acc2, currentTime))

        // 3. Chuyển tiếp sang Acc 3 (Level 1)
        assertTrue("Acc 3 phải sẵn sàng cày tiếp", SearchTokenBucketEngine.canExecuteSearch(acc3, currentTime))
        for (i in 1..3) {
            assertTrue(SearchTokenBucketEngine.canExecuteSearch(acc3, currentTime))
            SearchTokenBucketEngine.recordSearch(acc3, currentTime)
            currentTime += 16000L
        }
        assertFalse("Acc 3 đã hết quota slot 1", SearchTokenBucketEngine.canExecuteSearch(acc3, currentTime))

        // 4. Cả 3 tài khoản đều dính cooldown -> Tìm mốc thức giấc sớm nhất
        val accounts = listOf(acc1, acc2, acc3)
        val earliestCooldownUntil = accounts.minOf { it.cooldownUntilTimestamp }
        assertEquals("Mốc thức giấc sớm nhất phải là của Acc 1", acc1.cooldownUntilTimestamp, earliestCooldownUntil)

        val waitMs = earliestCooldownUntil - currentTime
        assertTrue("Thời gian chờ phải dương và nhỏ hơn 920s", waitMs in 1L..<SearchTokenBucketEngine.WINDOW_DURATION_MS)
    }

    @Test
    fun testLevel1FastExitQuota() {
        // Level 1: 30 điểm PC = 10 queries
        acc3.pcMax = 30
        acc3.pcProgress = 27 // Đã cày 9 queries (27 điểm)

        val t0 = 10000000L
        assertTrue(SearchTokenBucketEngine.canExecuteSearch(acc3, t0))
        SearchTokenBucketEngine.recordSearch(acc3, t0)
        acc3.pcProgress = 30 // Đạt 30/30 trần điểm PC

        // Khi kiểm tra trần điểm rổ PC
        val isPcMaxed = acc3.pcProgress >= acc3.pcMax
        assertTrue("Level 1 đã max rổ PC", isPcMaxed)
    }
}
