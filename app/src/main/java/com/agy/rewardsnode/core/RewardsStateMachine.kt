package com.agy.rewardsnode.core

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import com.agy.rewardsnode.utils.TelegramNotifier
import com.agy.rewardsnode.utils.WakeLockHelper
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Máy trạng thái điều phối quá trình tích điểm Microsoft Rewards đa tài khoản.
 * Thực thi phân nhóm theo cụm (Batching), sàn Cooldown toán học 920s (15m20s) cho nick restricted,
 * và Closed-Loop Point Delta Tracking theo dõi biến thiên điểm số thực tế.
 */
class RewardsStateMachine(
    private val keywordEngine: KeywordEngine,
    private val profileManager: ProfileManager? = null,
    private val vlogManager: VlogManager? = null,
    private val webViewProvider: (() -> WebView?)? = null,
    private val wakeLockHelper: WakeLockHelper? = null,
    private val onExecuteSearch: suspend (account: AccountProfile, keyword: String, phase: SearchPhase) -> Boolean,
    private val onStatusUpdate: (status: String, details: String) -> Unit,
    private val onAccountUpdated: suspend (account: AccountProfile) -> Unit,
    private val onScheduleCooldownAlarm: ((secs: Long, account: AccountProfile) -> Unit)? = null,
    private val telegramNotifier: TelegramNotifier? = null,
    private val onAnomalyDetected: (suspend (account: AccountProfile, reason: String, webView: WebView?) -> Unit)? = null,
    private val database: AppDatabase? = null,
    var targetAccountId: String? = null,
    private val globalProxyProvider: (() -> String?)? = null,
    var searchDelayMinSec: Int = 18,
    var searchDelayMaxSec: Int = 45,
    var maxClaimRetries: Int = 2,
    var dapiMaxRetries: Int = 2,
    var cooldownTriggerBatches: Int = 2,
    var frozenStopBatches: Int = 3,
    var maxReconciliationPasses: Int = 2,
    private val encryptedPrefs: EncryptedPreferences? = null
) {
    fun resolveProxyForAccount(account: AccountProfile): String? =
        ProfileManager.resolveEffectiveProxy(account.proxyUrl, globalProxyProvider?.invoke())

    private suspend fun extractPointsForAccount(account: AccountProfile) =
        RewardsBalanceExtractor.extractPointsViaHttp(
            account.cookiesSnapshot,
            account.customUserAgent,
            resolveProxyForAccount(account)
        )
    private var isRunning = false
    private var currentJob: Job? = null
    private var activeWebView: WebView? = null
    private var activeSessionAccountId: String? = null
    private val currentAccountId: String?
        get() = activeSessionAccountId ?: targetAccountId

    private val isWaitingForAlarmAtomic = java.util.concurrent.atomic.AtomicBoolean(false)
    private var alarmWakeupDeferred: CompletableDeferred<Unit>? = null

    // Tập hợp tài khoản bị phát hiện dính cờ Cooldown 15 phút (Closed-Loop Rate Limit)
    private val cooldownRestrictedAccounts = Collections.synchronizedSet(mutableSetOf<String>())
    // Tập hợp tài khoản bị mất đồng bộ Bing Search Auth (Issue #36 isRewardsUser == false)
    private val authDesyncAccounts = Collections.synchronizedSet(mutableSetOf<String>())
    // Bảng lưu số điểm trước batch để đo ΔPoints chính xác
    private val preBatchPointsMap = ConcurrentHashMap<String, Int>()
    // Bảng theo dõi số batch liên tiếp ΔPoints == 0 của mỗi tài khoản
    private val zeroDeltaBatchCountMap = ConcurrentHashMap<String, Int>()
    // Bảng lưu kết quả trích xuất điểm gần nhất để kiểm soát rổ điểm & cày bù
    private val latestBalanceMap = ConcurrentHashMap<String, RewardsBalanceExtractor.ExtractedRewardsBalance>()
    // Bảng theo dõi ngày hoàn thành Phase 0 (Pre-Search Activities) của từng tài khoản
    private val phase0CompletedDateMap = ConcurrentHashMap<String, String>()
    // Bảng theo dõi ngày hoàn thành Phase 0.5 (DAPI Activities) của từng tài khoản
    private val phase05CompletedDateMap = ConcurrentHashMap<String, String>()
    // Bảng theo dõi ngày hoàn thành Bing App Check-In của từng tài khoản
    private val checkInCompletedDateMap = ConcurrentHashMap<String, String>()
    // Bảng theo dõi ngày khởi tạo baselinePointsToday của từng tài khoản
    private val baselineDateMap = ConcurrentHashMap<String, String>()
    // Bảng theo dõi các Phase đã thử nghiệm không tăng điểm (đạt trần hoặc cạn ngạch) của từng tài khoản trong ngày
    private val phaseExhaustedMap = ConcurrentHashMap<String, MutableSet<SearchPhase>>()
    // Bảng ghi nhận các rổ điểm đã đạt trần trong phiên làm việc hiện tại
    private val sessionFullBasketsMap = ConcurrentHashMap<String, MutableSet<SearchPhase>>()
    // Bảng đếm số lần recheck của từng rổ trong phiên làm việc
    private val sessionRecheckCountMap = ConcurrentHashMap<String, ConcurrentHashMap<SearchPhase, Int>>()
    // Bảng theo dõi số lượt delta == 0 liên tiếp của từng phase
    private val phaseZeroDeltaCountMap = ConcurrentHashMap<String, ConcurrentHashMap<SearchPhase, Int>>()
    // Bảng đếm số lần thử claim Ready-to-Claim trong ngày của từng tài khoản (tối đa maxClaimRetries lần/ngày chống lặp vô tận)
    private val claimAttemptCountMap = ConcurrentHashMap<String, Int>()
    // Bảng đếm số lần thử lấy DAPI token trong ngày của từng tài khoản (tối đa dapiMaxRetries lần/ngày chống lặp vô tận)
    private val dapiAttemptCountMap = ConcurrentHashMap<String, Int>()
    // Bảng dự phòng trong RAM cho số lần thử từng offer trong ngày khi encryptedPrefs == null
    private val offerTriedCountMap = ConcurrentHashMap<String, Int>()

    private fun applyBalancePreservingExhaustedCap(
        account: AccountProfile,
        balance: RewardsBalanceExtractor.ExtractedRewardsBalance,
        todayStr: String,
        fallbackBaseline: Int? = null
    ) {
        account.applyBalance(balance, todayStr, fallbackBaseline)
        if (encryptedPrefs?.isSearchExhaustedToday(account.id, todayStr) == true &&
            account.pcMax > 0 &&
            account.pcProgress >= account.pcMax - 3
        ) {
            account.pcProgress = account.pcMax
        }
    }

    private fun getOfferTriedCount(accountId: String, offerId: String, todayStr: String): Int {
        encryptedPrefs?.let { return it.getOfferTriedCountToday(accountId, offerId, todayStr) }
        return offerTriedCountMap["$todayStr:$accountId:$offerId"] ?: 0
    }

    private fun incrementOfferTriedCount(accountId: String, offerId: String, todayStr: String): Int {
        encryptedPrefs?.let { return it.incrementOfferTriedCountToday(accountId, offerId, todayStr) }
        val key = "$todayStr:$accountId:$offerId"
        val memNext = (offerTriedCountMap[key] ?: 0) + 1
        offerTriedCountMap[key] = memNext
        return memNext
    }

    private fun getDapiAttempts(accountId: String, todayStr: String): Int {
        encryptedPrefs?.let { return it.getDapiAttemptsToday(accountId, todayStr) }
        return dapiAttemptCountMap["$todayStr:$accountId"] ?: 0
    }

    private fun incrementDapiAttempts(accountId: String, todayStr: String): Int {
        encryptedPrefs?.let { return it.incrementDapiAttemptsToday(accountId, todayStr) }
        val key = "$todayStr:$accountId"
        val memNext = (dapiAttemptCountMap[key] ?: 0) + 1
        dapiAttemptCountMap[key] = memNext
        return memNext
    }

    private fun isPhase05CompletedToday(accountId: String, todayStr: String): Boolean {
        return phase05CompletedDateMap[accountId] == todayStr ||
            (encryptedPrefs?.isPhase05DoneToday(accountId, todayStr) == true)
    }

    private fun markPhase05CompletedToday(accountId: String, todayStr: String) {
        phase05CompletedDateMap[accountId] = todayStr
        encryptedPrefs?.markPhase05DoneToday(accountId, todayStr)
    }

    private fun isCheckInCompletedToday(accountId: String, todayStr: String): Boolean {
        return checkInCompletedDateMap[accountId] == todayStr ||
            (encryptedPrefs?.isCheckInDoneToday(accountId, todayStr) == true)
    }

    private fun markCheckInCompletedToday(accountId: String, todayStr: String) {
        checkInCompletedDateMap[accountId] = todayStr
        encryptedPrefs?.markCheckInDoneToday(accountId, todayStr)
    }

    private fun isDapiPhaseDoneForAccount(account: AccountProfile, todayStr: String): Boolean {
        val checkInDone = isCheckInCompletedToday(account.id, todayStr)
        val readDone = account.readProgress >= 30
        if (checkInDone && readDone) return true
        val maxRetries = maxOf(1, dapiMaxRetries)
        if (getDapiAttempts(account.id, todayStr) >= maxRetries) return true
        return isPhase05CompletedToday(account.id, todayStr)
    }

    /**
     * Kiểm tra trạng thái hoàn thành toàn diện 60/60 điểm Bing App:
     * - Daily Set (30đ): account.dailySetDone || phase0CompletedDateMap[account.id] == todayStr
     * - MSN Read to Earn (30đ): account.readProgress >= 30 || phase05CompletedDateMap[account.id] == todayStr
     */
    fun isBingApp60Of60Done(account: AccountProfile, todayStr: String): Boolean {
        return (account.dailySetDone || phase0CompletedDateMap[account.id] == todayStr) &&
            (account.readProgress >= 30 || phase05CompletedDateMap[account.id] == todayStr)
    }

    private fun extractDailySetSubset(tasks: List<DailyTask>): List<DailyTask> {
        return tasks.filter {
            it.id.contains("daily_set", ignoreCase = true) ||
                it.id.contains("dailyset", ignoreCase = true) ||
                it.id.startsWith("Global_DailySet")
        }.ifEmpty { tasks.take(3) }
    }

    private fun areAllDailySetOffersExhausted(
        accountId: String,
        dailySetOffers: List<DailyTask>,
        todayStr: String
    ): Boolean {
        if (dailySetOffers.isEmpty()) return false
        val uncompleted = dailySetOffers.filter { !it.isCompleted }
        return uncompleted.isNotEmpty() && uncompleted.all { task ->
            val offerKey = task.offerId.ifBlank { task.id.ifBlank { task.destinationUrl.hashCode().toString() } }
            getOfferTriedCount(accountId, offerKey, todayStr) >= 2
        }
    }

    private suspend fun ensureOnRewardsDashboard(targetWebView: WebView, account: AccountProfile) {
        val curUrl = withContext(AndroidMainDispatcher) { targetWebView.url } ?: ""
        if (!curUrl.contains("rewards.bing.com/dashboard", ignoreCase = true) ||
            curUrl.contains("modal=pointsclaim", ignoreCase = true)
        ) {
            loadUrlAndWait(targetWebView, "https://rewards.bing.com/dashboard", 20000L)
            safeDelay(2000L)
            try {
                android.webkit.CookieManager.getInstance().flush()
                val freshCookies = profileManager?.dumpCookiesForProfile(account.id)
                if (!freshCookies.isNullOrBlank() && isPreservableCookie(freshCookies)) {
                    account.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, freshCookies)
                    onAccountUpdated(account)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    private suspend fun runQuizStepLoop(targetWebView: WebView, task: DailyTask): Boolean {
        val quizTask = if (task.taskType.equals("quiz", ignoreCase = true)) task else task.copy(taskType = "quiz")
        var quizSolved = false
        for (step in 1..8) {
            val stepResult = RewardsActivitiesEngine.solveDailyTask(targetWebView, quizTask)
            if (stepResult) {
                quizSolved = true
                safeDelay(Random.nextLong(2200L, 3500L))
            } else {
                break
            }
        }
        return quizSolved
    }

    /**
     * Thực thi quy trình Ready to Claim 2 nhịp trên WebView (?modal=pointsclaim) kèm xác thực lại qua HTTP.
     */
    private suspend fun executeTwoStepPointClaim(targetWebView: WebView, account: AccountProfile): Boolean {
        val preClaimPoints = account.availablePoints
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val claimKey = "$todayStr:${account.id}"
        claimAttemptCountMap[claimKey] = (claimAttemptCountMap[claimKey] ?: 0) + 1
        return try {
            loadUrlAndWait(targetWebView, "https://rewards.bing.com/dashboard?modal=pointsclaim", 18000L)
            safeDelay(3500L)
            RewardsActivitiesEngine.enableStreakProtection(targetWebView)

            // Nhịp 1: Click nút Claim trong modal (hoặc click thẻ PointClaim để mở modal)
            val step1Clicked = RewardsActivitiesEngine.executeReadyToClaim(targetWebView)
            safeDelay(2500L)

            // Nhịp 2: Click xác nhận nút Claim bên trong modal nếu Nhịp 1 vừa mở modal
            val step2Clicked = RewardsActivitiesEngine.executeReadyToClaim(targetWebView)
            if (step1Clicked || step2Clicked) {
                safeDelay(3000L)
            }

            // Đồng bộ lại cookie sau khi tương tác trên Dashboard (hợp nhất bảo toàn login.live.com)
            try {
                android.webkit.CookieManager.getInstance().flush()
                val freshCookies = profileManager?.dumpCookiesForProfile(account.id)
                if (!freshCookies.isNullOrBlank() && (freshCookies.contains("_RwBf") || freshCookies.contains("_U") || freshCookies.contains("MUID"))) {
                    account.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, freshCookies)
                }
            } catch (_: Exception) {}

            // Xác thực lại qua HTTP API xem claimablePoints đã giảm hoặc availablePoints đã tăng chưa
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val postClaimBal = extractPointsForAccount(account)
            if (postClaimBal.isSuccess && postClaimBal.availablePoints > 0) {
                latestBalanceMap[account.id] = postClaimBal
                RewardsBalanceExtractor.latestTelemetryMap[account.id] = postClaimBal
                if (account.email.isNotBlank()) {
                    RewardsBalanceExtractor.latestTelemetryMap[account.email] = postClaimBal
                }
                applyBalancePreservingExhaustedCap(account, postClaimBal, todayStr, account.baselinePointsToday)
                account.claimablePoints = postClaimBal.claimablePoints
                onAccountUpdated(account)
                val claimedSuccess = postClaimBal.claimablePoints == 0 || postClaimBal.availablePoints > preClaimPoints
                if (claimedSuccess) {
                    vlogManager?.logPoints(
                        "[READY-TO-CLAIM] Đã nhận điểm thưởng thành công!",
                        "Số dư: $preClaimPoints -> ${account.availablePoints} pts | Còn chờ: ${account.claimablePoints} pts",
                        account.email
                    )
                } else {
                    vlogManager?.logPoints(
                        "[READY-TO-CLAIM] Đã thử claim nhưng server vẫn giữ +${account.claimablePoints} chờ",
                        "Điểm thưởng có thể có ngày mở khóa (DepositedDate) vào cuối tuần/cuối tháng.",
                        account.email
                    )
                }
                claimedSuccess
            } else {
                step1Clicked || step2Clicked
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    val MOBILE_USER_AGENT = FingerprintEngine.MOBILE[0].userAgent
    val DESKTOP_USER_AGENT = FingerprintEngine.DESKTOP[0].userAgent

    private suspend fun loadUrlAndWait(
        webView: WebView,
        url: String,
        timeoutMs: Long = 20000L
    ): Boolean = withContext(AndroidMainDispatcher) {
        wakeLockHelper?.acquire("Burst_LoadUrl", timeoutMs)
        try {
            suspendCancellableCoroutine { continuation ->
                var completed = false
                val handler = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!completed && continuation.isActive) {
                        completed = true
                        continuation.resume(false)
                    }
                }
                handler.postDelayed(timeoutRunnable, timeoutMs)
                continuation.invokeOnCancellation {
                    handler.removeCallbacks(timeoutRunnable)
                    handler.post {
                        try {
                            webView.stopLoading()
                        } catch (_: Exception) {}
                    }
                }

                val targetUri = try { java.net.URI(url) } catch (_: Exception) { null }
                val targetHost = targetUri?.host?.lowercase(Locale.ROOT) ?: ""

                val autoAuthScript = """
                    (function(){
                        try {
                            var f = document.forms[0] || document.fmHF || document.getElementById('fmHF');
                            if (f && f.action) {
                                var u = new URL(f.action, window.location.href);
                                var h = u.hostname.toLowerCase();
                                if (h === 'rewards.bing.com' || h === 'www.bing.com' || h.endsWith('.bing.com')) {
                                    f.submit();
                                    return 'form_submitted';
                                }
                            }
                            var tile = document.querySelector('div[data-test-id*="tile"]') 
                                || document.querySelector('.table[role="button"]')
                                || document.querySelector('div[role="button"][data-bind*="tile"]')
                                || document.querySelector('#tilesHolder .tile-container')
                                || document.querySelector('.tile-container')
                                || document.querySelector('div[role="button"]');
                            if (tile) {
                                tile.click();
                                return 'tile_clicked';
                            }
                            var btn = document.getElementById('idSIButton9') 
                                || document.querySelector('input[type="submit"]') 
                                || document.querySelector('button[type="submit"]');
                            if (btn) {
                                btn.click();
                                return 'btn_clicked';
                            }
                        } catch(e) {}
                        return 'none';
                    })();
                """.trimIndent()

                webView.webViewClient = object : DataSaverWebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val stealthScript = FingerprintEngine.createStealthScript(
                            FingerprintEngine.getEdgePresetForAccount(currentAccountId ?: "default", false),
                            true
                        )
                        view?.evaluateJavascript(stealthScript, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val curUrl = url ?: ""
                        val curHost = try { java.net.URI(curUrl).host?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }

                        // Nếu bị chuyển hướng qua login.live.com để xác thực SSO / Account Picker:
                        if (curHost == "login.live.com" || curHost.endsWith(".live.com")) {
                            view?.evaluateJavascript(autoAuthScript, null)
                            // Nếu đích đến là rewards.bing.com hoặc bing.com, tiếp tục đợi chuyển tiếp hoàn tất về đích
                            if (targetHost.contains("bing.com")) {
                                return
                            }
                        }

                        if (!completed && continuation.isActive) {
                            completed = true
                            handler.removeCallbacks(timeoutRunnable)
                            continuation.resume(true)
                        }
                    }

                    @Suppress("DEPRECATION")
                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        if (!completed && continuation.isActive) {
                            completed = true
                            handler.removeCallbacks(timeoutRunnable)
                            continuation.resume(false)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && !completed && continuation.isActive) {
                            completed = true
                            handler.removeCallbacks(timeoutRunnable)
                            continuation.resume(false)
                        }
                    }
                }

                try {
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.databaseEnabled = true
                    webView.settings.javaScriptCanOpenWindowsAutomatically = true
                    webView.settings.setSupportMultipleWindows(false)
                    FingerprintEngine.stripXRequestedWith(webView)
                    webView.loadUrl(url)
                } catch (e: Exception) {
                    if (!completed && continuation.isActive) {
                        completed = true
                        handler.removeCallbacks(timeoutRunnable)
                        continuation.resume(false)
                    }
                }
            }
        } finally {
            wakeLockHelper?.release()
        }
    }

    /**
     * Tạm dừng an toàn (Zero WakeLock): Nhả 100% WakeLock trước khi bước vào trạng thái delay.
     */
    private suspend fun safeDelay(ms: Long) {
        wakeLockHelper?.release()
        delay(ms)
    }

    fun setWebView(webView: WebView?) {
        activeWebView = webView
    }

    fun isAccountCooldownRestricted(accountId: String): Boolean {
        return cooldownRestrictedAccounts.contains(accountId)
    }

    fun markAuthDesync(accountId: String) {
        authDesyncAccounts.add(accountId)
    }

    fun clearAuthDesync(accountId: String) {
        authDesyncAccounts.remove(accountId)
    }

    fun isAuthDesync(accountId: String): Boolean {
        return authDesyncAccounts.contains(accountId)
    }

    fun markAccountRestricted(accountId: String, restricted: Boolean) {
        if (restricted) {
            cooldownRestrictedAccounts.add(accountId)
        } else {
            cooldownRestrictedAccounts.remove(accountId)
            zeroDeltaBatchCountMap.remove(accountId)
        }
    }

    fun isWaitingForAlarm(): Boolean = isWaitingForAlarmAtomic.get()

    suspend fun waitForAlarmWakeup(cooldownSecs: Long) {
        wakeLockHelper?.release()
        isWaitingForAlarmAtomic.set(true)
        val deferred = CompletableDeferred<Unit>()
        alarmWakeupDeferred = deferred
        try {
            withTimeoutOrNull(cooldownSecs * 1000L) {
                deferred.await()
            }
        } finally {
            isWaitingForAlarmAtomic.set(false)
            alarmWakeupDeferred = null
            wakeLockHelper?.release()
        }
    }

    fun notifyAlarmWakeup() {
        isWaitingForAlarmAtomic.set(false)
        alarmWakeupDeferred?.complete(Unit)
    }

    fun recordSearchDelta(accountId: String, delta: Int) {
        if (delta == 0) {
            val count = (zeroDeltaBatchCountMap[accountId] ?: 0) + 1
            zeroDeltaBatchCountMap[accountId] = count
            if (count >= maxOf(1, cooldownTriggerBatches)) {
                markAccountRestricted(accountId, true)
            }
        } else {
            zeroDeltaBatchCountMap[accountId] = 0
            markAccountRestricted(accountId, false)
        }
    }

    fun isFrozenBalance(accountId: String): Boolean {
        return (zeroDeltaBatchCountMap[accountId] ?: 0) >= maxOf(2, frozenStopBatches)
    }

    fun getConsecutiveZeroDeltaSearches(accountId: String): Int {
        return zeroDeltaBatchCountMap[accountId] ?: 0
    }

    enum class SearchPhase(val code: Int, val label: String, val isDesktop: Boolean) {
        PHASE_1_PC(1, "Pha 1: Desktop Edge (PC Search)", true),
        PHASE_2_MOBILE(2, "Pha 2: Mobile Edge (Mobile Search)", false),
        PHASE_3_EDGE(3, "Pha 3: Desktop Edge (Edge Bonus)", true),
        DONE(4, "Hoàn tất tìm kiếm", true)
    }

    /**
     * Đánh giá và chuyển tiếp Phase tìm kiếm theo thuật toán Skip-Logic:
     * - Trước Phase 1: Nếu isPcDone -> ghi vlog [SKIP-PC], chuyển sang Phase 2.
     * - Trước Phase 2: Nếu isMobDone -> ghi vlog [SKIP-MOB], chuyển sang Phase 3.
     * - Trước Phase 3: Nếu isEdgeDone -> ghi vlog [SKIP-EDGE], hoàn tất tìm kiếm.
     */
    fun determineNextPhase(
        account: AccountProfile,
        currentPhase: SearchPhase = SearchPhase.PHASE_1_PC,
        loggedSkips: MutableSet<SearchPhase>? = null
    ): SearchPhase = evaluateNextSearchPhase(account, currentPhase, loggedSkips)

    fun evaluateNextSearchPhase(
        account: AccountProfile,
        currentPhase: SearchPhase = SearchPhase.PHASE_1_PC,
        loggedSkips: MutableSet<SearchPhase>? = null
    ): SearchPhase {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val isPersistedSearchExhausted = encryptedPrefs?.isSearchExhaustedToday(account.id, todayStr) == true

        val balance = latestBalanceMap[account.id]
            ?: RewardsBalanceExtractor.latestTelemetryMap[account.id]
            ?: RewardsBalanceExtractor.latestTelemetryMap[account.email]

        val exhausted = phaseExhaustedMap[account.id] ?: emptySet()

        val isLevel1 = (account.level < 2)

        val pcMax = if (balance != null && balance.pcMax > 0) balance.pcMax else (if (account.pcMax > 0) account.pcMax else (if (isLevel1) 30 else 90))
        val pcProgress = if (balance != null && balance.isSuccess && balance.pcMax > 0) {
            if (isPersistedSearchExhausted && balance.pcProgress >= balance.pcMax - 3) balance.pcMax else balance.pcProgress
        } else {
            account.pcProgress
        }

        val mobileMax = if (balance != null && balance.mobileMax > 0) {
            balance.mobileMax
        } else if (account.mobileMax > 0) {
            account.mobileMax
        } else if (isLevel1) {
            0
        } else {
            60
        }
        val mobileProgress = if (balance != null && balance.isSuccess && balance.mobileMax > 0) balance.mobileProgress else account.mobileProgress

        val edgeMax = if (balance != null && balance.isSuccess) balance.edgeMax else (if (account.edgeMax > 0) account.edgeMax else 0)
        val edgeProgress = if (balance != null && balance.isSuccess) balance.edgeProgress else account.edgeProgress

        // Tự động latch vào sessionFullBasketsMap khi đạt trần
        if (pcMax > 0 && pcProgress >= pcMax) {
            sessionFullBasketsMap.getOrPut(account.id) { Collections.synchronizedSet(mutableSetOf()) }.add(SearchPhase.PHASE_1_PC)
        }
        if (mobileMax > 0 && mobileProgress >= mobileMax) {
            sessionFullBasketsMap.getOrPut(account.id) { Collections.synchronizedSet(mutableSetOf()) }.add(SearchPhase.PHASE_2_MOBILE)
        }
        if (edgeMax > 0 && edgeProgress >= edgeMax) {
            sessionFullBasketsMap.getOrPut(account.id) { Collections.synchronizedSet(mutableSetOf()) }.add(SearchPhase.PHASE_3_EDGE)
        }

        val sessionFull = sessionFullBasketsMap[account.id] ?: emptySet()
        val isPcFullInSession = sessionFull.contains(SearchPhase.PHASE_1_PC)
        val isMobFullInSession = sessionFull.contains(SearchPhase.PHASE_2_MOBILE)
        val isEdgeFullInSession = sessionFull.contains(SearchPhase.PHASE_3_EDGE)

        val isPcDone = (pcMax > 0 && pcProgress >= pcMax) || exhausted.contains(SearchPhase.PHASE_1_PC) || isPersistedSearchExhausted || isPcFullInSession
        // Level Gating: Nếu account.level < 2 hoặc mobileMax == 0, BỎ QUA Phase 2 (Mobile Search)
        val isMobDone = (account.level < 2 || mobileMax == 0) || (mobileMax > 0 && mobileProgress >= mobileMax) || exhausted.contains(SearchPhase.PHASE_2_MOBILE) || isMobFullInSession
        val isEdgeDone = (edgeMax == 0) || (edgeMax > 0 && edgeProgress >= edgeMax) || exhausted.contains(SearchPhase.PHASE_3_EDGE) || isEdgeFullInSession

        var phase = currentPhase

        // Trước Phase 1: Nếu isPcDone -> ghi vlog [SKIP-PC], chuyển sang Phase 2.
        if (phase == SearchPhase.PHASE_1_PC) {
            if (isPcDone) {
                if (loggedSkips != null && loggedSkips.add(SearchPhase.PHASE_1_PC)) {
                    val reason = if (isPcFullInSession) {
                        "Đã đạt trần PC trong phiên ($pcProgress/$pcMax)"
                    } else if (pcMax > 0 && pcProgress >= pcMax) {
                        "Đã đạt tối đa rổ PC ($pcProgress/$pcMax)"
                    } else {
                        "Đã thử nghiệm PC không tăng thêm điểm"
                    }
                    vlogManager?.logPoints(
                        "[SKIP-PC] Bỏ qua Phase 1 (PC Search) cho ${account.email}",
                        "$reason. Chuyển sang Phase 2 (Mobile Search).",
                        account.email
                    )
                }
                phase = SearchPhase.PHASE_2_MOBILE
            } else {
                return SearchPhase.PHASE_1_PC
            }
        }

        // Trước Phase 2: Nếu isMobDone -> ghi vlog [SKIP-MOB], chuyển sang Phase 3.
        if (phase == SearchPhase.PHASE_2_MOBILE) {
            if (isMobDone) {
                if (loggedSkips != null && loggedSkips.add(SearchPhase.PHASE_2_MOBILE)) {
                    val reason = if (account.level < 2 || mobileMax == 0) {
                        "Level Gating: Tài khoản Level ${account.level} (<2) hoặc mobileMax == 0"
                    } else if (isMobFullInSession || (mobileMax > 0 && mobileProgress >= mobileMax)) {
                        "Đã đạt tối đa rổ Mobile ($mobileProgress/$mobileMax)"
                    } else {
                        "Đã thử nghiệm Mobile không tăng thêm điểm"
                    }
                    vlogManager?.logPoints(
                        "[SKIP-MOB] Bỏ qua Phase 2 (Mobile Search) cho ${account.email}",
                        "$reason. Chuyển sang Phase 3 (Edge Search) hoặc kết thúc.",
                        account.email
                    )
                }
                phase = SearchPhase.PHASE_3_EDGE
            } else {
                return SearchPhase.PHASE_2_MOBILE
            }
        }

        // Trước Phase 3: Nếu isEdgeDone -> ghi vlog [SKIP-EDGE], hoàn tất tìm kiếm.
        if (phase == SearchPhase.PHASE_3_EDGE) {
            if (isEdgeDone) {
                if (loggedSkips != null && loggedSkips.add(SearchPhase.PHASE_3_EDGE)) {
                    val reason = if (edgeMax == 0) {
                        "Không có rổ Edge Bonus (edgeMax == 0)"
                    } else if (isEdgeFullInSession || (edgeMax > 0 && edgeProgress >= edgeMax)) {
                        "Đã đạt tối đa rổ Edge ($edgeProgress/$edgeMax)"
                    } else {
                        "Đã thử nghiệm Edge không tăng thêm điểm"
                    }
                    vlogManager?.logPoints(
                        "[SKIP-EDGE] Bỏ qua Phase 3 (Edge Search) cho ${account.email}",
                        "$reason. Hoàn tất tìm kiếm.",
                        account.email
                    )
                }
                if (!isPcDone) {
                    return SearchPhase.PHASE_1_PC
                }
                return SearchPhase.DONE
            } else {
                return SearchPhase.PHASE_3_EDGE
            }
        }

        return SearchPhase.DONE
    }

    /**
     * Sequential Phase Routing:
     * - Pha 1: Desktop Edge (PC Search).
     * - Pha 2: Mobile Edge (Mobile Search).
     * - Pha 3: Desktop Edge (Edge Bonus).
     */
    fun selectRoutingMode(account: AccountProfile): Boolean {
        val phase = evaluateNextSearchPhase(account)
        return phase.isDesktop
    }

    @Suppress("UNUSED_PARAMETER")
    fun start(
        accounts: List<AccountProfile>,
        batchSize: Int,
        cooldownMinutes: Int,
        coroutineScope: CoroutineScope,
        webView: WebView? = null
    ) {
        if (isRunning) return
        isRunning = true
        sessionFullBasketsMap.clear()
        sessionRecheckCountMap.clear()
        if (webView != null) {
            activeWebView = webView
        }

        currentJob = coroutineScope.launch(Dispatchers.Default) {
            try {
                var accountList = accounts

            while (isActive && isRunning) {
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                if (database != null) {
                    try {
                        val dbProfiles = database.rewardsDao().getAllActiveProfiles()
                        if (dbProfiles.isNotEmpty()) {
                            val prevMap = accountList.associateBy { it.id }
                            for (dbAcc in dbProfiles) {
                                val prev = prevMap[dbAcc.id]
                                if (prev != null) {
                                    if (prev.cookieExpired && dbAcc.cookiesSnapshot == prev.cookiesSnapshot) {
                                        dbAcc.cookieExpired = true
                                    }
                                    if (prev.cooldownUntilTimestamp > dbAcc.cooldownUntilTimestamp) {
                                        dbAcc.cooldownUntilTimestamp = prev.cooldownUntilTimestamp
                                    }
                                    dbAcc.pcProgress = maxOf(dbAcc.pcProgress, prev.pcProgress)
                                    dbAcc.mobileProgress = maxOf(dbAcc.mobileProgress, prev.mobileProgress)
                                    dbAcc.edgeProgress = maxOf(dbAcc.edgeProgress, prev.edgeProgress)
                                    dbAcc.completedSearchesToday = maxOf(dbAcc.completedSearchesToday, prev.completedSearchesToday)
                                    if (prev.pcMax > 0 && dbAcc.pcMax == 0) dbAcc.pcMax = prev.pcMax
                                    if (prev.mobileMax > 0 && dbAcc.mobileMax == 0) dbAcc.mobileMax = prev.mobileMax
                                    if (prev.edgeMax > 0 && dbAcc.edgeMax == 0) dbAcc.edgeMax = prev.edgeMax
                                }
                            }
                            accountList = dbProfiles
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                    }
                }

                var allAccountsFinished = true
                var anyAccountExecutedInCycle = false
                var earliestCooldownUntilMs = Long.MAX_VALUE

                // Single-Account Target: Nếu targetAccountId khác null, chỉ chạy duy nhất account đó
                val candidateAccounts = if (!targetAccountId.isNullOrBlank()) {
                    accountList.filter { it.id == targetAccountId }
                } else {
                    accountList
                }

                if (candidateAccounts.isEmpty() && !targetAccountId.isNullOrBlank()) {
                    vlogManager?.logSystem("Single-Account Target", "Không tìm thấy tài khoản với ID: $targetAccountId. Dừng điều phối.")
                    isRunning = false
                    break
                }

                for (account in candidateAccounts) {
                    if (!isActive || !isRunning) break
                    if (!account.isEnabled) continue
                    if (encryptedPrefs?.isWifiOnly == true && wakeLockHelper?.isWifiConnected() == false) {
                        vlogManager?.logCooldown(
                            "[NET-GUARD] Tạm dừng chờ kết nối Wi-Fi (Chế độ Chỉ dùng Wi-Fi đang bật)",
                            "Sẽ tự động kiểm tra lại sau 60s."
                        )
                        onStatusUpdate("Đang chờ kết nối Wi-Fi...", "Chế độ Wi-Fi Only đang bật")
                        wakeLockHelper?.release()
                        allAccountsFinished = false
                        earliestCooldownUntilMs = minOf(earliestCooldownUntilMs, System.currentTimeMillis() + 60_000L)
                        break
                    }
                    if (encryptedPrefs?.isBatteryGuardEnabled == true && wakeLockHelper?.isBatterySafe(15) == false) {
                        vlogManager?.logCooldown(
                            "[BATTERY-GUARD] Pin yếu (<15%) và chưa cắm sạc — tạm hoãn để bảo vệ thiết bị",
                            "Sẽ kiểm tra lại sau 120s."
                        )
                        onStatusUpdate("Tạm dừng bảo vệ Pin (<15%)", "Vui lòng cắm sạc để tiếp tục chạy Node")
                        wakeLockHelper?.release()
                        allAccountsFinished = false
                        earliestCooldownUntilMs = minOf(earliestCooldownUntilMs, System.currentTimeMillis() + 120_000L)
                        break
                    }
                    val nowMs = System.currentTimeMillis()
                    if (account.cooldownUntilTimestamp > nowMs) {
                        allAccountsFinished = false
                        earliestCooldownUntilMs = minOf(earliestCooldownUntilMs, account.cooldownUntilTimestamp)
                        continue
                    } else if (account.cooldownUntilTimestamp != 0L) {
                        account.cooldownUntilTimestamp = 0L
                        onAccountUpdated(account)
                    }
                    wakeLockHelper?.acquire("Cycle_${account.id}")
                    if (account.cookieExpired) {
                        val renewed = if (!account.cookiesSnapshot.isNullOrBlank()) {
                            ProfileManager.renewCookiesViaPureHttp(
                                account.cookiesSnapshot ?: "",
                                account.customUserAgent,
                                resolveProxyForAccount(account)
                            )
                        } else null
                        if (!renewed.isNullOrBlank()) {
                            account.cookiesSnapshot = renewed
                            account.cookieExpired = false
                            onAccountUpdated(account)
                            vlogManager?.logPoints("[AUTO-RENEW] Đã tự động gia hạn cookie qua HTTP thành công cho ${account.email}", "", account.email)
                        } else {
                            vlogManager?.logPoints("[SKIP-EXPIRED] Bỏ qua tài khoản do cookies đã hết hạn: ${account.email}", "", account.email)
                            continue
                        }
                    }

                    val targetWebView = webView ?: activeWebView ?: webViewProvider?.invoke()

                    // Kiểm tra và reset bộ đếm nếu sang ngày mới, chuẩn hóa targetSearches (Level 1: 30-35 lượt, Level 2: 55 lượt)
                    val expectedTarget = if (account.level < 2) 35 else 55
                    val baselineDate = baselineDateMap[account.id] ?: account.lastActiveDate
                    if (baselineDate != todayStr) {
                        account.completedSearchesToday = 0
                        account.pointsEarnedToday = 0
                        account.pcProgress = 0
                        account.mobileProgress = 0
                        account.edgeProgress = 0
                        account.readProgress = 0
                        account.baselinePointsToday = account.availablePoints
                        account.lastActiveDate = todayStr
                        account.targetSearches = expectedTarget
                        baselineDateMap[account.id] = todayStr
                        zeroDeltaBatchCountMap.remove(account.id)
                        latestBalanceMap.remove(account.id)
                        phase0CompletedDateMap.remove(account.id)
                        phase05CompletedDateMap.remove(account.id)
                        checkInCompletedDateMap.remove(account.id)
                        phaseExhaustedMap.remove(account.id)
                        phaseZeroDeltaCountMap.remove(account.id)
                        sessionFullBasketsMap.remove(account.id)
                        sessionRecheckCountMap.remove(account.id)
                        claimAttemptCountMap.keys.removeIf { it.endsWith(":${account.id}") }
                        dapiAttemptCountMap.keys.removeIf { it.endsWith(":${account.id}") }
                        offerTriedCountMap.keys.removeIf { it.contains(":${account.id}:") }
                        onAccountUpdated(account)
                    } else {
                        if (account.baselinePointsToday <= 0 && account.availablePoints > 0) {
                            account.baselinePointsToday = account.availablePoints
                        }
                        baselineDateMap[account.id] = todayStr
                        if (account.level < 2) {
                            if (account.targetSearches !in 30..35) {
                                account.targetSearches = 35
                                onAccountUpdated(account)
                            }
                        } else if (account.targetSearches != expectedTarget) {
                            account.targetSearches = expectedTarget
                            onAccountUpdated(account)
                        }
                    }

                    val isCatchingUp = account.completedSearchesToday >= account.targetSearches
                    val maxCatchUpSearches = minOf(account.targetSearches + 3, (account.targetSearches * 1.6).toInt())

                    // Pre-flight Validation: Trước vòng cày đầu tiên, ping extractPointsViaHttp.
                    // Nếu 401/403/hết hạn, set account.cookieExpired = true, log cảnh báo và skip.
                    // Vòng retry cày bù không check lại.
                    if (!isCatchingUp) {
                        if (account.cookiesSnapshot.isNullOrBlank()) {
                            account.cookieExpired = true
                            onAccountUpdated(account)
                            vlogManager?.logError(
                                "[PRE-FLIGHT] Cookies trống",
                                "Tài khoản ${account.email} không có cookies. Đánh dấu cookieExpired = true và bỏ qua.",
                                account.email
                            )
                            continue
                        }

                        var httpPing = try {
                            extractPointsForAccount(account)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            null
                        }

                        if (httpPing == null || !httpPing.isSuccess) {
                            val retryDelays = listOf(5000L, 10000L, 15000L)
                            for ((idx, delayMs) in retryDelays.withIndex()) {
                                if (!isActive || !isRunning) break
                                val isTransientNetworkError = httpPing == null ||
                                    httpPing.source == "http_error" ||
                                    httpPing.source == "dashboard_http_error" ||
                                    httpPing.source == "none" ||
                                    httpPing.source.startsWith("http_5")
                                if (!isTransientNetworkError) break
                                vlogManager?.logNetwork(
                                    "[NET-RETRY] Mạng yếu/timeout khi kiểm tra tài khoản ${account.email} (Lần ${idx + 1}/3)",
                                    "Source: ${httpPing?.source ?: "HTTP_TIMEOUT"}. Chờ ${delayMs / 1000}s rồi thử lại...",
                                    account.email
                                )
                                safeDelay(delayMs)
                                if (!isActive || !isRunning) break
                                wakeLockHelper?.acquire("NetRetry_${account.id}")
                                httpPing = try {
                                    extractPointsForAccount(account)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    null
                                }
                                if (httpPing != null && httpPing.isSuccess) break
                            }
                        }

                        if (httpPing == null || !httpPing.isSuccess || httpPing.availablePoints <= 0) {
                            val renewedCookies = try {
                                ProfileManager.renewCookiesViaPureHttp(
                                    account.cookiesSnapshot ?: "",
                                    account.customUserAgent,
                                    resolveProxyForAccount(account)
                                )
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                null
                            }
                            if (!renewedCookies.isNullOrBlank()) {
                                account.cookiesSnapshot = renewedCookies
                                account.cookieExpired = false
                                onAccountUpdated(account)
                                vlogManager?.logPoints("[AUTO-RENEW] Gia hạn cookie ngầm thành công cho ${account.email}", "", account.email)
                                httpPing = try {
                                    extractPointsForAccount(account)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    null
                                }
                            }
                        }

                        val isExpiredOrUnauthorized = httpPing != null && (
                            httpPing.source.contains("401") ||
                            httpPing.source.contains("403") ||
                            httpPing.source.contains("unauthorized", ignoreCase = true) ||
                            httpPing.source.contains("forbidden", ignoreCase = true)
                        )

                        if (isExpiredOrUnauthorized) {
                            account.cookieExpired = true
                            onAccountUpdated(account)
                            vlogManager?.logError(
                                "[PRE-FLIGHT] Cookies hết hạn hoặc không có quyền truy cập (401/403)",
                                "Tài khoản ${account.email} trả về source: ${httpPing?.source ?: "HTTP_TIMEOUT"}. Đánh dấu cookieExpired = true và bỏ qua.",
                                account.email
                            )
                            onStatusUpdate("Tài khoản: ${account.email}", "Cookies hết hạn (401/403). Bỏ qua.")
                            continue
                        } else if (httpPing == null || !httpPing.isSuccess) {
                            vlogManager?.logNetwork(
                                "[NET-GUARD] Mạng yếu hoặc chập chờn khi kết nối cho ${account.email}",
                                "Source: ${httpPing?.source ?: "HTTP_TIMEOUT"}. Tạm hoãn 60s thay vì đánh dấu hết hạn cookie.",
                                account.email
                            )
                            onStatusUpdate("Tài khoản: ${account.email}", "Mạng yếu/timeout. Tạm hoãn 60s...")
                            allAccountsFinished = false
                            earliestCooldownUntilMs = minOf(earliestCooldownUntilMs, System.currentTimeMillis() + 60_000L)
                            continue
                        } else if (httpPing.availablePoints > 0) {
                            account.cookieExpired = false
                            latestBalanceMap[account.id] = httpPing
                            val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                            if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                account.baselinePointsToday = httpPing.availablePoints
                                baselineDateMap[account.id] = todayStr
                            }
                            applyBalancePreservingExhaustedCap(account, httpPing, todayStr)
                            val exhaustedDailyRetries = areAllDailySetOffersExhausted(account.id, extractDailySetSubset(httpPing.dailyTasks), todayStr)
                            if (httpPing.dailySetTotal > 0) {
                                account.dailySetDone = httpPing.dailySetDone || exhaustedDailyRetries
                                account.dailySetProgress = httpPing.dailySetProgress
                                account.dailySetTotal = httpPing.dailySetTotal
                            }
                            if (httpPing.dailySetDone || exhaustedDailyRetries) {
                                phase0CompletedDateMap[account.id] = todayStr
                            }
                            onAccountUpdated(account)
                        }
                    }

                    // Pre-flight HTTP fetch nếu chưa có số dư mới nhất
                    var latestBalance = latestBalanceMap[account.id]
                        ?: RewardsBalanceExtractor.latestTelemetryMap[account.id]
                        ?: RewardsBalanceExtractor.latestTelemetryMap[account.email]

                    if (latestBalance == null && !account.cookiesSnapshot.isNullOrBlank()) {
                        try {
                            val httpBal = extractPointsForAccount(account)
                            if (httpBal.isSuccess && httpBal.availablePoints > 0) {
                                latestBalanceMap[account.id] = httpBal
                                latestBalance = httpBal
                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                    account.baselinePointsToday = httpBal.availablePoints
                                    baselineDateMap[account.id] = todayStr
                                }
                                applyBalancePreservingExhaustedCap(account, httpBal, todayStr)
                                val exhaustedDailyRetries = areAllDailySetOffersExhausted(account.id, extractDailySetSubset(httpBal.dailyTasks), todayStr)
                                if (httpBal.dailySetTotal > 0) {
                                    account.dailySetDone = httpBal.dailySetDone || exhaustedDailyRetries
                                    account.dailySetProgress = httpBal.dailySetProgress
                                    account.dailySetTotal = httpBal.dailySetTotal
                                }
                                if (httpBal.dailySetDone || exhaustedDailyRetries) {
                                    phase0CompletedDateMap[account.id] = todayStr
                                }
                                onAccountUpdated(account)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                    }

                    // 1. ACCOUNT-LEVEL CHECK ([SKIP-ACC]):
                    val dailySetDone = (latestBalance?.dailySetDone == true) ||
                        (phase0CompletedDateMap[account.id] == todayStr) ||
                        (latestBalance != null && areAllDailySetOffersExhausted(account.id, extractDailySetSubset(latestBalance.dailyTasks), todayStr))
                    val claimablePoints = latestBalance?.claimablePoints ?: account.claimablePoints
                    val claimAttemptsToday = claimAttemptCountMap["$todayStr:${account.id}"] ?: 0
                    val canAttemptClaim = claimablePoints > 0 && claimAttemptsToday < maxOf(1, maxClaimRetries)
                    val isDapiDone = isDapiPhaseDoneForAccount(account, todayStr)
                    val isBingAppDone = isBingApp60Of60Done(account, todayStr)
                    val isActDone = (dailySetDone && !canAttemptClaim && isDapiDone) || isBingAppDone

                    val nextSearchPhase = evaluateNextSearchPhase(account)
                    val isSearchDone = nextSearchPhase == SearchPhase.DONE

                    if (isActDone && isSearchDone) {
                        if (!account.dailySetDone || account.lastActiveDate != todayStr) {
                            account.dailySetDone = true
                            account.lastActiveDate = todayStr
                            onAccountUpdated(account)
                        }
                        val readMaxDisplay = if (account.readMax > 0) account.readMax else 30
                        val readMark = if (account.readProgress >= readMaxDisplay) "✓" else "!"
                        vlogManager?.logPoints(
                            "[SKIP-ACC] Bỏ qua tài khoản ${account.email} (0s delay)",
                            "Đã hoàn thành tất cả các mục: Activities ✓ (Daily Set: Xong, Claim: $claimablePoints, DAPI: Xong) | PC: ${account.pcProgress}/${account.pcMax} ✓ | Mobile: ${account.mobileProgress}/${account.mobileMax} ✓ | Edge: ${account.edgeProgress}/${account.edgeMax} ✓ | Read: ${account.readProgress}/$readMaxDisplay $readMark",
                            account.email
                        )
                        continue
                    }

                    // Có ít nhất một nhiệm vụ (Activities hoặc Search) chưa hoàn thành
                    allAccountsFinished = false

                    if (account.completedSearchesToday >= account.targetSearches) {
                        vlogManager?.logPoints(
                            "Kích hoạt cày bù điểm: ${account.completedSearchesToday}/${account.targetSearches} lượt",
                            "Chưa max rổ điểm (PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax}). Giới hạn cày bù: $maxCatchUpSearches lượt.",
                            account.email
                        )
                    }

                    // Kiểm tra Proxy Circuit Breaker
                    val proxy = resolveProxyForAccount(account)
                    if (!proxy.isNullOrBlank() && profileManager?.isProxyCircuitOpen(proxy) == true) {
                        vlogManager?.logNetwork("Bỏ qua tài khoản do Proxy Circuit Breaker mở (Proxy lỗi liên tiếp >= 3 lần)", proxy, account.email)
                        continue
                    }

                    allAccountsFinished = false
                    anyAccountExecutedInCycle = true
                    wakeLockHelper?.acquire("Cycle_${account.id}")
                    val currentProgressLabel = if (isCatchingUp) {
                        "Tiến độ cày bù: ${account.completedSearchesToday}/$maxCatchUpSearches lượt"
                    } else {
                        "Tiến độ: ${account.completedSearchesToday}/${account.targetSearches} lượt"
                    }
                    onStatusUpdate("Đang chạy tài khoản: ${account.email}", currentProgressLabel)
                    vlogManager?.logSearch("Bắt đầu xử lý tài khoản: ${account.email}", currentProgressLabel, account.email)

                    // G4.1: Tuần tự hoán đổi Session qua Mutex ProfileManager.switchSession
                    val currentProfileId = profileManager?.activeAccountId
                    if (targetWebView != null && (activeSessionAccountId != account.id || currentProfileId != account.id || profileManager?.activeEffectiveProxy != proxy)) {
                        val switched = profileManager?.switchSession(
                            targetWebView,
                            account,
                            globalProxy = globalProxyProvider?.invoke(),
                            onSaveOutgoingSession = { accId, cookies ->
                                val acc = candidateAccounts.find { it.id == accId }
                                if (acc != null) {
                                    acc.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(acc.cookiesSnapshot, cookies)
                                    onAccountUpdated(acc)
                                }
                            }
                        ) ?: true
                        if (switched) {
                            activeSessionAccountId = account.id
                        } else {
                            vlogManager?.logError("Lỗi hoán đổi Session", "Không thể hoán đổi session cho ${account.email} trong thời gian quy định.", account.email)
                            continue
                        }
                    }

                    // 0. PHASE 0: PRE-SEARCH ACTIVITIES (Ready to Claim, Daily Set, Streak Protection)
                    val isBingAppDoneAt0 = isBingApp60Of60Done(account, todayStr)
                    val isPhase0Needed = (phase0CompletedDateMap[account.id] != todayStr) || canAttemptClaim
                    if (isBingAppDoneAt0 || isActDone) {
                        vlogManager?.logPoints(
                            "[SKIP-ACT] Bỏ qua Phase 0 (Pre-Search Activities)",
                            if (isBingAppDoneAt0) "Tài khoản ${account.email} đã hoàn thành trọn vẹn Bing App 60/60 điểm hôm nay (Daily Set & MSN Read to Earn)"
                            else "Tài khoản ${account.email} đã xong Daily Set và claimablePoints == 0",
                            account.email
                        )
                        phase0CompletedDateMap[account.id] = todayStr
                    } else if (targetWebView != null && isPhase0Needed && isRunning && isActive) {
                        try {
                            wakeLockHelper?.acquire("Cycle_${account.id}")
                            onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0] Khởi chạy Pre-Search Activities...")
                            vlogManager?.logSearch("Bắt đầu Phase 0: Pre-Search Activities", "Nạp cookies và lấy danh sách Daily Set...", account.email)

                            // 0. Quản lý session boundary & proxy isolation giữa các tài khoản qua ProfileManager.switchSession
                            if (activeSessionAccountId != account.id || profileManager?.activeEffectiveProxy != proxy) {
                                val switched = profileManager?.switchSession(
                                    targetWebView,
                                    account,
                                    globalProxy = globalProxyProvider?.invoke(),
                                    onSaveOutgoingSession = { accId, cookies ->
                                        val acc = accountList.find { it.id == accId }
                                        if (acc != null) {
                                            acc.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(acc.cookiesSnapshot, cookies)
                                            onAccountUpdated(acc)
                                        }
                                    }
                                ) ?: true
                                if (switched) {
                                    activeSessionAccountId = account.id
                                } else {
                                    vlogManager?.logError("Lỗi hoán đổi Session Phase 0", "Không thể hoán đổi session cho ${account.email}.", account.email)
                                    continue
                                }
                            }

                            withContext(AndroidMainDispatcher) {
                                val preset = FingerprintEngine.getEdgePresetForAccount(account.id, false)
                                targetWebView.settings.userAgentString = preset.userAgent
                            }

                            // 2. Lấy Daily Tasks từ HTTP JSON API chính thống (Authoritative)
                            var httpPreBal = extractPointsForAccount(account)
                            var dailyTasks = httpPreBal.dailyTasks

                            // 3. Fallback: Nếu HTTP chưa có Daily Tasks, mới mở Dashboard trên WebView
                            if (dailyTasks.isEmpty()) {
                                loadUrlAndWait(targetWebView, "https://rewards.bing.com/dashboard", 25000L)
                                safeDelay(2500L)
                                dailyTasks = RewardsActivitiesEngine.extractDailyTasks(targetWebView)

                                // Sau khi Silent SSO hoàn tất trên WebView: Đồng bộ cookie mới vào DB
                                try {
                                    android.webkit.CookieManager.getInstance().flush()
                                    val freshCookies = profileManager?.dumpCookiesForProfile(account.id)
                                    if (!freshCookies.isNullOrBlank() && (freshCookies.contains("_RwBf") || freshCookies.contains("_U") || freshCookies.contains("MSPAuth") || freshCookies.contains("MUID") || freshCookies.contains("KievRPSToken"))) {
                                        account.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, freshCookies)
                                        onAccountUpdated(account)
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                }
                            }

                            // 4. Streak Protection & Ready to Claim (2 nhịp + Verify HTTP) trong Phase 0
                            try {
                                val currentClaimable = httpPreBal.claimablePoints.takeIf { it > 0 } ?: account.claimablePoints
                                if (currentClaimable > 0 && (claimAttemptCountMap["$todayStr:${account.id}"] ?: 0) < maxOf(1, maxClaimRetries)) {
                                    vlogManager?.logPoints(
                                        "[READY-TO-CLAIM] Phát hiện +$currentClaimable điểm thưởng chờ nhận ở Phase 0",
                                        "Mở hộp thoại ?modal=pointsclaim và thực hiện nhận điểm 2 nhịp...",
                                        account.email
                                    )
                                    executeTwoStepPointClaim(targetWebView, account)
                                } else {
                                    val curUrl = withContext(AndroidMainDispatcher) { targetWebView.url } ?: ""
                                    if (!curUrl.contains("rewards.bing.com/dashboard")) {
                                        loadUrlAndWait(targetWebView, "https://rewards.bing.com/dashboard", 15000L)
                                        safeDelay(1500L)
                                    }
                                    RewardsActivitiesEngine.enableStreakProtection(targetWebView)
                                    val claimed = RewardsActivitiesEngine.executeReadyToClaim(targetWebView)
                                    if (claimed) {
                                        vlogManager?.logPoints("[READY-TO-CLAIM] Đã nhận điểm thưởng Ready to claim thành công", "", account.email)
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }

                            // 5. Trích xuất Daily Tasks và giải quyết các task chưa hoàn thành
                            var allDailyTasksDone = false
                            val dailySetTasks = extractDailySetSubset(dailyTasks)
                            var detectedDailyTaskCount = dailySetTasks.size

                            try {
                                val uncompletedDailyTasks = dailySetTasks.filter { !it.isCompleted }
                                val pendingTasks = uncompletedDailyTasks.filter { task ->
                                    val offerKey = task.offerId.ifBlank { task.id.ifBlank { task.destinationUrl.hashCode().toString() } }
                                    getOfferTriedCount(account.id, offerKey, todayStr) < 2
                                }

                                if (pendingTasks.isNotEmpty()) {
                                    allDailyTasksDone = true
                                    // Sắp xếp ưu tiên: Khám phá/Tin tức (urlreward, news) -> Poll -> Quiz
                                    val sortedTasks = pendingTasks.sortedBy { task ->
                                        val isQuizUrl = task.destinationUrl.contains("form=dsetqu", ignoreCase = true)
                                        when {
                                            isQuizUrl || task.taskType.equals("quiz", ignoreCase = true) -> 2
                                            task.taskType.equals("poll", ignoreCase = true) -> 1
                                            task.taskType.equals("urlreward", ignoreCase = true) || task.taskType.equals("news", ignoreCase = true) -> 0
                                            else -> 3
                                        }
                                    }

                                    vlogManager?.logPoints(
                                        "Phát hiện ${sortedTasks.size}/${dailySetTasks.size} Daily Set Tasks chưa hoàn thành",
                                        sortedTasks.joinToString("; ") { "${it.title} (+${it.pointValue}đ, ${it.taskType})" },
                                        account.email
                                    )

                                    for ((index, task) in sortedTasks.withIndex()) {
                                        if (!isRunning || !isActive) break

                                        val offerKey = task.offerId.ifBlank { task.id.ifBlank { task.destinationUrl.hashCode().toString() } }
                                        val tryNum = incrementOfferTriedCount(account.id, offerKey, todayStr)
                                        val isQuizTask = task.taskType.equals("quiz", ignoreCase = true) ||
                                            task.destinationUrl.contains("form=dsetqu", ignoreCase = true)
                                        val isPollTask = !isQuizTask && (
                                            task.taskType.equals("poll", ignoreCase = true) ||
                                                task.destinationUrl.contains("btoption", ignoreCase = true)
                                            )
                                        val isDomTask = isQuizTask || isPollTask || RewardsActivitiesEngine.isInteractiveDomTask(task)

                                        onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0] Giải Daily Set: ${task.title} (+${task.pointValue}đ)")
                                        vlogManager?.logPoints(
                                            "[TASK] Bắt đầu thực hiện Daily Set (${index + 1}/${sortedTasks.size}, thử $tryNum/2): ${task.title} (+${task.pointValue}đ)",
                                            "Loại: ${task.taskType} | URL: ${task.destinationUrl}",
                                            account.email
                                        )

                                        var taskSuccess = false

                                        // Error isolation: try/catch per task để không để 1 thẻ lỗi làm hỏng toàn bộ pipeline
                                        try {
                                            if (!isDomTask) {
                                                // 1. Thẻ urlreward / news: Chạy THUẦN HTTP qua executeSingleTaskHttp (ưu tiên reportActivity -> fallback fast redirect 1.5s ngầm)
                                                // Tuyệt đối không fallback WebView cho urlreward
                                                val httpOk = RewardsActivitiesEngine.executeSingleTaskHttp(
                                                    task,
                                                    account.cookiesSnapshot,
                                                    account.customUserAgent,
                                                    resolveProxyForAccount(account)
                                                )
                                                taskSuccess = httpOk
                                                if (httpOk) {
                                                    vlogManager?.logPoints(
                                                        "[FAST-PATH] Hoàn thành Daily Task urlreward qua HTTP: ${task.title} (+${task.pointValue}đ)",
                                                        "OfferId: ${task.offerId} | Destination: ${task.destinationUrl}",
                                                        account.email
                                                    )
                                                } else {
                                                    vlogManager?.logPoints(
                                                        "[FAST-PATH] Đã gửi HTTP fast redirect cho Daily Task urlreward: ${task.title}",
                                                        "OfferId: ${task.offerId} | Chờ server Bing cập nhật điểm",
                                                        account.email
                                                    )
                                                    taskSuccess = true
                                                }
                                            } else {
                                                // 2. Thẻ Quiz / Poll:
                                                // A. Ưu tiên thử qua API chính thống trước nếu có authKey
                                                val apiEarned = if (task.authKey.isNotBlank()) {
                                                    try {
                                                        RewardsActivitiesEngine.reportActivityViaBingApi(
                                                            task,
                                                            account.cookiesSnapshot ?: "",
                                                            account.customUserAgent ?: "",
                                                            resolveProxyForAccount(account)
                                                        )
                                                    } catch (e: Exception) {
                                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                                        0
                                                    }
                                                } else 0

                                                if (apiEarned > 0) {
                                                    taskSuccess = true
                                                    vlogManager?.logPoints(
                                                        "[FAST-PATH] Hoàn thành Daily Task quiz/poll qua HTTP ReportActivity API: ${task.title} (+${apiEarned}đ)",
                                                        "OfferId: ${task.offerId} | Destination: ${task.destinationUrl}",
                                                        account.email
                                                    )
                                                } else {
                                                    // B. Chỉ fallback WebView khi task là quiz/poll cần click DOM
                                                    // Kích hoạt HTTP Redirect tracker trước ngầm
                                                    try {
                                                        RewardsActivitiesEngine.executeFastRedirect(
                                                            task.destinationUrl,
                                                            task.offerId,
                                                            account.cookiesSnapshot,
                                                            resolveProxyForAccount(account),
                                                            account.customUserAgent ?: RewardsActivitiesEngine.DEFAULT_DESKTOP_EDGE_UA
                                                        )
                                                    } catch (e: Exception) {
                                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                                    }

                                                    // Đảm bảo WebView đang ở trên trang Dashboard trước khi gọi solveDailyTask
                                                    ensureOnRewardsDashboard(targetWebView, account)
                                                    val cardClickTask = task.copy(taskType = "urlreward")
                                                    val clickedOnDashboard = RewardsActivitiesEngine.solveDailyTask(targetWebView, cardClickTask)
                                                    if (clickedOnDashboard) {
                                                        val dwellSecs = Random.nextLong(3500L, 5000L)
                                                        safeDelay(dwellSecs)
                                                        val afterUrl = withContext(AndroidMainDispatcher) { targetWebView.url } ?: ""
                                                        if (afterUrl.contains("rewards.bing.com/dashboard", ignoreCase = true) &&
                                                            task.destinationUrl.isNotBlank() &&
                                                            task.destinationUrl.startsWith("https://")
                                                        ) {
                                                            loadUrlAndWait(targetWebView, task.destinationUrl, 15000L)
                                                            safeDelay(2500L)
                                                        }
                                                        taskSuccess = when {
                                                            isQuizTask -> runQuizStepLoop(targetWebView, task)
                                                            isPollTask -> {
                                                                val pollOk = RewardsActivitiesEngine.solveDailyTask(targetWebView, task.copy(taskType = "poll"))
                                                                safeDelay(2000L)
                                                                pollOk
                                                            }
                                                            else -> true
                                                        }
                                                        loadUrlAndWait(targetWebView, "https://rewards.bing.com/dashboard", 15000L)
                                                        safeDelay(2000L)
                                                    } else if (task.destinationUrl.isNotBlank() && task.destinationUrl.startsWith("https://")) {
                                                        loadUrlAndWait(targetWebView, task.destinationUrl, 15000L)
                                                        val dwellSecs = Random.nextLong(3500L, 5000L)
                                                        safeDelay(dwellSecs)
                                                        taskSuccess = when {
                                                            isQuizTask -> runQuizStepLoop(targetWebView, task)
                                                            isPollTask -> {
                                                                val pollOk = RewardsActivitiesEngine.solveDailyTask(targetWebView, task.copy(taskType = "poll"))
                                                                safeDelay(2000L)
                                                                pollOk
                                                            }
                                                            else -> true
                                                        }
                                                        loadUrlAndWait(targetWebView, "https://rewards.bing.com/dashboard", 15000L)
                                                        safeDelay(2000L)
                                                    } else {
                                                        taskSuccess = false
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            taskSuccess = false
                                            vlogManager?.logError("Lỗi cách ly khi xử lý thẻ Daily Set ${task.title}", e.message, account.email)
                                        }

                                        if (taskSuccess) {
                                            vlogManager?.logPoints(
                                                "[TASK] Hoàn thành Daily Set (${index + 1}/${sortedTasks.size}): ${task.title} (+${task.pointValue}đ)",
                                                "Trạng thái: Đã thực thi thành công",
                                                account.email
                                            )
                                        } else {
                                            allDailyTasksDone = false
                                            vlogManager?.logPoints(
                                                "[TASK] Đã trigger Daily Set (${index + 1}/${sortedTasks.size}): ${task.title}",
                                                "Trạng thái: Hoàn tất thao tác (chờ Microsoft đồng bộ điểm)",
                                                account.email
                                            )
                                        }

                                        // Nghỉ giãn cách ngẫu nhiên 10-18s giữa các task
                                        if (index < sortedTasks.size - 1 && isRunning && isActive) {
                                            val taskDelay = Random.nextInt(10, 19)
                                            onStatusUpdate("Tài khoản: ${account.email}", "Nghỉ giãn cách $taskDelay giây trước Daily Task tiếp...")
                                            vlogManager?.logCooldown("Nghỉ giãn cách giữa các Daily Task: $taskDelay giây", account = account.email)
                                            safeDelay(taskDelay * 1000L)
                                        }
                                    }
                                } else if (dailySetTasks.isNotEmpty() && uncompletedDailyTasks.isEmpty()) {
                                    allDailyTasksDone = true
                                    phase0CompletedDateMap[account.id] = todayStr
                                    vlogManager?.logPoints("Tất cả Daily Set Tasks đã hoàn thành (${dailySetTasks.size}/${dailySetTasks.size})", "Không có nhiệm vụ Daily Set nào còn tồn đọng.", account.email)
                                } else if (dailySetTasks.isNotEmpty()) {
                                    allDailyTasksDone = true
                                    phase0CompletedDateMap[account.id] = todayStr
                                    vlogManager?.logPoints("Bỏ qua Daily Set Tasks còn lại", "Tất cả nhiệm vụ chưa xong đã đạt giới hạn thử tối đa (2/2) hôm nay.", account.email)
                                } else {
                                    val curUrl = withContext(AndroidMainDispatcher) { targetWebView.url }
                                    vlogManager?.logError("Không trích xuất được Daily Tasks từ Dashboard (trang chưa nạp xong hoặc chưa login)", "URL: $curUrl", account.email)
                                }

                                // 6. Keep Earning: Sau Daily Set trong Phase 0, duyệt morePromotions (dailyTasks) chưa xong và isSafePromotion, chạy executeSingleTaskHttp với 1000-1500ms delay.
                                val morePromoTasks = dailyTasks.filterNot { dailySetTasks.contains(it) }
                                    .filter { task ->
                                        !task.isCompleted &&
                                        RewardsActivitiesEngine.isSafePromotion(task.title, task.destinationUrl, task.pointValue) &&
                                        (task.destinationUrl.isNotBlank() || (task.authKey.isNotBlank() && task.userId.isNotBlank())) &&
                                        getOfferTriedCount(
                                            account.id,
                                            task.offerId.ifBlank { task.id.ifBlank { task.destinationUrl.hashCode().toString() } },
                                            todayStr
                                        ) < 2
                                    }

                                if (morePromoTasks.isNotEmpty()) {
                                    vlogManager?.logPoints(
                                        "[KEEP-EARNING] Phát hiện ${morePromoTasks.size} ưu đãi bổ sung (More Promotions) chưa hoàn thành",
                                        morePromoTasks.joinToString("; ") { "${it.title} (+${it.pointValue}đ)" },
                                        account.email
                                    )
                                    for ((pIdx, pTask) in morePromoTasks.withIndex()) {
                                        if (!isRunning || !isActive) break
                                        val promoOfferKey = pTask.offerId.ifBlank { pTask.id.ifBlank { pTask.destinationUrl.hashCode().toString() } }
                                        incrementOfferTriedCount(account.id, promoOfferKey, todayStr)
                                        onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0] Keep Earning: ${pTask.title} (+${pTask.pointValue}đ)")
                                        val fastOk = try {
                                            RewardsActivitiesEngine.executeSingleTaskHttp(
                                                pTask,
                                                account.cookiesSnapshot,
                                                account.customUserAgent,
                                                resolveProxyForAccount(account)
                                            )
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            false
                                        }
                                        if (fastOk) {
                                            vlogManager?.logPoints(
                                                "[KEEP-EARNING] Hoàn thành qua HTTP ReportActivity/Redirect (${pIdx + 1}/${morePromoTasks.size}): ${pTask.title} (+${pTask.pointValue}đ)",
                                                "OfferId: ${pTask.offerId} | Destination: ${pTask.destinationUrl}",
                                                account.email
                                            )
                                        }
                                        val promoDelay = Random.nextLong(1000L, 1501L)
                                        safeDelay(promoDelay)
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                allDailyTasksDone = false
                                vlogManager?.logError("Lỗi trong quá trình xử lý Daily Tasks", e.message, account.email)
                            }

                            // 5. Sau khi hoàn thành Phase 0: Đo lại balance và cập nhật AccountProfile từ HTTP API chính thống
                            safeDelay(2000L)
                            val postPhase0Bal = extractPointsForAccount(account)
                            if (postPhase0Bal.isSuccess && postPhase0Bal.availablePoints > 0) {
                                latestBalanceMap[account.id] = postPhase0Bal
                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                    account.baselinePointsToday = postPhase0Bal.availablePoints
                                    baselineDateMap[account.id] = todayStr
                                }
                                applyBalancePreservingExhaustedCap(account, postPhase0Bal, todayStr)

                                val postDailySetOffers = extractDailySetSubset(postPhase0Bal.dailyTasks).ifEmpty { dailySetTasks }
                                val allOffersExhausted = areAllDailySetOffersExhausted(account.id, postDailySetOffers, todayStr)
                                val isDone = postPhase0Bal.dailySetDone || allOffersExhausted
                                if (isDone) {
                                    phase0CompletedDateMap[account.id] = todayStr
                                } else {
                                    phase0CompletedDateMap.remove(account.id)
                                }
                                val effTotal = if (postPhase0Bal.dailySetTotal > 0) postPhase0Bal.dailySetTotal else (if (detectedDailyTaskCount > 0) detectedDailyTaskCount else (if (account.dailySetTotal > 0) account.dailySetTotal else 3))
                                account.dailySetDone = isDone
                                account.dailySetTotal = effTotal
                                account.dailySetProgress = if (postPhase0Bal.dailySetDone) effTotal else postPhase0Bal.dailySetProgress
                                account.claimablePoints = postPhase0Bal.claimablePoints
                                account.cookieExpired = false

                                // Ready to claim: Gọi executeTwoStepPointClaim ở cuối Phase 0 nếu vẫn còn claimablePoints > 0
                                if (account.claimablePoints > 0 && (claimAttemptCountMap["$todayStr:${account.id}"] ?: 0) < maxOf(1, maxClaimRetries)) {
                                    executeTwoStepPointClaim(targetWebView, account)
                                }

                                onAccountUpdated(account)

                                vlogManager?.logPoints(
                                    "Hoàn thành Phase 0 (Pre-Search Activities) | Số dư: ${account.availablePoints} pts",
                                    "Chuỗi ngày: ${account.streakCount} | Đã kiếm hôm nay: +${account.pointsEarnedToday} pts (PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax})",
                                    account.email
                                )
                            } else if (areAllDailySetOffersExhausted(account.id, dailySetTasks, todayStr)) {
                                phase0CompletedDateMap[account.id] = todayStr
                                account.dailySetDone = true
                                onAccountUpdated(account)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            vlogManager?.logError("Lỗi trong Phase 0 (Pre-Search Activities)", e.message, account.email)
                        }
                    } else if (targetWebView != null && latestBalanceMap[account.id] == null) {
                        // PRE-FLIGHT BASELINE EXTRACTION FALLBACK NẾU CHƯA CÓ LATEST BALANCE
                        try {
                            val fallbackProfileId = profileManager?.activeAccountId
                            if (activeSessionAccountId != account.id || fallbackProfileId != account.id || profileManager?.activeEffectiveProxy != proxy) {
                                val switched = profileManager?.switchSession(
                                    targetWebView,
                                    account,
                                    globalProxy = globalProxyProvider?.invoke(),
                                    onSaveOutgoingSession = { accId, cookies ->
                                        val acc = candidateAccounts.find { it.id == accId }
                                        if (acc != null) {
                                            acc.cookiesSnapshot = cookies
                                            onAccountUpdated(acc)
                                        }
                                    }
                                ) ?: true
                                if (switched) {
                                    activeSessionAccountId = account.id
                                } else {
                                    vlogManager?.logError("Lỗi hoán đổi Session Baseline", "Không thể hoán đổi session cho ${account.email}.", account.email)
                                }
                            } else {
                                profileManager?.restoreCookiesForProfile(account.id, account.cookiesSnapshot)
                            }
                            var preBalance = RewardsBalanceExtractor.extractPoints(targetWebView)
                            if (!preBalance.isSuccess || preBalance.availablePoints <= 0 || preBalance.pcMax == 0) {
                                preBalance = extractPointsForAccount(account)
                            }
                            if (preBalance.isSuccess && preBalance.availablePoints > 0) {
                                latestBalanceMap[account.id] = preBalance
                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                    account.baselinePointsToday = preBalance.availablePoints
                                    baselineDateMap[account.id] = todayStr
                                }
                                applyBalancePreservingExhaustedCap(account, preBalance, todayStr)

                                onAccountUpdated(account)

                                vlogManager?.logPoints(
                                    "Bóc tách điểm xuất phát (Baseline): ${account.availablePoints} pts",
                                    "Chuỗi ngày: ${account.streakCount} | Đã kiếm hôm nay: ${account.pointsEarnedToday} pts (PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax}) [${preBalance.source}]",
                                    account.email
                                )
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            vlogManager?.logError("Lỗi bóc tách điểm trước chu kỳ", e.message, account.email)
                        }
                    }

                    // 0.5. PHASE 0.5: DAPI MOBILE ACTIVITIES (Sapphire Daily Check-in & MSN Read to Earn)
                    val isBingAppDoneAt05 = isBingApp60Of60Done(account, todayStr)
                    val isPhase05Needed = !isBingAppDoneAt05 && !isDapiPhaseDoneForAccount(account, todayStr)
                    if (isBingAppDoneAt05) {
                        vlogManager?.logPoints(
                            "[SKIP-DAPI] Bỏ qua Phase 0.5 (DAPI Mobile Activities)",
                            "Tài khoản ${account.email} đã hoàn thành trọn vẹn Bing App 60/60 điểm hôm nay (Daily Set & MSN Read to Earn)",
                            account.email
                        )
                    } else if (isPhase05Needed && isRunning && isActive) {
                        try {
                            wakeLockHelper?.acquire("Cycle_${account.id}")
                            if (targetWebView != null) {
                                withContext(AndroidMainDispatcher) {
                                    val preset = FingerprintEngine.getEdgePresetForAccount(account.id, false)
                                    targetWebView.settings.userAgentString = preset.userAgent
                                }
                            }
                            onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0.5] Khởi chạy DAPI Mobile Activities...")
                            vlogManager?.logSearch("Bắt đầu Phase 0.5: DAPI Activities", "Kiểm tra và xác thực OAuth2 Bearer Token (Pure HTTP 302 / WebView)...", account.email)

                            // Luôn gọi DapiAuthManager.ensureValidToken (tận dụng Pure HTTP 302 từ account.cookiesSnapshot)
                            var dapiToken = DapiAuthManager.ensureValidToken(account, targetWebView, database)
                            if (dapiToken.isNullOrBlank()) {
                                val maxDapiAttempts = maxOf(1, dapiMaxRetries)
                                val attempts = incrementDapiAttempts(account.id, todayStr)
                                if (attempts >= maxDapiAttempts) {
                                    markPhase05CompletedToday(account.id, todayStr)
                                    val detailMsg = "Đã đạt giới hạn thử DAPI hôm nay. Bỏ qua DAPI cho ${account.email} (Vui lòng kiểm tra cookies hoặc cấp lại quyền login.live.com)."
                                    vlogManager?.logSearch(
                                        "Phase 0.5: Không thể lấy DAPI token (Lần $attempts/$maxDapiAttempts)",
                                        detailMsg,
                                        account.email
                                    )
                                } else {
                                    vlogManager?.logError(
                                        "Phase 0.5: Không thể lấy DAPI token (Lần $attempts/$maxDapiAttempts)",
                                        "Bỏ qua DAPI ở lượt này cho ${account.email}, sẽ thử lại ở lượt sau.",
                                        account.email
                                    )
                                }
                            } else {
                                dapiAttemptCountMap.remove("$todayStr:${account.id}")
                                vlogManager?.logSearch("Phase 0.5: Đã có Bearer Token", "Tiến hành Sapphire Daily Check-in & MSN Read to Earn...", account.email)

                                // 1. Check in to the Bing App (type 103 Bing App Streak + type 101 Sapphire Check-In)
                                if (!isCheckInCompletedToday(account.id, todayStr)) {
                                    var checkInRes = DapiActivitiesEngine.executeDailyCheckIn(dapiToken, resolveProxyForAccount(account))
                                    if (!checkInRes.isSuccess && (checkInRes.isUnauthorized || checkInRes.message.contains("401"))) {
                                        account.dapiAccessToken = null
                                        account.dapiTokenExpiryTimestampMs = 0L
                                        onAccountUpdated(account)
                                        val refreshedToken = DapiAuthManager.ensureValidToken(account, targetWebView, database)
                                        if (!refreshedToken.isNullOrBlank()) {
                                            dapiToken = refreshedToken
                                            checkInRes = DapiActivitiesEngine.executeDailyCheckIn(dapiToken, resolveProxyForAccount(account))
                                        }
                                    }
                                    if (checkInRes.isSuccess) {
                                        markCheckInCompletedToday(account.id, todayStr)
                                        if (checkInRes.pointsEarned > 0) {
                                            account.availablePoints += checkInRes.pointsEarned
                                            account.pointsEarnedToday += checkInRes.pointsEarned
                                            account.totalPoints = account.availablePoints
                                            vlogManager?.logPoints(
                                                "[DAPI] Check in to the Bing App (+${checkInRes.pointsEarned} pts)",
                                                checkInRes.message,
                                                account.email
                                            )
                                        } else if (checkInRes.alreadyCompleted) {
                                            vlogManager?.logPoints(
                                                "[DAPI] Check in to the Bing App",
                                                "Đã hoàn thành điểm danh Bing App hôm nay.",
                                                account.email
                                            )
                                        }
                                    } else {
                                        vlogManager?.logError("[DAPI] Check in to the Bing App", checkInRes.message, account.email)
                                    }
                                } else {
                                    vlogManager?.logPoints(
                                        "[DAPI] Check in to the Bing App",
                                        "Đã hoàn thành điểm danh hôm nay, bỏ qua.",
                                        account.email
                                    )
                                }

                                // 2. MSN Read to Earn (Tối đa 30đ/ngày nếu readProgress < 30)
                                if (account.readProgress < 30 && !dapiToken.isNullOrBlank()) {
                                    onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0.5] Đang đọc báo MSN (${account.readProgress}/30đ)...")
                                    var readRes = DapiActivitiesEngine.executeReadToEarn(
                                        token = dapiToken,
                                        currentProgress = account.readProgress,
                                        maxPoints = 30,
                                        onArticleDone = { index, newProg ->
                                            account.readProgress = newProg
                                            onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0.5] Đọc báo #$index (+3đ) -> $newProg/30đ")
                                        },
                                        proxyUrl = resolveProxyForAccount(account)
                                    )

                                    if (!readRes.isSuccess && (readRes.isUnauthorized || readRes.message.contains("401"))) {
                                        account.dapiAccessToken = null
                                        account.dapiTokenExpiryTimestampMs = 0L
                                        onAccountUpdated(account)
                                        val refreshedToken = DapiAuthManager.ensureValidToken(account, targetWebView, database)
                                        if (!refreshedToken.isNullOrBlank()) {
                                            dapiToken = refreshedToken
                                            readRes = DapiActivitiesEngine.executeReadToEarn(
                                                token = dapiToken,
                                                currentProgress = account.readProgress,
                                                maxPoints = 30,
                                                onArticleDone = { index, newProg ->
                                                    account.readProgress = newProg
                                                    onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0.5] Đọc báo #$index (+3đ) -> $newProg/30đ")
                                                },
                                                proxyUrl = resolveProxyForAccount(account)
                                            )
                                        }
                                    }

                                    if (readRes.isSuccess) {
                                        account.readProgress = maxOf(account.readProgress, readRes.currentProgress)
                                        if (readRes.pointsEarned > 0) {
                                            account.availablePoints += readRes.pointsEarned
                                            account.pointsEarnedToday += readRes.pointsEarned
                                            account.totalPoints = account.availablePoints
                                            vlogManager?.logPoints(
                                                "[DAPI] MSN Read to Earn (+${readRes.pointsEarned} pts)",
                                                "Đã đọc ${readRes.articlesRead} bài báo. Tiến độ: ${account.readProgress}/30 pts",
                                                account.email
                                            )
                                        } else if (account.readProgress >= 30) {
                                            vlogManager?.logPoints(
                                                "[DAPI] MSN Read to Earn",
                                                "Đã hoàn thành đọc báo hôm nay trên server (${account.readProgress}/30 pts).",
                                                account.email
                                            )
                                        }
                                    } else {
                                        val attempts = incrementDapiAttempts(account.id, todayStr)
                                        val maxDapiAttempts = maxOf(1, dapiMaxRetries)
                                        vlogManager?.logError(
                                            "[DAPI] MSN Read to Earn thất bại (Lần $attempts/$maxDapiAttempts)",
                                            readRes.message,
                                            account.email
                                        )
                                    }
                                } else {
                                    vlogManager?.logPoints(
                                        "[DAPI] MSN Read to Earn",
                                        "Đã đạt tối đa đọc báo ${account.readProgress}/30 pts, bỏ qua.",
                                        account.email
                                    )
                                }

                                account.readMax = 30
                                if ((account.readProgress >= 30 && isCheckInCompletedToday(account.id, todayStr)) || getDapiAttempts(account.id, todayStr) >= maxOf(1, dapiMaxRetries)) {
                                    markPhase05CompletedToday(account.id, todayStr)
                                }
                                onAccountUpdated(account)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            vlogManager?.logError("Lỗi trong Phase 0.5 (DAPI Activities)", e.message, account.email)
                        }
                    }

                    // Lưu snapshot trước batch để đối soát Closed-Loop Basket Breakdown
                    val prePc = account.pcProgress
                    val preMobile = account.mobileProgress
                    val preEdge = account.edgeProgress
                    val preTotal = account.availablePoints
                    preBatchPointsMap[account.id] = preTotal

                    // 2. CHẠY MỘT CỤM (BATCH) THEO CÁC PHASE TÌM KIẾM
                    val loggedSkips = mutableSetOf<SearchPhase>()
                    var activePhase = evaluateNextSearchPhase(account, SearchPhase.PHASE_1_PC, loggedSkips)

                    var successfulSearchesInBatch = 0
                    if (activePhase == SearchPhase.DONE || account.completedSearchesToday >= maxCatchUpSearches) {
                        if (activePhase == SearchPhase.DONE && account.completedSearchesToday < account.targetSearches) {
                            account.completedSearchesToday = account.targetSearches
                            onAccountUpdated(account)
                        }
                        vlogManager?.logPoints(
                            "[SKIP-SEARCH] Bỏ qua tìm kiếm cho ${account.email}",
                            "Đã đạt tối đa các rổ tìm kiếm: PC: ${account.pcProgress}/${account.pcMax} | Mobile: ${account.mobileProgress}/${account.mobileMax} | Edge: ${account.edgeProgress}/${account.edgeMax}. Chuyển sang tài khoản tiếp theo.",
                            account.email
                        )
                        continue
                    } else {
                        val effectiveTarget = if (isCatchingUp) maxCatchUpSearches else account.targetSearches
                        val remainingSearches = effectiveTarget - account.completedSearchesToday
                        val searchesInThisBatch = minOf(batchSize, remainingSearches)

                        if (searchesInThisBatch > 0) {
                            for (i in 1..searchesInThisBatch) {
                                if (!isActive || !isRunning) break

                                activePhase = evaluateNextSearchPhase(account, activePhase, loggedSkips)
                                if (activePhase == SearchPhase.DONE) {
                                    if (account.completedSearchesToday < account.targetSearches) {
                                        account.completedSearchesToday = account.targetSearches
                                        onAccountUpdated(account)
                                    }
                                    vlogManager?.logPoints(
                                        "Tài khoản ${account.email} đã đạt trần tìm kiếm giữa chừng tại lượt $i/$searchesInThisBatch!",
                                        "Dừng batch sớm, chuyển sang bước đối soát điểm.",
                                        account.email
                                    )
                                    break
                                }

                                val keyword = keywordEngine.generateNextKeyword(account.id)
                                val isDesktop = activePhase.isDesktop
                                val preset = FingerprintEngine.getEdgePresetForAccount(account.id, isDesktop)

                                val phaseLabel = activePhase.label
                                val modeTag = if (isCatchingUp) " [Cày bù]" else ""
                                onStatusUpdate(
                                    "Tài khoản: ${account.email} ($i/$searchesInThisBatch)$modeTag",
                                    "[$phaseLabel] Từ khóa: \"$keyword\" (${preset.id})"
                                )

                                val success = onExecuteSearch(account, keyword, activePhase)
                                if (success) {
                                    successfulSearchesInBatch++
                                    account.completedSearchesToday++
                                    account.lastActiveDate = todayStr
                                    if (!proxy.isNullOrBlank()) {
                                        profileManager?.recordProxySuccess(proxy)
                                    }

                                    // 2. Passive Per-Search Telemetry: Trích xuất điểm tức thì từ SERP Bing (#id_rc)
                                    val currentWebView = targetWebView ?: activeWebView ?: webViewProvider?.invoke()
                                    if (currentWebView != null) {
                                        try {
                                            val serpBalance = RewardsBalanceExtractor.extractPointsFromSerp(currentWebView)
                                            if (serpBalance.isSuccess && serpBalance.availablePoints > account.availablePoints) {
                                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                                val fallbackBase = if (bDate != todayStr || account.baselinePointsToday <= 0) serpBalance.availablePoints else account.baselinePointsToday
                                                applyBalancePreservingExhaustedCap(account, serpBalance, todayStr, fallbackBase)
                                                baselineDateMap[account.id] = todayStr
                                            }
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            // Không ngắt luồng nếu trích xuất SERP gặp ngoại lệ
                                        }
                                    }
                                    onAccountUpdated(account)
                                } else {
                                    if (!proxy.isNullOrBlank()) {
                                        val isOpen = profileManager?.recordProxyFailure(proxy) == true
                                        if (isOpen) {
                                            vlogManager?.logNetwork("Proxy Circuit Breaker đã KÍCH HOẠT cho $proxy", "Sẽ tạm dừng dùng proxy này", account.email)
                                        }
                                    }
                                }

                                // Trong vòng lặp batch: Nếu account.isAllMaxedOutToday(todayStr) -> break khỏi batch, chuyển ngay sang tài khoản tiếp theo, không cooldown 920s.
                                if (account.isAllMaxedOutToday(todayStr)) {
                                    vlogManager?.logPoints(
                                        "Tài khoản ${account.email} đã đạt tối đa tất cả rổ điểm hôm nay (All Maxed Out)!",
                                        "Dừng sớm batch tại lượt $i/$searchesInThisBatch, chuyển ngay sang tài khoản tiếp theo.",
                                        account.email
                                    )
                                    break
                                }

                                // Giãn cách ngẫu nhiên (searchDelayMinSec đến searchDelayMaxSec giây) giữa các lượt trong cụm
                                if (i < searchesInThisBatch && isRunning) {
                                    val nextPhase = evaluateNextSearchPhase(account, activePhase, loggedSkips)
                                    if (nextPhase == SearchPhase.DONE) {
                                        break
                                    }
                                    val minSec = maxOf(5, searchDelayMinSec)
                                    val maxSec = maxOf(minSec + 1, searchDelayMaxSec + 1)
                                    val jitterSeconds = Random.nextInt(minSec, maxSec)
                                    onStatusUpdate("Tài khoản: ${account.email}", "Đang đợi $jitterSeconds giây cho lượt tiếp theo...")
                                    vlogManager?.logCooldown("Nghỉ giãn cách giữa 2 lượt: $jitterSeconds giây", account = account.email)
                                    safeDelay(jitterSeconds * 1000L)
                                }
                            }
                        }
                    }

                    // 3. CLOSED-LOOP BASKET BREAKDOWN & DELTA TRACKING:
                    var deltaPoints = -1
                    val currentWebView = targetWebView ?: activeWebView ?: webViewProvider?.invoke()
                    if (successfulSearchesInBatch > 0 && (currentWebView != null || !account.cookiesSnapshot.isNullOrBlank())) {
                        try {
                            // Settle delay 10s để hệ thống Microsoft cập nhật điểm vào các rổ
                            onStatusUpdate("Tài khoản: ${account.email}", "Đang đợi 10s đồng bộ điểm rổ...")
                            safeDelay(10000L)

                            var postBalance = if (currentWebView != null) {
                                var bal = RewardsBalanceExtractor.extractPoints(currentWebView)
                                if (!bal.isSuccess || bal.availablePoints <= 0 || bal.pcMax == 0) {
                                    bal = extractPointsForAccount(account)
                                }
                                bal
                            } else {
                                extractPointsForAccount(account)
                            }

                            var deltaPC = maxOf(0, postBalance.pcProgress - prePc)
                            var deltaMobile = maxOf(0, postBalance.mobileProgress - preMobile)
                            var deltaEdge = maxOf(0, postBalance.edgeProgress - preEdge)
                            var deltaTotal = maxOf(0, postBalance.availablePoints - preTotal)
                            var deltaBonus = maxOf(0, deltaTotal - (deltaPC + deltaMobile + deltaEdge))

                            // Nếu ΔTotal == 0: Grace Period Retry: delay 10s re-poll lần 2 trước khi tăng zeroDeltaBatchCountMap
                            if (deltaTotal == 0 && isRunning && isActive) {
                                vlogManager?.logPoints(
                                    "Grace Period Retry: ΔTotal == 0 sau batch ($successfulSearchesInBatch lượt)",
                                    "Đang đợi thêm 10s re-poll lần 2 để đảm bảo backend Microsoft cập nhật...",
                                    account.email
                                )
                                onStatusUpdate("Tài khoản: ${account.email}", "Đang đợi 10s re-poll điểm lần 2...")
                                safeDelay(10000L)

                                val retryBalance = if (currentWebView != null) {
                                    var bal = RewardsBalanceExtractor.extractPoints(currentWebView)
                                    if (!bal.isSuccess || bal.availablePoints <= 0) {
                                        bal = extractPointsForAccount(account)
                                    }
                                    bal
                                } else {
                                    extractPointsForAccount(account)
                                }

                                if (retryBalance.isSuccess && retryBalance.availablePoints > 0) {
                                    postBalance = retryBalance
                                    deltaPC = maxOf(0, postBalance.pcProgress - prePc)
                                    deltaMobile = maxOf(0, postBalance.mobileProgress - preMobile)
                                    deltaEdge = maxOf(0, postBalance.edgeProgress - preEdge)
                                    deltaTotal = maxOf(0, postBalance.availablePoints - preTotal)
                                    deltaBonus = maxOf(0, deltaTotal - (deltaPC + deltaMobile + deltaEdge))
                                }
                            }

                            deltaPoints = deltaTotal

                            if (postBalance.isSuccess && postBalance.availablePoints > 0) {
                                latestBalanceMap[account.id] = postBalance
                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                val fallbackBase = if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                    if (preTotal > 0) preTotal else postBalance.availablePoints
                                } else {
                                    account.baselinePointsToday
                                }
                                applyBalancePreservingExhaustedCap(account, postBalance, todayStr, fallbackBase)
                                baselineDateMap[account.id] = todayStr

                                onAccountUpdated(account)

                                // Ghi Vlog chi tiết từng rổ
                                val basketBreakdownLog = "[POINT] +${deltaTotal}đ (PC:+${deltaPC}, Mob:+${deltaMobile}, Edge:+${deltaEdge}, Bonus:+${deltaBonus}) | Kho: ${postBalance.pcProgress}/${account.pcMax} PC, ${postBalance.mobileProgress}/${account.mobileMax} Mob, ${postBalance.edgeProgress}/${account.edgeMax} Edge"
                                vlogManager?.logPoints(
                                    basketBreakdownLog,
                                    "Số dư: ${account.availablePoints} pts (+${account.pointsEarnedToday} hôm nay)",
                                    account.email
                                )

                                // Cập nhật Live Notification Tray hiển thị breakdown
                                onStatusUpdate(
                                    "Tài khoản: ${account.email} | +${deltaTotal}đ (PC:+${deltaPC}, Mob:+${deltaMobile}, Edge:+${deltaEdge}, Bonus:+${deltaBonus})",
                                    "Kho: ${postBalance.pcProgress}/${account.pcMax} PC, ${postBalance.mobileProgress}/${account.mobileMax} Mob, ${postBalance.edgeProgress}/${account.edgeMax} Edge"
                                )

                                if (postBalance.isAllMaxedOut) {
                                    vlogManager?.logPoints(
                                        "Tất cả rổ điểm đã max (All Maxed Out)!",
                                        "PC: ${postBalance.pcProgress}/${account.pcMax}, Mobile: ${postBalance.mobileProgress}/${account.mobileMax}, Edge: ${postBalance.edgeProgress}/${account.edgeMax}",
                                        account.email
                                    )
                                }
                            }

                            // Đánh giá cờ Cooldown và Phase Trial Exhaustion
                            if (deltaTotal == 0) {
                                val zeroCount = (zeroDeltaBatchCountMap[account.id] ?: 0) + 1
                                zeroDeltaBatchCountMap[account.id] = zeroCount

                                val phaseCount = phaseZeroDeltaCountMap.getOrPut(account.id) { ConcurrentHashMap() }
                                val zCount = (phaseCount[activePhase] ?: 0) + 1
                                phaseCount[activePhase] = zCount

                                var isTransitioningToMobile = false

                                // Nếu cày thử nghiệm một phase mà không tăng điểm:
                                if (activePhase == SearchPhase.PHASE_2_MOBILE && zCount >= 2) {
                                    phaseExhaustedMap.getOrPut(account.id) { Collections.synchronizedSet(mutableSetOf()) }.add(SearchPhase.PHASE_2_MOBILE)
                                    vlogManager?.logPoints(
                                        "[TRIAL-MOB] Đã hoàn tất thử nghiệm Mobile Search cho ${account.email}",
                                        "Microsoft không cộng thêm điểm rổ Mobile (ΔMobile=0). Chuyển sang Phase 3 (Edge).",
                                        account.email
                                    )
                                } else if (activePhase == SearchPhase.PHASE_3_EDGE && zCount >= 2) {
                                    phaseExhaustedMap.getOrPut(account.id) { Collections.synchronizedSet(mutableSetOf()) }.add(SearchPhase.PHASE_3_EDGE)
                                    vlogManager?.logPoints(
                                        "[TRIAL-EDGE] Đã hoàn tất thử nghiệm Edge Search cho ${account.email}",
                                        "Microsoft không cộng thêm điểm rổ Edge (ΔEdge=0). Hoàn tất tìm kiếm.",
                                        account.email
                                    )
                                } else if (activePhase == SearchPhase.PHASE_1_PC && zCount >= 2) {
                                    phaseExhaustedMap.getOrPut(account.id) { Collections.synchronizedSet(mutableSetOf()) }.add(SearchPhase.PHASE_1_PC)
                                    if (account.pcMax > 0 && account.pcProgress >= account.pcMax - 3) {
                                        encryptedPrefs?.markSearchExhaustedToday(account.id, todayStr)
                                        account.pcProgress = account.pcMax
                                        onAccountUpdated(account)
                                    }

                                    val isL2OrMob = (account.level >= 2 || account.mobileMax > 0)
                                    val effMobQuota = if (account.mobileMax > 0) account.mobileMax else 60
                                    if (isL2OrMob && account.mobileProgress < effMobQuota && phaseExhaustedMap[account.id]?.contains(SearchPhase.PHASE_2_MOBILE) != true) {
                                        isTransitioningToMobile = true
                                        zeroDeltaBatchCountMap[account.id] = 0
                                        cooldownRestrictedAccounts.remove(account.id)
                                    }
                                    vlogManager?.logPoints(
                                        "[TRIAL-PC] Đã hoàn tất thử nghiệm PC Search cho ${account.email}",
                                        "Microsoft không cộng thêm điểm rổ PC (ΔPC=0). Chuyển sang Phase 2 (Mobile).",
                                        account.email
                                    )
                                }

                                val effFrozenStop = maxOf(2, frozenStopBatches)
                                val effCooldownTrigger = maxOf(1, cooldownTriggerBatches)
                                if (isTransitioningToMobile) {
                                    vlogManager?.logCooldown(
                                        "[TRANSITION-MOB] Bỏ qua Cooldown/Frozen Balance đợt PC cho ${account.email}",
                                        "Tài khoản Level 2 chuyển sang thử nghiệm Mobile Search trước khi kết luận.",
                                        account.email
                                    )
                                } else if (isAccountCooldownRestricted(account.id)) {
                                    // Tài khoản đang trong chu kỳ Cooldown 15 phút của Microsoft -> Không khóa nick (isEnabled = false)!
                                    vlogManager?.logCooldown(
                                        "[COOL-HOLD] Tài khoản ${account.email} đang trong thời gian Cooldown 15 phút ($zeroCount batch ΔTotal == 0)",
                                        "Tiếp tục bảo toàn tài khoản, duy trì Cooldown sàn toán học 920s và luân chuyển Round-Robin.",
                                        account.email
                                    )
                                } else if (isAuthDesync(account.id)) {
                                    // Tài khoản bị mất đồng bộ Bing Search Auth -> Không khóa nick (isEnabled = false)!
                                    vlogManager?.logNetwork(
                                        "[AUTH-DESYNC] Phát hiện mất đồng bộ Bing Search Auth cho ${account.email} ($zeroCount batch ΔTotal == 0)",
                                        "Bảo toàn tài khoản (không khóa nick), tạm hoãn để tự động re-auth ở chu kỳ kế tiếp.",
                                        account.email
                                    )
                                    cooldownRestrictedAccounts.add(account.id)
                                } else if (account.pointsEarnedToday > 0 && zeroCount >= effFrozenStop) {
                                    // Tài khoản hôm nay đã kiếm được điểm hợp lệ (ví dụ Phase 0 hoặc PC search) -> Không khóa nick!
                                    cooldownRestrictedAccounts.add(account.id)
                                    vlogManager?.logCooldown(
                                        "[SAFE-COOLDOWN] Tài khoản ${account.email} hôm nay đã kiếm được ${account.pointsEarnedToday} điểm",
                                        "Phát hiện số dư tạm dừng tăng ($zeroCount batch ΔTotal == 0). Áp dụng Cooldown 15 phút thay vì khóa nick.",
                                        account.email
                                    )
                                } else if (zeroCount >= effFrozenStop && successfulSearchesInBatch > 0) {
                                    // G4.4: Phát hiện Frozen Balance khi >= effFrozenStop batch liên tiếp delta == 0 dù tìm kiếm thành công
                                    vlogManager?.logError(
                                        "[FROZEN-BALANCE] Phát hiện số dư đóng băng: $zeroCount batch liên tiếp ΔTotal == 0 dù $successfulSearchesInBatch lượt tìm kiếm thành công!",
                                        "Tự động vô hiệu hóa tài khoản ${account.email}, gửi cảnh báo Telegram và giải phóng session an toàn.",
                                        account.email
                                    )

                                    val currentView = currentWebView ?: activeWebView ?: webViewProvider?.invoke()
                                    val screenshot = withContext(AndroidMainDispatcher) {
                                        TelegramNotifier.captureBitmap(currentView)
                                    }
                                    val alertCaption = "🚨 *CẢNH BÁO SỐ DƯ ĐÓNG BĂNG (FROZEN BALANCE)*\n" +
                                            "👤 Tài khoản: `${account.email}`\n" +
                                            "⚠️ Chi tiết: $zeroCount batch liên tiếp ΔTotal = 0 dù tìm kiếm hợp lệ ($successfulSearchesInBatch lượt)\n" +
                                            "🛑 Hành động: Tự động khóa nick (account.isEnabled = false) và xóa sạch session để bảo vệ điểm."

                                    try {
                                        if (screenshot != null && telegramNotifier != null) {
                                            telegramNotifier.sendScreenshot(screenshot, alertCaption)
                                        } else if (telegramNotifier != null) {
                                            telegramNotifier.sendMessage(alertCaption)
                                        }
                                    } catch (te: Exception) {
                                        if (te is kotlinx.coroutines.CancellationException) throw te
                                        android.util.Log.e("RewardsStateMachine", "Lỗi gửi alert Telegram Frozen Balance: ${te.message}")
                                    } finally {
                                        if (screenshot != null && !screenshot.isRecycled) {
                                            screenshot.recycle()
                                        }
                                    }

                                    onAnomalyDetected?.invoke(account, "FROZEN_BALANCE", currentView)

                                    // Fail-safe: Tự động vô hiệu hóa tài khoản và giải phóng session
                                    account.isEnabled = false
                                    onAccountUpdated(account)
                                    profileManager?.clearActiveSession(currentView)
                                    profileManager?.resetActiveSession()
                                    activeSessionAccountId = null

                                    onStatusUpdate(
                                        "Tài khoản ${account.email} đã dừng",
                                        "Tự động khóa nick do Frozen Balance ($zeroCount batch ΔTotal = 0)"
                                    )
                                    vlogManager?.logPoints(
                                        "[SAFETY-HALT] Đã cách ly an toàn tài khoản ${account.email}",
                                        "Tài khoản đã bị vô hiệu hóa (isEnabled = false) để chống burn nick.",
                                        account.email
                                    )
                                    continue
                                } else if (zeroCount >= effCooldownTrigger) {
                                    // effCooldownTrigger batch liên tiếp ΔTotal == 0 => Cắm cờ Cooldown restricted 15 phút
                                    cooldownRestrictedAccounts.add(account.id)
                                    vlogManager?.logCooldown(
                                        "[COOL] Phát hiện Cooldown Restricted: $zeroCount batch liên tiếp ΔTotal == 0 sau batch ($successfulSearchesInBatch lượt)",
                                        "Tài khoản ${account.email} đang bị Microsoft áp cờ kiểm soát 15 phút. Kích hoạt sàn toán học 920s.",
                                        account.email
                                    )
                                } else {
                                    // Mới 1 batch ΔTotal == 0 => Chỉ cảnh báo vlog, chưa cắm cờ Cooldown
                                    vlogManager?.logCooldown(
                                        "[WARN] Cảnh báo ΔTotal == 0 sau batch ($successfulSearchesInBatch lượt) [Lần $zeroCount/$effCooldownTrigger]",
                                        "Tài khoản ${account.email} chưa ghi nhận tăng điểm ở batch này. Tiếp tục theo dõi batch kế tiếp.",
                                        account.email
                                    )
                                }
                            } else {
                                // Điểm tăng hợp lệ => Reset bộ đếm zero batch & gỡ cờ restricted nếu có
                                zeroDeltaBatchCountMap[account.id] = 0
                                phaseZeroDeltaCountMap.getOrPut(account.id) { ConcurrentHashMap() }[activePhase] = 0
                                cooldownRestrictedAccounts.remove(account.id)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            vlogManager?.logError("Lỗi đo Closed-Loop Basket Breakdown sau batch", e.message, account.email)
                        }
                    }

                    // 4. RECONCILIATION LOOP (ĐỐI SOÁT & CÀY BÙ PHẪU THUẬT):
                    if (!isAccountCooldownRestricted(account.id)) {
                        val phaseCheck = evaluateNextSearchPhase(account)
                        if (phaseCheck == SearchPhase.DONE || account.completedSearchesToday >= account.targetSearches) {
                            runReconciliationAudit(account, currentWebView)
                        }
                    }

                    // Ready to claim sau khi kết thúc các phase tìm kiếm nếu claimablePoints > 0
                    val endSearchClaimable = latestBalanceMap[account.id]?.claimablePoints ?: account.claimablePoints
                    if (endSearchClaimable > 0 && (claimAttemptCountMap["$todayStr:${account.id}"] ?: 0) < maxOf(1, maxClaimRetries) && currentWebView != null && isRunning && isActive) {
                        try {
                            vlogManager?.logPoints(
                                "[READY-TO-CLAIM] Phát hiện $endSearchClaimable điểm claimable sau tìm kiếm",
                                "Đang mở ?modal=pointsclaim để nhận điểm 2 nhịp...",
                                account.email
                            )
                            executeTwoStepPointClaim(currentWebView, account)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                    }

                    // 5. RÚT GỌN COOLDOWN SAU BATCH:
                    // Áp dụng sàn toán học 920s (15m20s) + Random.nextInt(0, 30) thay cho 18-22 phút
                    val nextPhaseAfterBatch = evaluateNextSearchPhase(account)
                    val isAllBasketsDone = nextPhaseAfterBatch == SearchPhase.DONE

                    val isStillNeedingSearches = if (isAllBasketsDone) {
                        vlogManager?.logPoints(
                            "Tài khoản ${account.email} đã ăn trọn tất cả rổ điểm (All Maxed Out)!",
                            "PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax}. Chuyển ngay sang tài khoản tiếp theo.",
                            account.email
                        )
                        false
                    } else if (account.completedSearchesToday < account.targetSearches) {
                        true
                    } else {
                        // Chế độ cày bù: thắt chặt tối đa 2-3 lượt. Nếu gặp zero delta >= 1 hoặc cờ Cooldown / Desync -> dừng cày bù sớm!
                        val canCatchUp = account.completedSearchesToday < maxCatchUpSearches &&
                                (zeroDeltaBatchCountMap[account.id] ?: 0) < 1 &&
                                !isAccountCooldownRestricted(account.id) &&
                                !isAuthDesync(account.id)
                        if (!canCatchUp && account.completedSearchesToday >= account.targetSearches) {
                            vlogManager?.logCooldown(
                                "[CATCHUP-STOP] Dừng cày bù sớm cho ${account.email} (${account.completedSearchesToday}/$maxCatchUpSearches lượt)",
                                if (isAccountCooldownRestricted(account.id)) "Tài khoản dính Cooldown 15 phút." else "Phát hiện zero delta / desync khi cày bù. Bảo vệ tài khoản, kích hoạt Cooldown 15 phút.",
                                account.email
                            )
                            cooldownRestrictedAccounts.add(account.id)
                        }
                        canCatchUp
                    }

                    if (isStillNeedingSearches && isRunning) {
                        val isRestricted = cooldownRestrictedAccounts.contains(account.id)

                        val cooldownSecs = if (isRestricted) {
                            // Sàn toán học 920s (15m20s) + jitter 0..29s khi bị cắm cờ Cooldown
                            920 + Random.nextInt(0, 30)
                        } else if (deltaPoints > 0 || (zeroDeltaBatchCountMap[account.id] ?: 0) < 2) {
                            // Tài khoản sạch hoặc mới 1 batch delta == 0: micro-break 45..75s
                            Random.nextInt(45, 76)
                        } else {
                            // Mặc định an toàn: Sàn toán học 920s + jitter 0..29s
                            920 + Random.nextInt(0, 30)
                        }

                        val cooldownUntilMs = System.currentTimeMillis() + cooldownSecs * 1000L
                        account.cooldownUntilTimestamp = cooldownUntilMs
                        onAccountUpdated(account)
                        earliestCooldownUntilMs = minOf(earliestCooldownUntilMs, cooldownUntilMs)

                        val cooldownUntil = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(
                            Date(cooldownUntilMs)
                        )
                        val modeLabel = if (cooldownSecs >= 900) "Cooldown Sàn Toán Học 920s" else if (account.completedSearchesToday >= account.targetSearches) "Micro-break Cày Bù (45-75s)" else "Fast Bursting"

                        val hasOtherReadyAccount = candidateAccounts.any {
                            val otherDapiDone = isDapiPhaseDoneForAccount(it, todayStr)
                            val otherSearchDone = evaluateNextSearchPhase(it) == SearchPhase.DONE
                            val otherDailyDone = it.dailySetDone || phase0CompletedDateMap[it.id] == todayStr
                            val otherBingAppDone = isBingApp60Of60Done(it, todayStr)
                            val otherFinished = (it.isAllMaxedOutToday(todayStr) && otherDailyDone) || (otherSearchDone && otherDailyDone && otherDapiDone) || (otherSearchDone && otherBingAppDone)
                            it.id != account.id &&
                                it.isEnabled &&
                                !it.cookieExpired &&
                                it.cooldownUntilTimestamp <= System.currentTimeMillis() &&
                                !otherFinished
                        }

                        if (hasOtherReadyAccount) {
                            vlogManager?.logCooldown(
                                "Tài khoản ${account.email} vào trạng thái nghỉ $modeLabel (${cooldownSecs}s đến $cooldownUntil)",
                                "Chuyển ngay sang tài khoản sẵn sàng tiếp theo trong hàng đợi (Round-Robin Non-Blocking).",
                                account.email
                            )
                            continue
                        }

                        onStatusUpdate(
                            "Nghỉ $modeLabel (${cooldownSecs / 60}m${cooldownSecs % 60}s)",
                            "Tài khoản ${account.email} sẽ chạy tiếp lúc $cooldownUntil (ΔPoints: ${if (deltaPoints >= 0) "+$deltaPoints" else "N/A"})"
                        )
                        vlogManager?.logCooldown(
                            "Bắt đầu nghỉ $modeLabel: ${cooldownSecs}s",
                            "Chạy tiếp lúc $cooldownUntil (ΔPoints: ${if (deltaPoints >= 0) "+$deltaPoints" else "N/A"})",
                            account.email
                        )

                        if (cooldownSecs >= 900) {
                            wakeLockHelper?.release()
                            activeSessionAccountId = null
                            profileManager?.resetActiveSession()
                            withContext(AndroidMainDispatcher) {
                                try {
                                    targetWebView?.stopLoading()
                                    targetWebView?.loadUrl("about:blank")
                                    targetWebView?.clearHistory()
                                } catch (_: Exception) {}
                            }
                            onScheduleCooldownAlarm?.invoke(cooldownSecs.toLong(), account)
                            waitForAlarmWakeup(cooldownSecs.toLong())
                        } else {
                            safeDelay(cooldownSecs * 1000L)
                        }
                    } else if (!isStillNeedingSearches && account.cooldownUntilTimestamp != 0L) {
                        account.cooldownUntilTimestamp = 0L
                        onAccountUpdated(account)
                    }
                }

                // Single-Account Target: Nếu khác null, chỉ chạy duy nhất account đó rồi kết thúc
                if (!targetAccountId.isNullOrBlank()) {
                    vlogManager?.logSystem("Hoàn tất Single-Account Target", "Đã xử lý xong tài khoản $targetAccountId. Dừng điều phối.")
                    onStatusUpdate("Hoàn tất điều phối", "Chạy xong tài khoản duy nhất.")
                    isRunning = false
                    break
                }

                if (allAccountsFinished) {
                    onStatusUpdate("Hoàn thành tất cả tài khoản!", "Tất cả tài khoản đã đạt chỉ tiêu hôm nay. Nghỉ đến sáng mai.")
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, 1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 1)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val secsUntilTomorrow = maxOf(60L, (cal.timeInMillis - System.currentTimeMillis()) / 1000L)
                    val sampleAcc = AccountProfile(id = "all", email = "all", profileName = "All")

                    val finalAccounts = database?.rewardsDao()?.getAllActiveProfiles() ?: candidateAccounts
                    if (telegramNotifier != null && encryptedPrefs?.lastCompletionSummaryDate != todayStr) {
                        try {
                            wakeLockHelper?.acquire("DailyCompletionSummary", 60000L)
                            telegramNotifier.sendDailyCompletionReport(finalAccounts, todayStr)
                            encryptedPrefs?.lastCompletionSummaryDate = todayStr
                        } catch (e: Exception) {
                            android.util.Log.e("RewardsStateMachine", "Lỗi gửi báo cáo hoàn thành Telegram: ${e.message}")
                        }
                    }

                    val idleWebView = webView ?: activeWebView ?: webViewProvider?.invoke()
                    wakeLockHelper?.release()
                    withContext(AndroidMainDispatcher) {
                        try {
                            idleWebView?.stopLoading()
                            idleWebView?.loadUrl("about:blank")
                            idleWebView?.clearHistory()
                        } catch (_: Exception) {}
                    }
                    onScheduleCooldownAlarm?.invoke(secsUntilTomorrow, sampleAcc)
                    waitForAlarmWakeup(secsUntilTomorrow)
                } else if (!anyAccountExecutedInCycle && earliestCooldownUntilMs < Long.MAX_VALUE) {
                    val waitSecs = maxOf(5L, (earliestCooldownUntilMs - System.currentTimeMillis()) / 1000L)
                    val nextReadyTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(earliestCooldownUntilMs))
                    val cooldownAcc = candidateAccounts.minByOrNull {
                        if (it.cooldownUntilTimestamp > System.currentTimeMillis()) it.cooldownUntilTimestamp else Long.MAX_VALUE
                    } ?: candidateAccounts.firstOrNull() ?: AccountProfile(id = "cooldown", email = "cooldown", profileName = "Cooldown")
                    onStatusUpdate(
                        "Tất cả tài khoản đang trong Cooldown",
                        "Chờ ${waitSecs / 60}m${waitSecs % 60}s đến $nextReadyTime (${cooldownAcc.email})"
                    )
                    vlogManager?.logCooldown(
                        "Hàng đợi Cooldown: chờ ${waitSecs}s đến $nextReadyTime",
                        "Tài khoản sẵn sàng sớm nhất: ${cooldownAcc.email}",
                        cooldownAcc.email
                    )
                    if (waitSecs >= 60L) {
                        val idleWebView = webView ?: activeWebView ?: webViewProvider?.invoke()
                        wakeLockHelper?.release()
                        activeSessionAccountId = null
                        profileManager?.resetActiveSession()
                        withContext(AndroidMainDispatcher) {
                            try {
                                idleWebView?.stopLoading()
                                idleWebView?.loadUrl("about:blank")
                                idleWebView?.clearHistory()
                            } catch (_: Exception) {}
                        }
                        onScheduleCooldownAlarm?.invoke(waitSecs, cooldownAcc)
                        waitForAlarmWakeup(waitSecs)
                    } else {
                        safeDelay(waitSecs * 1000L)
                    }
                } else {
                    safeDelay(2000L) // Giãn cách 2 giây trước chu kỳ quét kế tiếp
                }
            }
        } finally {
            wakeLockHelper?.release()
        }
    }
}

    /**
     * Vòng lặp đối soát (Reconciliation Loop) kiểm tra thâm hụt điểm sau các phase tìm kiếm.
     * Thực thi cày bù phẫu thuật (Surgical Micro-Catchup) tối đa 2 lượt/rổ thiếu.
     * Kích hoạt Two-Pass Stop-Loss Circuit Breaker: tối đa 2 vòng lặp; nếu deltaPoints == 0 thì ngắt sớm.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun runReconciliationAudit(account: AccountProfile, targetWebView: WebView?): Boolean {
        if (isAccountCooldownRestricted(account.id)) {
            vlogManager?.logPoints(
                "[RECONCILIATION] Bỏ qua đối soát cho ${account.email}",
                "Tài khoản đang bị cờ Cooldown 15 phút.",
                account.email
            )
            return false
        }

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        var totalRecoveredPoints = 0
        val effMaxPasses = maxOf(0, maxReconciliationPasses)

        for (pass in 1..effMaxPasses) {
            if (!isRunning) break

            // 1. Snapshot điểm trước audit pass qua HTTP chính thống
            val auditBal = try {
                extractPointsForAccount(account)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }

            if (auditBal != null && auditBal.isSuccess && auditBal.availablePoints > 0) {
                latestBalanceMap[account.id] = auditBal
                applyBalancePreservingExhaustedCap(account, auditBal, todayStr, account.baselinePointsToday)
                onAccountUpdated(account)
            }

            val isPcFullInSession = sessionFullBasketsMap[account.id]?.contains(SearchPhase.PHASE_1_PC) == true
            val pcRecheckCount = sessionRecheckCountMap.getOrPut(account.id) { ConcurrentHashMap() }[SearchPhase.PHASE_1_PC] ?: 0
            val isPcBlockedByGuard = isPcFullInSession && pcRecheckCount >= 1

            val isPcExhausted = (phaseExhaustedMap[account.id]?.contains(SearchPhase.PHASE_1_PC) == true) ||
                (encryptedPrefs?.isSearchExhaustedToday(account.id, todayStr) == true) ||
                isPcBlockedByGuard
            val pcDeficit = if (account.pcMax > 0 && !isPcExhausted) maxOf(0, account.pcMax - account.pcProgress) else 0
            val effMobMax = if (account.mobileMax > 0) account.mobileMax else (if (account.level >= 2) 60 else 0)
            val isMobAllowed = (account.level >= 2 && effMobMax > 0)
            if (!isMobAllowed) {
                vlogManager?.logPoints(
                    "[SKIP-MOB] [RECONCILIATION] Bỏ qua rổ Mobile trong đối soát",
                    "Level Gating: account.level=${account.level}, mobileMax=$effMobMax. Chuyển sang Phase 3 Edge hoặc kết thúc.",
                    account.email
                )
            }
            val mobDeficit = if (isMobAllowed) maxOf(0, effMobMax - account.mobileProgress) else 0
            val edgeDeficit = if (account.edgeMax > 0 && account.level > 1) maxOf(0, account.edgeMax - account.edgeProgress) else 0

            val totalDeficit = pcDeficit + mobDeficit + edgeDeficit
            if (totalDeficit <= 0) {
                vlogManager?.logPoints(
                    "[RECONCILIATION] Đối soát hoàn tất: Tất cả các rổ điểm đã đạt tối đa! (Pass $pass/$maxReconciliationPasses)",
                    "PC: ${account.pcProgress}/${account.pcMax} | Mobile: ${account.mobileProgress}/${account.mobileMax} | Edge: ${account.edgeProgress}/${account.edgeMax}",
                    account.email
                )
                return true
            }

            vlogManager?.logPoints(
                "[RECONCILIATION] Phát hiện thâm hụt điểm (Pass $pass/$maxReconciliationPasses)",
                "Thâm hụt: Mobile: $mobDeficit pts, Edge: $edgeDeficit pts, PC: $pcDeficit pts (Tổng thiếu: $totalDeficit pts). Kích hoạt Surgical Micro-Catchup...",
                account.email
            )

            val prePassPoints = account.availablePoints

            // Xác định danh sách các rổ cần bù: Deficit-First (Mobile -> Edge -> PC)
            val deficitBaskets = mutableListOf<SearchPhase>()
            if (isMobAllowed && mobDeficit > 0) {
                val neededSearches = minOf(2, (mobDeficit + 2) / 3)
                repeat(neededSearches) { deficitBaskets.add(SearchPhase.PHASE_2_MOBILE) }
            }
            if (edgeDeficit > 0) {
                val neededSearches = minOf(2, (edgeDeficit + 2) / 3)
                repeat(neededSearches) { deficitBaskets.add(SearchPhase.PHASE_3_EDGE) }
            }
            if (pcDeficit > 0 && !isPcBlockedByGuard) {
                val neededSearches = minOf(2, (pcDeficit + 2) / 3)
                repeat(neededSearches) { deficitBaskets.add(SearchPhase.PHASE_1_PC) }
                sessionRecheckCountMap.getOrPut(account.id) { ConcurrentHashMap() }[SearchPhase.PHASE_1_PC] = pcRecheckCount + 1
            }

            var passSearchesExecuted = 0
            for ((idx, phase) in deficitBaskets.withIndex()) {
                if (!isRunning) break

                val keyword = keywordEngine.generateNextKeyword(account.id)
                val phaseLabel = phase.label
                onStatusUpdate(
                    "Tài khoản: ${account.email} [Đối soát $pass/$maxReconciliationPasses]",
                    "[$phaseLabel] Cày bù: \"$keyword\" (${idx + 1}/${deficitBaskets.size})"
                )

                val success = onExecuteSearch(account, keyword, phase)
                if (success) {
                    passSearchesExecuted++
                    account.completedSearchesToday++
                    account.lastActiveDate = todayStr
                    onAccountUpdated(account)
                }

                if (idx < deficitBaskets.size - 1 && isRunning) {
                    val waitSec = Random.nextInt(12, 22)
                    safeDelay(waitSec * 1000L)
                }
            }

            // Chờ 8s settle rồi kiểm tra lại HTTP balance
            safeDelay(8000L)
            val postPassBal = try {
                extractPointsForAccount(account)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }

            val postPassPoints = postPassBal?.availablePoints ?: account.availablePoints
            val passDelta = maxOf(0, postPassPoints - prePassPoints)
            totalRecoveredPoints += passDelta

            if (postPassBal != null && postPassBal.isSuccess) {
                latestBalanceMap[account.id] = postPassBal
                applyBalancePreservingExhaustedCap(account, postPassBal, todayStr, account.baselinePointsToday)
                onAccountUpdated(account)
            }

            vlogManager?.logPoints(
                "[RECONCILIATION] Kết quả vòng đối soát $pass/$maxReconciliationPasses: +${passDelta}đ",
                "Đã thực thi $passSearchesExecuted lượt. PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax}",
                account.email
            )

            // Two-Pass Stop-Loss Circuit Breaker:
            // Nếu sau 1 pass cày bù mà delta == 0 -> ngắt sớm ngay lập tức!
            if (passDelta == 0) {
                if (passSearchesExecuted > 0) {
                    phaseExhaustedMap.getOrPut(account.id) { Collections.synchronizedSet(mutableSetOf()) }.add(SearchPhase.PHASE_1_PC)
                    if (account.pcMax > 0 && account.pcProgress >= account.pcMax - 3) {
                        encryptedPrefs?.markSearchExhaustedToday(account.id, todayStr)
                        account.pcProgress = account.pcMax
                        onAccountUpdated(account)
                    }
                }
                vlogManager?.logCooldown(
                    "[RECONCILIATION] Stop-Loss Circuit Breaker KÍCH HOẠT: deltaPoints == 0 tại pass $pass",
                    "Dừng đối soát sớm để bảo vệ tài khoản khỏi cày vô ích hoặc cờ phạt.",
                    account.email
                )
                break
            }
        }

        return totalRecoveredPoints > 0
    }

    fun stop() {
        isRunning = false
        activeSessionAccountId = null
        wakeLockHelper?.release()
        currentJob?.cancel()
        currentJob = null
        onStatusUpdate("Đã dừng node", "Dịch vụ đang ở trạng thái nghỉ.")
    }

    companion object {
        /**
         * Nhận diện các vector URL Abuse, Suspended hoặc Cloudflare Challenge (G4.4)
         */
        fun isAnomalyUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains("live.com/abuse") ||
                    lower.contains("account.live.com/recover") ||
                    lower.contains("identity/confirm") ||
                    lower.contains("error=suspended") ||
                    lower.contains("challenges.cloudflare.com")
        }

        /**
         * Nhận diện các dấu hiệu Captcha Arkose Labs, FunCaptcha hoặc Cloudflare trong DOM (G4.4)
         */
        fun isAnomalyDom(domContent: String?): Boolean {
            if (domContent.isNullOrBlank()) return false
            val lower = domContent.lowercase(Locale.ROOT)
            return lower.contains("challenge-stage") ||
                    lower.contains("arkoselabs") ||
                    lower.contains("funcaptcha") ||
                    lower.contains("enforcementframe")
        }

        /**
         * Tiêu chí nhận diện Frozen Balance khi số batch liên tiếp delta == 0 >= 3 (G4.4)
         */
        fun isFrozenBalance(zeroDeltaBatchCount: Int): Boolean {
            return zeroDeltaBatchCount >= 3
        }

        /**
         * Kiểm tra cookie snapshot có chứa các token xác thực hợp lệ của Microsoft (_RwBf, _U, MSPAuth, MUID, KievRPSToken)
         * để quyết định bảo tồn và lưu vào Room Database.
         */
        fun isPreservableCookie(cookies: String?): Boolean {
            if (cookies.isNullOrBlank()) return false
            return cookies.contains("_RwBf") ||
                    cookies.contains("_U") ||
                    cookies.contains("MSPAuth") ||
                    cookies.contains("MUID") ||
                    cookies.contains("KievRPSToken")
        }
    }
}
