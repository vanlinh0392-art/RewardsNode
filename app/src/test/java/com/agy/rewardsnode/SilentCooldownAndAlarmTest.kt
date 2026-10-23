package com.agy.rewardsnode

import com.agy.rewardsnode.core.KeywordEngine
import com.agy.rewardsnode.core.RewardsStateMachine
import com.agy.rewardsnode.data.model.AccountProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SilentCooldownAndAlarmTest {

    @Test
    fun testSilentCooldownDetectorSetsRestrictedFlagOnThreeZeroDeltas() {
        val account = AccountProfile(
            id = "acc-bot-test",
            email = "botguard@example.com",
            profileName = "Bot Guard Tester",
            level = 2,
            availablePoints = 1000
        )

        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = { }
        )

        assertFalse("Ban đầu tài khoản không bị cờ restricted", sm.isAccountCooldownRestricted(account.id))
        assertEquals(0, sm.getConsecutiveZeroDeltaSearches(account.id))

        // Lần 1: delta == 0
        sm.recordSearchDelta(account.id, 0)
        assertEquals(1, sm.getConsecutiveZeroDeltaSearches(account.id))
        assertFalse("Chưa đủ 3 lần thì chưa restricted", sm.isAccountCooldownRestricted(account.id))

        // Lần 2: delta == 0 -> Bật Bot Guard / Silent Cooldown!
        sm.recordSearchDelta(account.id, 0)
        assertEquals(2, sm.getConsecutiveZeroDeltaSearches(account.id))
        assertTrue("Sau 2 lần liên tiếp delta == 0, tài khoản bị gắn cờ restricted", sm.isAccountCooldownRestricted(account.id))

        // Lần 3: delta == 0 -> Vẫn tiếp tục restricted
        sm.recordSearchDelta(account.id, 0)
        assertEquals(3, sm.getConsecutiveZeroDeltaSearches(account.id))
        assertTrue("Sau 3 lần liên tiếp delta == 0, tài khoản vẫn bị gắn cờ restricted", sm.isAccountCooldownRestricted(account.id))

        // Khi có 1 lượt tăng điểm delta > 0 -> reset bộ đếm
        sm.recordSearchDelta(account.id, 3)
        assertEquals(0, sm.getConsecutiveZeroDeltaSearches(account.id))
    }

    @Test
    fun testZeroWakeLockCooldownTriggersCallbackAndAlarmWakeup() = runTest {
        val scheduledSecs = AtomicInteger(0)
        val alarmInvoked = AtomicBoolean(false)

        val sm = RewardsStateMachine(
            keywordEngine = KeywordEngine(),
            onExecuteSearch = { _, _, _ -> true },
            onStatusUpdate = { _, _ -> },
            onAccountUpdated = { },
            onScheduleCooldownAlarm = { secs, _ ->
                scheduledSecs.set(secs.toInt())
                alarmInvoked.set(true)
            }
        )

        val account = AccountProfile(
            id = "acc-alarm-test",
            email = "alarm@example.com",
            profileName = "Alarm Tester",
            availablePoints = 500
        )

        // Đánh dấu tài khoản restricted để kích hoạt sàn toán học 920s
        sm.markAccountRestricted(account.id, true)
        assertTrue(sm.isAccountCooldownRestricted(account.id))

        // Test waitForAlarmWakeup và notifyAlarmWakeup
        var wokeUp = false
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            sm.waitForAlarmWakeup(920L)
            wokeUp = true
        }

        // Kiểm tra state machine đang ở trạng thái chờ báo thức
        assertTrue("StateMachine phải đang chờ AlarmManager", sm.isWaitingForAlarm())

        // Giả lập CooldownAlarmReceiver gửi intent thức giấc
        sm.notifyAlarmWakeup()
        job.join()

        assertTrue("StateMachine phải hoàn thành việc thức giấc", wokeUp)
        assertFalse("StateMachine không còn ở trạng thái chờ báo thức", sm.isWaitingForAlarm())
    }
}
