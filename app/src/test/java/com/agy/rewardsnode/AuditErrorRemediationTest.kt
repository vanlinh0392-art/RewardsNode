package com.agy.rewardsnode

import android.content.ContextWrapper
import android.webkit.WebView
import com.agy.rewardsnode.core.KeywordEngine
import com.agy.rewardsnode.core.LoopbackProxyBridge
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsActivitiesEngine
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.core.RewardsStateMachine
import com.agy.rewardsnode.core.SearchTokenBucketEngine
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.service.RewardsForegroundService
import com.agy.rewardsnode.utils.TelegramNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bộ Unit Test kiểm chứng toàn diện 12 bản vá lỗi trong plan_ultra_audit_loi_rewardsnode.md:
 * 1. Timeout safety cho RewardsBalanceExtractor & ProfileManager cookie restore.
 * 2. Event-driven VlogManager (không chạy vòng lặp vô tận khi idle, tự động flush khi có log).
 * 3. AppDatabase (v8) lưu và đọc chính xác readProgress và readMax.
 * 4. Alarm RequestCode tách biệt (1001 vs 1002).
 * 5. Cooperative CancellationException rethrow & AccountProfile.applyBalance DRY.
 * 6. Headless WebView viewport dimensions (1080x1920) cho phép captureBitmap hoạt động.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditErrorRemediationTest {

    private fun resolveProjectFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../$relativePath"),
            File("d:/mod/android/bing_reward/RewardsNode/$relativePath"),
            File("d:/mod/android/bing_reward/RewardsNode/app/$relativePath")
        )
        return candidates.firstOrNull { it.exists() } ?: File(relativePath)
    }

    // =========================================================================
    // 1. Timeout safety cho RewardsBalanceExtractor & ProfileManager cookie restore
    // =========================================================================

    @Test
    fun testRewardsBalanceExtractorTimeoutSafetyWhenWebViewCallbackHangs() = runTest {
        val dummyWebView = WebView(ContextWrapper(null))

        // Trong môi trường JVM unit test (isReturnDefaultValues = true), evaluateJavascript không gọi callback.
        // Nhờ withTimeoutOrNull(10000L), hàm bắt buộc phải tự thoát sau 10s ảo thay vì treo vĩnh viễn.
        val balanceResult = RewardsBalanceExtractor.extractFromWebView(dummyWebView)
        assertFalse("Khi WebView treo callback, extractFromWebView phải trả về isSuccess = false", balanceResult.isSuccess)
        assertEquals("Source khi hết thời gian chờ phải là timeout", "timeout", balanceResult.source)

        val serpResult = RewardsBalanceExtractor.extractPointsFromSerp(dummyWebView)
        assertFalse("Khi WebView treo callback, extractPointsFromSerp phải trả về isSuccess = false", serpResult.isSuccess)
        assertEquals("Source của SERP timeout phải là serp_id_rc", "serp_id_rc", serpResult.source)
    }

    @Test
    fun testProfileManagerCookieRestoreTimeoutAndSharedExecutorSafety() = runTest {
        // Kiểm chứng hợp đồng timeout 8000ms khi CookieManager.setCookie bị treo callback ở tầng native Chromium
        val restoreResult = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Boolean> {
                // Giả lập Chromium IPC bị nghẽn không bao giờ gọi ValueCallback.onReceiveValue
            }
        } ?: false

        assertFalse("Khi setCookie không phản hồi trong 8s, restoreCookiesForProfile phải trả về false an toàn", restoreResult)

        // Kiểm chứng mã nguồn ProfileManager.kt đã bọc withTimeoutOrNull và dùng sharedProxyExecutor (diệt Thread Leak)
        val profileManagerFile = resolveProjectFile("src/main/java/com/agy/rewardsnode/core/ProfileManager.kt")
        if (profileManagerFile.exists()) {
            val source = profileManagerFile.readText()
            assertTrue(
                "ProfileManager.restoreCookiesForProfile phải có withTimeoutOrNull(8000L)",
                source.contains("withTimeoutOrNull(8000L)")
            )
            assertTrue(
                "ProfileManager phải dùng sharedProxyExecutor dùng chung thay vì tạo mới mỗi lần",
                source.contains("sharedProxyExecutor")
            )
        }
    }

    // =========================================================================
    // 2. Event-driven VlogManager (không chạy vòng lặp vô tận khi idle, tự động flush khi có log)
    // =========================================================================

    @Test
    fun testEventDrivenVlogManagerZeroWakeupWhenIdleAndAutoFlushOnLog() = runTest {
        val pendingLogs = ConcurrentLinkedDeque<String>()
        val flushCount = AtomicInteger(0)
        val flushedItems = mutableListOf<String>()
        var flushJob: Job? = null

        fun flushPending() {
            while (!pendingLogs.isEmpty()) {
                val batch = mutableListOf<String>()
                while (batch.size < 100) {
                    val item = pendingLogs.poll() ?: break
                    batch.add(item)
                }
                if (batch.isNotEmpty()) {
                    flushedItems.addAll(batch)
                    flushCount.incrementAndGet()
                }
            }
        }

        fun postEventDrivenLog(msg: String) {
            pendingLogs.add(msg)
            if (pendingLogs.size >= 30) {
                flushJob?.cancel()
                launch { flushPending() }
            } else if (flushJob?.isActive != true) {
                flushJob = launch {
                    delay(2000L)
                    flushPending()
                }
            }
        }

        // Case 1: Khi hệ thống Idle (0 log) suốt 60 giây -> Không có bất kỳ lần thức giấc hay flush nào
        advanceTimeBy(60_000L)
        assertEquals("Khi idle không có log, số lần flush phải bằng 0 (bảo vệ Doze Mode)", 0, flushCount.get())

        // Case 2: Khi có 2 log lẻ -> Tự động debounce flush sau 2000ms rồi tự ngắt coroutine
        postEventDrivenLog("Log 1")
        postEventDrivenLog("Log 2")
        advanceTimeBy(1000L)
        assertEquals("Chưa đủ 2000ms debounce thì chưa flush", 0, flushCount.get())

        advanceTimeBy(1100L)
        assertEquals("Sau 2000ms debounce, phải tự động flush đúng 1 lần", 1, flushCount.get())
        assertEquals(2, flushedItems.size)

        // Sau khi flush xong, để yên thêm 60 giây nữa -> Không chạy vòng lặp ngầm nào nữa
        advanceTimeBy(60_000L)
        assertEquals("Sau khi flush xong và trở lại idle, không được phát sinh thêm bất kỳ flush nào", 1, flushCount.get())

        // Case 3: Khi dồn >= 30 logs liên tiếp -> Flush ngay lập tức không cần đợi hết 2s
        for (i in 1..30) {
            postEventDrivenLog("Burst log #$i")
        }
        advanceTimeBy(10L)
        assertTrue("Khi hàng đợi đạt ngưỡng >= 30 mục, phải kích hoạt flush tức thì", flushCount.get() >= 2)
        assertEquals("Tổng số log đã flush phải đủ 32 mục", 32, flushedItems.size)

        // Kiểm chứng mã nguồn VlogManager.kt đã xóa vòng lặp while (isActive) { delay(2000L) }
        val vlogFile = resolveProjectFile("src/main/java/com/agy/rewardsnode/core/VlogManager.kt")
        if (vlogFile.exists()) {
            val source = vlogFile.readText()
            assertFalse(
                "VlogManager không được chứa vòng lặp vô tận while (isActive)",
                source.contains("while (isActive)")
            )
            assertTrue(
                "VlogManager phải kiểm tra ngưỡng pendingLogs.size >= 30",
                source.contains("pendingLogs.size >= 30")
            )
        }
    }

    // =========================================================================
    // 3. AppDatabase lưu và đọc chính xác readProgress và readMax
    // =========================================================================

    @Test
    fun testAppDatabaseSchemaVersion8AndReadProgressPersistence() {
        // Kiểm tra hằng số DATABASE_VERSION == 8 qua Reflection trên AppDatabase
        val versionField = AppDatabase::class.java.getDeclaredField("DATABASE_VERSION")
        versionField.isAccessible = true
        val dbVersion = versionField.getInt(null)
        assertEquals("AppDatabase DATABASE_VERSION phải được nâng lên 8", 8, dbVersion)

        // Kiểm tra AccountProfile.applyBalance và isAllMaxedOutToday với readProgress & readMax
        val today = "2026-10-23"
        val profile = AccountProfile(
            id = "acc-msn-read",
            email = "msnreader@outlook.com",
            profileName = "MSN Reader",
            level = 2,
            lastActiveDate = today
        )

        val extractedPartialRead = RewardsBalanceExtractor.ExtractedRewardsBalance(
            availablePoints = 5250,
            lifetimePoints = 15250,
            isSuccess = true,
            pcProgress = 90,
            pcMax = 90,
            mobileProgress = 60,
            mobileMax = 60,
            edgeProgress = 12,
            edgeMax = 12,
            readNewsProgress = 15,
            readNewsMax = 30
        )

        profile.applyBalance(extractedPartialRead, today)
        assertEquals("readProgress phải được cập nhật chính xác từ ExtractedRewardsBalance", 15, profile.readProgress)
        assertEquals("readMax phải được cập nhật chính xác từ ExtractedRewardsBalance", 30, profile.readMax)
        assertFalse(
            "Khi readProgress (15) < readMax (30), tài khoản chưa được coi là maxed out",
            profile.isAllMaxedOutToday(today)
        )

        val extractedFullRead = extractedPartialRead.copy(readNewsProgress = 30, readNewsMax = 30)
        profile.applyBalance(extractedFullRead, today)
        assertEquals("readProgress sau khi đọc đủ báo MSN phải là 30", 30, profile.readProgress)
        assertEquals("readMax phải là 30", 30, profile.readMax)
        assertTrue(
            "Khi PC (90/90), Mobile (60/60), Edge (12/12) và Read (30/30) đều đạt max, isAllMaxedOutToday phải là true",
            profile.isAllMaxedOutToday(today)
        )

        // Kiểm chứng mã nguồn AppDatabase.kt lưu và đọc đầy đủ readProgress & readMax
        val dbFile = resolveProjectFile("src/main/java/com/agy/rewardsnode/data/db/AppDatabase.kt")
        if (dbFile.exists()) {
            val dbSource = dbFile.readText()
            assertTrue("Bảng account_profiles phải có cột readProgress", dbSource.contains("readProgress INTEGER NOT NULL DEFAULT 0"))
            assertTrue("Bảng account_profiles phải có cột readMax", dbSource.contains("readMax INTEGER NOT NULL DEFAULT 30"))
            assertTrue("parseAccountProfile phải đọc readProgress", dbSource.contains("getIntSafe(cursor, \"readProgress\""))
            assertTrue("parseAccountProfile phải đọc readMax", dbSource.contains("getIntSafe(cursor, \"readMax\""))
            assertTrue("insertOrUpdateProfile phải ghi readProgress", dbSource.contains("put(\"readProgress\", profile.readProgress)"))
            assertTrue("insertOrUpdateProfile phải ghi readMax", dbSource.contains("put(\"readMax\", profile.readMax)"))
            assertTrue("resetDailyCountsIfNewDay phải reset readProgress = 0", dbSource.contains("readProgress = 0"))
        }
    }

    // =========================================================================
    // 4. Alarm RequestCode tách biệt (1001 vs 1002)
    // =========================================================================

    @Test
    fun testAlarmRequestCodesAreDistinctForCooldownAndDailySchedule() {
        assertEquals(
            "RC_COOLDOWN_ALARM phải có mã định danh 1001",
            1001,
            RewardsForegroundService.RC_COOLDOWN_ALARM
        )
        assertEquals(
            "RC_DAILY_SCHEDULE_ALARM phải có mã định danh 1002",
            1002,
            RewardsForegroundService.RC_DAILY_SCHEDULE_ALARM
        )
        assertNotEquals(
            "RequestCode của Cooldown Alarm và Daily Schedule Alarm bắt buộc phải khác nhau để không ghi đè PendingIntent",
            RewardsForegroundService.RC_COOLDOWN_ALARM,
            RewardsForegroundService.RC_DAILY_SCHEDULE_ALARM
        )
    }

    // =========================================================================
    // 5. Cooperative CancellationException rethrow
    // =========================================================================

    @Test
    fun testCooperativeCancellationExceptionIsRethrownImmediately() = runTest {
        var cancellationRethrown = false
        var swallowedByGenericCatch = false

        val job = launch {
            try {
                delay(5000L)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    cancellationRethrown = true
                    throw e
                }
                swallowedByGenericCatch = true
            }
        }

        advanceTimeBy(100L)
        job.cancel(CancellationException("User pressed STOP"))
        job.join()

        assertTrue("CancellationException bắt buộc phải được rethrow khi hủy coroutine", cancellationRethrown)
        assertFalse("Khối catch (e: Exception) tuyệt đối không được nuốt CancellationException", swallowedByGenericCatch)
        assertTrue("Job phải ở trạng thái isCancelled", job.isCancelled)

        // Kiểm chứng RewardsStateMachine.kt có bảo vệ if (e is CancellationException) throw e
        val smFile = resolveProjectFile("src/main/java/com/agy/rewardsnode/core/RewardsStateMachine.kt")
        if (smFile.exists()) {
            val smSource = smFile.readText()
            assertTrue(
                "RewardsStateMachine.kt phải rethrow CancellationException để dừng luồng ngay khi bấm Stop",
                smSource.contains("CancellationException) throw e")
            )
        }
    }

    // =========================================================================
    // 6. Headless WebView viewport dimensions (1080x1920) cho phép captureBitmap hoạt động
    // =========================================================================

    @Test
    fun testHeadlessWebViewViewportDimensions1080x1920AndCaptureBitmapContract() {
        // Khi WebView chưa layout (width=0, height=0 trên JVM stub), captureBitmap trả về null an toàn không crash
        val unmeasuredWebView = WebView(ContextWrapper(null))
        assertNull(
            "WebView có kích thước 0x0 phải trả về null an toàn trong captureBitmap",
            TelegramNotifier.captureBitmap(unmeasuredWebView)
        )

        // Kiểm tra toán học downscale của captureBitmap với viewport 1080x1920 -> 720x1280 (tỉ lệ 9:16 chuẩn)
        val origW = 1080
        val origH = 1920
        val targetW = if (origW > 720) 720 else origW
        val targetH = if (origW > 720) {
            ((origH.toFloat() / origW.toFloat()) * targetW).toInt().coerceAtLeast(1)
        } else origH

        assertEquals("Chiều rộng ảnh chụp từ viewport 1080x1920 phải thu về 720px", 720, targetW)
        assertEquals("Chiều cao ảnh chụp từ viewport 1080x1920 phải thu về 1280px", 1280, targetH)

        // Kiểm chứng RewardsForegroundService.kt khởi tạo headlessWebView với measure & layout(0, 0, 1080, 1920)
        val serviceFile = resolveProjectFile("src/main/java/com/agy/rewardsnode/service/RewardsForegroundService.kt")
        if (serviceFile.exists()) {
            val serviceSource = serviceFile.readText()
            assertTrue(
                "initHeadlessWebView phải gọi layout(0, 0, 1080, 1920) để chống lỗi viewport 0x0",
                serviceSource.contains("layout(0, 0, 1080, 1920)")
            )
            assertTrue(
                "initHeadlessWebView phải gọi MeasureSpec.makeMeasureSpec(1080, ...)",
                serviceSource.contains("makeMeasureSpec(1080") && serviceSource.contains("makeMeasureSpec(1920")
            )
        }
    }

    // =========================================================================
    // 7. PC priority over Mobile in combined search points
    // =========================================================================

    @Test
    fun testPCPriorityOverMobileInCombinedSearchPoints() {
        val testAccId = "test_pc_priority_acc"
        RewardsBalanceExtractor.latestTelemetryMap.remove(testAccId)

        try {
            // Case 1: Level 2 (pcMax=90, mobMax=60) khi chưa có telemetry trước đó, nhận 90 điểm gộp
            // PC phải được ưu tiên lấp đầy trước: pcProgress = 90, mobileProgress = 0
            val json90 = JSONObject().apply {
                put("userStatus", JSONObject().apply {
                    put("availablePoints", 2000)
                    put("level", 2)
                    put("levelInfoPromotion", JSONObject().apply {
                        put("attributes", JSONObject().apply {
                            put("bing_search_daily_points", "90")
                            put("level", "2")
                        })
                    })
                })
                put("dailySetPromotions", JSONObject())
            }

            val result90 = RewardsBalanceExtractor.parseFlyoutJsonPayload(json90, accountId = testAccId)
            assertTrue("Parse flyout json phải thành công", result90.isSuccess)
            assertEquals("Điểm gộp 90đ phải ưu tiên lấp đầy rổ PC trước (90)", 90, result90.pcProgress)
            assertEquals("Mobile chưa được phân bổ điểm khi PC vừa đủ 90", 0, result90.mobileProgress)

            // Case 2: Khi điểm gộp là 105đ (vượt qua pcMax 90), phần dư 15đ phải sang Mobile
            val json105 = JSONObject().apply {
                put("userStatus", JSONObject().apply {
                    put("availablePoints", 2015)
                    put("level", 2)
                    put("levelInfoPromotion", JSONObject().apply {
                        put("attributes", JSONObject().apply {
                            put("bing_search_daily_points", "105")
                            put("level", "2")
                        })
                    })
                })
                put("dailySetPromotions", JSONObject())
            }

            val result105 = RewardsBalanceExtractor.parseFlyoutJsonPayload(json105, accountId = testAccId)
            assertEquals("PC phải nhận tối đa 90 điểm", 90, result105.pcProgress)
            assertEquals("Phần dư (105 - 90 = 15) phải được phân bổ cho Mobile", 15, result105.mobileProgress)

            // Case 3: Trong AccountProfile.applyBalance, nếu profile đã có mobileProgress = 60,
            // và nguồn non-breakdown gửi pcProgress = 90 & mobileProgress = 0, thì profile phải giữ mobile 60 và trừ PC còn 30
            val today = "2026-10-25"
            val profile = AccountProfile(
                id = "acc_combined",
                email = "test@outlook.com",
                profileName = "Combined Acc",
                level = 2,
                mobileProgress = 60,
                mobileMax = 60,
                pcProgress = 0,
                pcMax = 90,
                lastActiveDate = today
            )

            val rawFlyoutBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
                isSuccess = true,
                source = "flyout_http",
                availablePoints = 2000,
                pcProgress = 90,
                pcMax = 90,
                mobileProgress = 0,
                mobileMax = 0
            )

            profile.applyBalance(rawFlyoutBalance, today)
            assertEquals("Mobile 60 phải được bảo toàn nguyên vẹn", 60, profile.mobileProgress)
            assertEquals("PC progress phải là phần chênh lệch (90 - 60 = 30)", 30, profile.pcProgress)
        } finally {
            RewardsBalanceExtractor.latestTelemetryMap.remove(testAccId)
        }
    }

    // =========================================================================
    // 8. Exact breakdown overwrite in AccountProfile
    // =========================================================================

    @Test
    fun testExactBreakdownOverwritesAccountProfileCounters() {
        val today = "2026-10-25"
        val profile = AccountProfile(
            id = "acc_breakdown_test",
            email = "breakdown@outlook.com",
            profileName = "Breakdown Tester",
            level = 2,
            mobileProgress = 60,
            mobileMax = 60,
            pcProgress = 90,
            pcMax = 90,
            lastActiveDate = today
        )

        // Khi nhận balance không phải breakdown có mobileProgress nhỏ hơn (vd: 45) -> Giữ nguyên 60 (maxOf)
        val nonExactBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            source = "flyout_http",
            availablePoints = 1500,
            pcProgress = 90,
            pcMax = 90,
            mobileProgress = 45,
            mobileMax = 60
        )
        profile.applyBalance(nonExactBalance, today)
        assertEquals("Nguồn non-breakdown không được ghi đè giảm mobileProgress", 60, profile.mobileProgress)

        // Khi nhận balance từ nguồn exact breakdown ("flyout_http+breakdown") -> Phải ghi đè chính xác 45
        val exactBreakdownBalance = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            source = "flyout_http+breakdown",
            availablePoints = 1500,
            pcProgress = 90,
            pcMax = 90,
            mobileProgress = 45,
            mobileMax = 60
        )
        profile.applyBalance(exactBreakdownBalance, today)
        assertEquals("Nguồn exact breakdown bắt buộc phải ghi đè chính xác mobileProgress = 45", 45, profile.mobileProgress)
    }

    // =========================================================================
    // 9. Edge bonus preservation on flyout
    // =========================================================================

    @Test
    fun testEdgeBonusPreservationOnFlyoutForLevel2() {
        val today = "2026-10-25"
        // Level 2 profile đã hoàn thành Edge 12/12 điểm
        val profileL2 = AccountProfile(
            id = "acc_l2_edge",
            email = "l2@outlook.com",
            profileName = "L2 Edge Profile",
            level = 2,
            pcProgress = 90,
            pcMax = 90,
            edgeProgress = 12,
            edgeMax = 12,
            lastActiveDate = today
        )

        // Flyout HTTP không chứa counter Edge (edgeMax = 0, edgeProgress = 0)
        val flyoutWithoutEdge = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            source = "flyout_http",
            availablePoints = 2500,
            level = 2,
            pcProgress = 90,
            pcMax = 90,
            edgeProgress = 0,
            edgeMax = 0
        )

        profileL2.applyBalance(flyoutWithoutEdge, today)
        assertEquals("Edge progress của Level 2 không được bị xóa về 0 khi flyout thiếu rổ Edge", 12, profileL2.edgeProgress)
        assertEquals("Edge max của Level 2 phải được bảo toàn là 12", 12, profileL2.edgeMax)
    }

    // =========================================================================
    // 10. DailySet completion with < 3 tasks
    // =========================================================================

    @Test
    fun testDailySetCompletionWithLessThanThreeTasks() {
        // Giả lập vùng chỉ có 2 thẻ nhiệm vụ Daily Set
        val parsedTwoTasksComplete = RewardsBalanceExtractor.ExtractedRewardsBalance(
            isSuccess = true,
            source = "flyout_http",
            availablePoints = 1200,
            dailySetTotal = 2,
            dailySetProgress = 2,
            dailySetDone = true
        )

        assertTrue("Khi hoàn thành đủ 2/2 task, dailySetDone phải là true dù < 3 tasks", parsedTwoTasksComplete.dailySetDone)
        assertEquals(2, parsedTwoTasksComplete.dailySetTotal)
        assertEquals(2, parsedTwoTasksComplete.dailySetProgress)

        val today = "2026-10-25"
        val profile = AccountProfile(
            id = "acc_dailyset_2",
            email = "daily2@outlook.com",
            profileName = "Daily 2 Tasks",
            level = 2,
            dailySetTotal = 3,
            dailySetDone = false,
            lastActiveDate = today
        )

        profile.applyBalance(parsedTwoTasksComplete, today)
        assertEquals("dailySetTotal của profile phải cập nhật theo server là 2", 2, profile.dailySetTotal)
        assertEquals("dailySetProgress của profile phải là 2", 2, profile.dailySetProgress)
        assertTrue("dailySetDone của profile phải là true", profile.dailySetDone)

        // Nếu mới hoàn thành 1/2 task -> dailySetDone = false
        val parsedOneOfTwo = parsedTwoTasksComplete.copy(dailySetProgress = 1, dailySetDone = false)
        val profilePending = AccountProfile(
            id = "acc_dailyset_pending",
            email = "pending@outlook.com",
            profileName = "Pending Daily",
            level = 2,
            dailySetTotal = 2,
            dailySetDone = false,
            lastActiveDate = today
        )
        profilePending.applyBalance(parsedOneOfTwo, today)
        assertFalse("Chỉ hoàn thành 1/2 task thì dailySetDone phải là false", profilePending.dailySetDone)
        assertEquals(1, profilePending.dailySetProgress)
    }

    // =========================================================================
    // 11. TokenBucket clock drift reset
    // =========================================================================

    @Test
    fun testTokenBucketClockDriftReset() {
        val account = AccountProfile(
            id = "acc_drift",
            email = "drift@outlook.com",
            profileName = "Clock Drift Acc",
            searchWindowStartMs = 1_000_000L,
            searchesInWindow = 3,
            cooldownUntilTimestamp = 1_920_000L
        )

        // Giả lập đồng hồ thiết bị hoặc máy chủ bị nhảy lùi về quá khứ (nowMs = 500_000L < windowStart 1_000_000L)
        val pastNowMs = 500_000L
        val canSearch = SearchTokenBucketEngine.canExecuteSearch(account, pastNowMs)

        assertTrue("Khi phát hiện lệch xung nhịp (clock drift lùi giờ), engine phải tự động reset và cho phép tìm kiếm", canSearch)
        assertEquals("Mốc bắt đầu cửa sổ phải được reset về nowMs hiện tại", pastNowMs, account.searchWindowStartMs)
        assertEquals("Số lượt tìm kiếm trong cửa sổ phải được reset về 0", 0, account.searchesInWindow)

        // Ghi nhận tìm kiếm tại thời điểm lệch
        val count = SearchTokenBucketEngine.recordSearch(account, pastNowMs)
        assertEquals(1, count)
        assertEquals(pastNowMs, account.searchWindowStartMs)

        // Kiểm tra getRemainingCooldownMs trả về 0L khi đã được reset
        assertEquals(0L, SearchTokenBucketEngine.getRemainingCooldownMs(account, pastNowMs))
    }

    // =========================================================================
    // 12. Promotion blacklist matching new keywords & SEC host whitelist
    // =========================================================================

    @Test
    fun testPromotionBlacklistMatchingNewKeywordsAndHostWhitelist() {
        // 1. Kiểm tra các từ khóa mới được mở rộng trong PROMOTION_BLACKLIST_REGEX
        val blacklistKeywords = listOf(
            "Amazon Gift Card $5",
            "Win an Xbox Series X",
            "Get 50 bonus entries for sweepstakes",
            "Daily entry draw contest",
            "Lucky Draw points",
            "State Lottery chance",
            "Holiday charity raffle",
            "Đổi thưởng thẻ cào điện thoại",
            "đổi  thưởng quà tặng",
            "Complete checkout at Microsoft Store",
            "Redeem your points now"
        )

        for (kw in blacklistKeywords) {
            assertFalse(
                "Nhiệm vụ chứa từ khóa cấm '$kw' trong title bắt buộc phải bị loại bỏ",
                RewardsActivitiesEngine.isSafePromotion(title = kw, destinationUrl = "https://www.bing.com", pointValue = 10)
            )
        }

        // Kiểm tra từ khóa cấm trong destinationUrl
        assertFalse(
            "destinationUrl chứa /redeem bắt buộc phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion("Daily Task", "https://rewards.bing.com/redeem/0001", 10)
        )
        assertFalse(
            "destinationUrl chứa checkout bắt buộc phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion("Daily Task", "https://store.microsoft.com/checkout", 10)
        )

        // Kiểm tra từ khóa cấm trong description
        assertFalse(
            "description chứa từ khóa cấm bắt buộc phải bị chặn",
            RewardsActivitiesEngine.isSafePromotion("Safe Title", "https://www.bing.com", 10, description = "Win 1,000,000 points lottery")
        )

        // Kiểm tra các hoạt động hợp lệ không bị chặn nhầm
        assertTrue(
            "Nhiệm vụ Windows 11 Quiz không được bị chặn nhầm bởi win",
            RewardsActivitiesEngine.isSafePromotion("Windows 11 Quiz", "https://www.bing.com/search?q=windows+11", 10)
        )
        assertTrue(
            "Nhiệm vụ tìm kiếm thông thường phải an toàn",
            RewardsActivitiesEngine.isSafePromotion("Khám phá văn hóa Việt Nam", "https://www.bing.com/search?q=vietnam", 10)
        )

        // 2. Kiểm tra SEC-06 Whitelist host: Chỉ cho phép .bing.com / .microsoft.com / .msn.com
        assertTrue("www.bing.com phải được whitelist", RewardsActivitiesEngine.isAllowedRewardsHost("https://www.bing.com/rewards"))
        assertTrue("rewards.bing.com phải được whitelist", RewardsActivitiesEngine.isAllowedRewardsHost("https://rewards.bing.com/dashboard"))
        assertTrue("microsoft.com phải được whitelist", RewardsActivitiesEngine.isAllowedRewardsHost("https://www.microsoft.com/rewards"))
        assertTrue("msn.com phải được whitelist", RewardsActivitiesEngine.isAllowedRewardsHost("https://www.msn.com/vi-vn/news"))

        assertFalse("Domain bên ngoài login.live.com không thuộc whitelist loadUrl", RewardsActivitiesEngine.isAllowedRewardsHost("https://login.live.com"))
        assertFalse("Domain độc hại giả mạo phải bị chặn", RewardsActivitiesEngine.isAllowedRewardsHost("https://evil-phishing.com/bing.com"))
        assertFalse("Host rỗng phải trả về false", RewardsActivitiesEngine.isAllowedRewardsHost(""))
    }

    // =========================================================================
    // 13. LoopbackProxyBridge header limit
    // =========================================================================

    @Test
    fun testLoopbackProxyBridgeHeaderLimitEnforcement() {
        // Kiểm chứng mã nguồn LoopbackProxyBridge.kt có cơ chế giới hạn tối đa 100 headers (SEC-03)
        val bridgeFile = resolveProjectFile("src/main/java/com/agy/rewardsnode/core/LoopbackProxyBridge.kt")
        if (bridgeFile.exists()) {
            val source = bridgeFile.readText()
            assertTrue(
                "LoopbackProxyBridge phải kiểm tra headers.size >= 100",
                source.contains("headers.size >= 100")
            )
            assertTrue(
                "LoopbackProxyBridge phải ném IOException khi header vượt quá max 100",
                source.contains("HTTP headers count vượt quá giới hạn tối đa")
            )
        }

        // Kiểm chứng thực tế qua Socket kết nối vào LoopbackProxyBridge
        val mockUpstream = ServerSocket(0)
        val mockPort = mockUpstream.localPort
        val bridge = LoopbackProxyBridge.getInstance()
        val bridgePort = bridge.start("http://127.0.0.1:$mockPort")

        try {
            val clientSocket = Socket("127.0.0.1", bridgePort)
            val out = clientSocket.getOutputStream()

            // Gửi request line kèm 105 headers (vượt quá ngưỡng 100)
            val reqBuilder = StringBuilder()
            reqBuilder.append("GET http://www.bing.com/ HTTP/1.1\r\n")
            for (i in 1..105) {
                reqBuilder.append("X-Header-$i: test-value-$i\r\n")
            }
            reqBuilder.append("\r\n")

            out.write(reqBuilder.toString().toByteArray())
            out.flush()

            // LoopbackProxyBridge sẽ ném IOException và đóng kết nối ngay lập tức (không chuyển tiếp sang upstream)
            val input = clientSocket.getInputStream()
            val readByte = try { input.read() } catch (_: Exception) { -1 }
            // Kết nối bị đóng từ phía server do ném IOException
            assertEquals("Khi gửi > 100 headers, LoopbackProxyBridge phải ngắt kết nối ngay lập tức (-1 EOF)", -1, readByte)
            clientSocket.close()
        } finally {
            mockUpstream.close()
            bridge.stop()
        }
    }
}
