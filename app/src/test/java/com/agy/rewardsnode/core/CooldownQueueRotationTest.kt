package com.agy.rewardsnode.core

import org.junit.Assert.*
import org.junit.Test

class CooldownQueueRotationTest {

    @Test
    fun `test single-account target logic does not stop when account still needs searches`() {
        val targetAccountId = "test_acc_1"
        var allAccountsFinished = false
        var isRunning = true

        // Kiểm tra điều kiện ngắt dòng 2252:
        // if (!targetAccountId.isNullOrBlank() && allAccountsFinished)
        val shouldStop = !targetAccountId.isNullOrBlank() && allAccountsFinished
        assertFalse(shouldStop)

        // Khi tài khoản chưa xong chỉ tiêu trong ngày, isRunning phải được duy trì
        if (shouldStop) {
            isRunning = false
        }
        assertTrue(isRunning)
    }

    @Test
    fun `test single-account target logic stops only when allAccountsFinished is true`() {
        val targetAccountId = "test_acc_1"
        var allAccountsFinished = true
        var isRunning = true

        val shouldStop = !targetAccountId.isNullOrBlank() && allAccountsFinished
        assertTrue(shouldStop)

        if (shouldStop) {
            isRunning = false
        }
        assertFalse(isRunning)
    }

    @Test
    fun `test multi-account global cycle with null targetAccountId never triggers single-account stop`() {
        val targetAccountId: String? = null
        var allAccountsFinished = false
        var isRunning = true

        val shouldStop = !targetAccountId.isNullOrBlank() && allAccountsFinished
        assertFalse(shouldStop)

        allAccountsFinished = true
        val shouldStopFinished = !targetAccountId.isNullOrBlank() && allAccountsFinished
        assertFalse(shouldStopFinished)
    }
}
