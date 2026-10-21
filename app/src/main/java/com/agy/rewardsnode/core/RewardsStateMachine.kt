package com.agy.rewardsnode.core

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.utils.AndroidMainDispatcher
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
    private val onExecuteSearch: suspend (account: AccountProfile, keyword: String, phase: SearchPhase) -> Boolean,
    private val onStatusUpdate: (status: String, details: String) -> Unit,
    private val onAccountUpdated: suspend (account: AccountProfile) -> Unit
) {
    private var isRunning = false
    private var currentJob: Job? = null
    private var activeWebView: WebView? = null

    // Tập hợp tài khoản bị phát hiện dính cờ Cooldown 15 phút (Closed-Loop Rate Limit)
    private val cooldownRestrictedAccounts = Collections.synchronizedSet(mutableSetOf<String>())
    // Bảng lưu số điểm trước batch để đo ΔPoints chính xác
    private val preBatchPointsMap = ConcurrentHashMap<String, Int>()
    // Bảng theo dõi số batch liên tiếp ΔPoints == 0 của mỗi tài khoản
    private val zeroDeltaBatchCountMap = ConcurrentHashMap<String, Int>()
    // Bảng lưu kết quả trích xuất điểm gần nhất để kiểm soát rổ điểm & cày bù
    private val latestBalanceMap = ConcurrentHashMap<String, RewardsBalanceExtractor.ExtractedRewardsBalance>()
    // Bảng theo dõi ngày hoàn thành Phase 0 (Pre-Search Activities) của từng tài khoản
    private val phase0CompletedDateMap = ConcurrentHashMap<String, String>()
    // Bảng theo dõi ngày khởi tạo baselinePointsToday của từng tài khoản
    private val baselineDateMap = ConcurrentHashMap<String, String>()
    // Bảng theo dõi các Phase đã thử nghiệm không tăng điểm (đạt trần hoặc cạn ngạch) của từng tài khoản trong ngày
    private val phaseExhaustedMap = ConcurrentHashMap<String, MutableSet<SearchPhase>>()
    // Bảng theo dõi số lượt delta == 0 liên tiếp của từng phase
    private val phaseZeroDeltaCountMap = ConcurrentHashMap<String, ConcurrentHashMap<SearchPhase, Int>>()

    val MOBILE_USER_AGENT = FingerprintEngine.MOBILE[0].userAgent
    val DESKTOP_USER_AGENT = FingerprintEngine.DESKTOP[0].userAgent

    private suspend fun loadUrlAndWait(
        webView: WebView,
        url: String,
        timeoutMs: Long = 20000L
    ): Boolean = withContext(AndroidMainDispatcher) {
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
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    if (!completed && continuation.isActive) {
                        completed = true
                        handler.removeCallbacks(timeoutRunnable)
                        continuation.resume(true)
                    }
                }
            }

            try {
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.loadUrl(url)
            } catch (e: Exception) {
                if (!completed && continuation.isActive) {
                    completed = true
                    handler.removeCallbacks(timeoutRunnable)
                    continuation.resume(false)
                }
            }
        }
    }

    fun setWebView(webView: WebView?) {
        activeWebView = webView
    }

    fun isAccountCooldownRestricted(accountId: String): Boolean {
        return cooldownRestrictedAccounts.contains(accountId)
    }

    fun markAccountRestricted(accountId: String, restricted: Boolean) {
        if (restricted) {
            cooldownRestrictedAccounts.add(accountId)
        } else {
            cooldownRestrictedAccounts.remove(accountId)
            zeroDeltaBatchCountMap.remove(accountId)
        }
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
    fun evaluateNextSearchPhase(
        account: AccountProfile,
        currentPhase: SearchPhase = SearchPhase.PHASE_1_PC,
        loggedSkips: MutableSet<SearchPhase>? = null
    ): SearchPhase {
        val balance = latestBalanceMap[account.id]
            ?: RewardsBalanceExtractor.latestTelemetryMap[account.id]
            ?: RewardsBalanceExtractor.latestTelemetryMap[account.email]

        val exhausted = phaseExhaustedMap[account.id] ?: emptySet()

        val pcMax = if (account.pcMax > 0) account.pcMax else (balance?.pcMax?.takeIf { it > 0 } ?: if (account.level == 1) 30 else 90)
        val pcProgress = maxOf(account.pcProgress, balance?.pcProgress ?: 0)
        val isPcDone = (pcMax > 0 && pcProgress >= pcMax) || exhausted.contains(SearchPhase.PHASE_1_PC)

        val mobileMax = if (account.mobileMax > 0) account.mobileMax else (balance?.mobileMax?.takeIf { it > 0 } ?: 60)
        val mobileProgress = maxOf(account.mobileProgress, balance?.mobileProgress ?: 0)
        val isMobDone = (mobileMax > 0 && mobileProgress >= mobileMax) || exhausted.contains(SearchPhase.PHASE_2_MOBILE)

        val edgeMax = if (account.edgeMax > 0) account.edgeMax else (balance?.edgeMax?.takeIf { it > 0 } ?: 12)
        val edgeProgress = maxOf(account.edgeProgress, balance?.edgeProgress ?: 0)
        val isEdgeDone = (edgeMax > 0 && edgeProgress >= edgeMax) || exhausted.contains(SearchPhase.PHASE_3_EDGE)

        var phase = currentPhase

        // Trước Phase 1: Nếu isPcDone -> ghi vlog [SKIP-PC], chuyển sang Phase 2.
        if (phase == SearchPhase.PHASE_1_PC) {
            if (isPcDone) {
                if (loggedSkips != null && loggedSkips.add(SearchPhase.PHASE_1_PC)) {
                    val reason = if (pcMax > 0 && pcProgress >= pcMax) "Đã đạt tối đa rổ PC ($pcProgress/$pcMax)" else "Đã thử nghiệm PC không tăng thêm điểm"
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
                    val reason = if (mobileMax > 0 && mobileProgress >= mobileMax) "Đã đạt tối đa rổ Mobile ($mobileProgress/$mobileMax)" else "Đã thử nghiệm Mobile không tăng thêm điểm"
                    vlogManager?.logPoints(
                        "[SKIP-MOB] Bỏ qua Phase 2 (Mobile Search) cho ${account.email}",
                        "$reason. Chuyển sang Phase 3 (Edge Search).",
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
                    val reason = if (edgeMax > 0 && edgeProgress >= edgeMax) "Đã đạt tối đa rổ Edge ($edgeProgress/$edgeMax)" else "Đã thử nghiệm Edge không tăng thêm điểm"
                    vlogManager?.logPoints(
                        "[SKIP-EDGE] Bỏ qua Phase 3 (Edge Search) cho ${account.email}",
                        "$reason. Hoàn tất tìm kiếm.",
                        account.email
                    )
                }
                phase = SearchPhase.DONE
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

    fun start(
        accounts: List<AccountProfile>,
        batchSize: Int,
        cooldownMinutes: Int,
        coroutineScope: CoroutineScope,
        webView: WebView? = null
    ) {
        if (isRunning) return
        isRunning = true
        if (webView != null) {
            activeWebView = webView
        }

        currentJob = coroutineScope.launch(Dispatchers.Default) {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            while (isActive && isRunning) {
                var allAccountsFinished = true

                for (account in accounts) {
                    if (!isActive || !isRunning) break
                    if (!account.isEnabled) continue

                    val targetWebView = webView ?: activeWebView ?: webViewProvider?.invoke()

                    // Kiểm tra và reset bộ đếm nếu sang ngày mới, chuẩn hóa targetSearches (Level 1=35, Level 2=55)
                    val expectedTarget = if (account.level == 1) 35 else 55
                    val baselineDate = baselineDateMap[account.id] ?: account.lastActiveDate
                    if (baselineDate != todayStr) {
                        account.completedSearchesToday = 0
                        account.pointsEarnedToday = 0
                        account.pcProgress = 0
                        account.mobileProgress = 0
                        account.edgeProgress = 0
                        account.baselinePointsToday = account.availablePoints
                        account.lastActiveDate = todayStr
                        account.targetSearches = expectedTarget
                        baselineDateMap[account.id] = todayStr
                        zeroDeltaBatchCountMap.remove(account.id)
                        latestBalanceMap.remove(account.id)
                        phase0CompletedDateMap.remove(account.id)
                        phaseExhaustedMap.remove(account.id)
                        phaseZeroDeltaCountMap.remove(account.id)
                        onAccountUpdated(account)
                    } else {
                        if (account.baselinePointsToday <= 0 && account.availablePoints > 0) {
                            account.baselinePointsToday = account.availablePoints
                        }
                        baselineDateMap[account.id] = todayStr
                        if (account.targetSearches != expectedTarget) {
                            account.targetSearches = expectedTarget
                            onAccountUpdated(account)
                        }
                    }

                    val maxCatchUpSearches = (account.targetSearches * 1.6).toInt()

                    // Pre-flight HTTP fetch nếu chưa có số dư mới nhất
                    var latestBalance = latestBalanceMap[account.id]
                        ?: RewardsBalanceExtractor.latestTelemetryMap[account.id]
                        ?: RewardsBalanceExtractor.latestTelemetryMap[account.email]

                    if (latestBalance == null && !account.cookiesSnapshot.isNullOrBlank()) {
                        try {
                            val httpBal = RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot)
                            if (httpBal.isSuccess && httpBal.availablePoints > 0) {
                                latestBalanceMap[account.id] = httpBal
                                latestBalance = httpBal
                                account.availablePoints = httpBal.availablePoints
                                account.totalPoints = httpBal.availablePoints
                                if (httpBal.lifetimePoints > 0) account.lifetimePoints = httpBal.lifetimePoints
                                if (httpBal.streakCount > 0) account.streakCount = httpBal.streakCount
                                val serverEarned = httpBal.pointsEarnedToday
                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                    account.baselinePointsToday = httpBal.availablePoints
                                    baselineDateMap[account.id] = todayStr
                                }
                                account.pointsEarnedToday = maxOf(serverEarned, maxOf(0, account.availablePoints - account.baselinePointsToday))
                                account.pcProgress = httpBal.pcProgress
                                if (httpBal.pcMax > 0) account.pcMax = httpBal.pcMax
                                account.mobileProgress = httpBal.mobileProgress
                                if (httpBal.mobileMax > 0) account.mobileMax = httpBal.mobileMax
                                account.edgeProgress = httpBal.edgeProgress
                                if (httpBal.edgeMax > 0) account.edgeMax = httpBal.edgeMax
                                if (httpBal.mobileMax > 0 || httpBal.pcMax > 30) account.level = 2 else if (httpBal.pcMax in 1..30) account.level = 1
                                account.targetSearches = if (account.level == 1) 35 else 55
                                if (httpBal.dailySetDone) phase0CompletedDateMap[account.id] = todayStr
                                onAccountUpdated(account)
                            }
                        } catch (_: Exception) {
                        }
                    }

                    // 1. ACCOUNT-LEVEL CHECK ([SKIP-ACC]):
                    val dailySetDone = (latestBalance?.dailySetDone == true) || (phase0CompletedDateMap[account.id] == todayStr)
                    val claimablePoints = latestBalance?.claimablePoints ?: 0
                    val isActDone = dailySetDone && claimablePoints == 0

                    val nextSearchPhase = evaluateNextSearchPhase(account)
                    val isSearchDone = nextSearchPhase == SearchPhase.DONE

                    if (isActDone && isSearchDone) {
                        vlogManager?.logPoints(
                            "[SKIP-ACC] Bỏ qua tài khoản ${account.email} (0s delay)",
                            "Đã hoàn thành tất cả 4 rổ: Activities ✓ (Daily Set: Xong, Claim: 0) | PC: ${account.pcProgress}/${account.pcMax} ✓ | Mobile: ${account.mobileProgress}/${account.mobileMax} ✓ | Edge: ${account.edgeProgress}/${account.edgeMax} ✓",
                            account.email
                        )
                        continue
                    }

                    if (isSearchDone || account.completedSearchesToday >= maxCatchUpSearches) {
                        continue // Đã hoàn thành chỉ tiêu ngày hoặc đã cày bù tối đa cho tài khoản này
                    }

                    if (account.completedSearchesToday >= account.targetSearches) {
                        vlogManager?.logPoints(
                            "Kích hoạt cày bù điểm: ${account.completedSearchesToday}/${account.targetSearches} lượt",
                            "Chưa max rổ điểm (PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax}). Giới hạn cày bù: $maxCatchUpSearches lượt.",
                            account.email
                        )
                    }

                    // Kiểm tra Proxy Circuit Breaker
                    val proxy = account.proxyUrl
                    if (!proxy.isNullOrBlank() && profileManager?.isProxyCircuitOpen(proxy) == true) {
                        vlogManager?.logNetwork("Bỏ qua tài khoản do Proxy Circuit Breaker mở (Proxy lỗi liên tiếp >= 3 lần)", proxy, account.email)
                        continue
                    }

                    allAccountsFinished = false
                    val isCatchingUp = account.completedSearchesToday >= account.targetSearches
                    val currentProgressLabel = if (isCatchingUp) {
                        "Tiến độ cày bù: ${account.completedSearchesToday}/$maxCatchUpSearches lượt"
                    } else {
                        "Tiến độ: ${account.completedSearchesToday}/${account.targetSearches} lượt"
                    }
                    onStatusUpdate("Đang chạy tài khoản: ${account.email}", currentProgressLabel)
                    vlogManager?.logSearch("Bắt đầu xử lý tài khoản: ${account.email}", currentProgressLabel, account.email)

                    // 0. PHASE 0: PRE-SEARCH ACTIVITIES (Ready to Claim, Daily Set, Streak Protection)
                    val isPhase0Needed = phase0CompletedDateMap[account.id] != todayStr
                    if (isActDone) {
                        vlogManager?.logPoints(
                            "[SKIP-ACT] Bỏ qua Phase 0 (Pre-Search Activities)",
                            "Tài khoản ${account.email} đã xong Daily Set và claimablePoints == 0",
                            account.email
                        )
                        phase0CompletedDateMap[account.id] = todayStr
                    } else if (targetWebView != null && isPhase0Needed && isRunning && isActive) {
                        try {
                            onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0] Khởi chạy Pre-Search Activities...")
                            vlogManager?.logSearch("Bắt đầu Phase 0: Pre-Search Activities", "Nạp cookies và mở Dashboard...", account.email)

                            // 1. Nạp cookies và mở dashboard trên targetWebView
                            profileManager?.restoreCookiesForProfile(account.id, account.cookiesSnapshot)

                            withContext(AndroidMainDispatcher) {
                                val preset = FingerprintEngine.getEdgePresetForAccount(account.id, true)
                                targetWebView.settings.userAgentString = preset.userAgent
                            }

                            loadUrlAndWait(targetWebView, "https://rewards.bing.com/dashboard", 20000L)
                            delay(2500L) // Chờ DOM & Next.js hydrate ổn định

                            // 2. Kích hoạt Streak Protection nếu đang tắt
                            try {
                                val streakEnabled = RewardsActivitiesEngine.enableStreakProtection(targetWebView)
                                if (streakEnabled) {
                                    vlogManager?.logPoints("[STREAK] Kích hoạt thành công bảo vệ chuỗi Streak Protection", "Đã bật isProtectionOn = true để bảo vệ chuỗi", account.email)
                                }
                            } catch (e: Exception) {
                                vlogManager?.logError("Lỗi kích hoạt Streak Protection", e.message, account.email)
                            }

                            // 3. Thu hoạch Ready to Claim
                            try {
                                val claimed = RewardsActivitiesEngine.executeReadyToClaim(targetWebView)
                                if (claimed) {
                                    vlogManager?.logPoints("[CLAIM] Thu hoạch thành công điểm thưởng từ mục Ready to claim", "Đã click nhận điểm thưởng chờ nhận vào balance chính", account.email)
                                    delay(1500L)
                                    val claimBal = RewardsBalanceExtractor.extractPoints(targetWebView)
                                    if (claimBal.isSuccess && claimBal.availablePoints > 0) {
                                        latestBalanceMap[account.id] = claimBal
                                        account.availablePoints = claimBal.availablePoints
                                        account.totalPoints = claimBal.availablePoints
                                        if (claimBal.lifetimePoints > 0) account.lifetimePoints = claimBal.lifetimePoints
                                        if (claimBal.streakCount > 0) account.streakCount = claimBal.streakCount
                                        val serverEarned = claimBal.pointsEarnedToday
                                        val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                        if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                            account.baselinePointsToday = claimBal.availablePoints
                                            baselineDateMap[account.id] = todayStr
                                        }
                                        account.pointsEarnedToday = maxOf(serverEarned, maxOf(0, account.availablePoints - account.baselinePointsToday))
                                        onAccountUpdated(account)
                                    }
                                }
                            } catch (e: Exception) {
                                vlogManager?.logError("Lỗi thực thi Ready to Claim", e.message, account.email)
                            }

                            // 4. Trích xuất Daily Tasks và giải quyết các task chưa hoàn thành
                            var allDailyTasksDone = true
                            try {
                                val dailyTasks = RewardsActivitiesEngine.extractDailyTasks(targetWebView)
                                val pendingTasks = dailyTasks.filter { !it.isCompleted }

                                if (pendingTasks.isNotEmpty()) {
                                    vlogManager?.logPoints(
                                        "Phát hiện ${pendingTasks.size}/${dailyTasks.size} Daily Tasks chưa hoàn thành",
                                        pendingTasks.joinToString("; ") { "${it.title} (+${it.pointValue}đ, ${it.taskType})" },
                                        account.email
                                    )

                                    for ((index, task) in pendingTasks.withIndex()) {
                                        if (!isRunning || !isActive) break

                                        onStatusUpdate("Tài khoản: ${account.email}", "[Phase 0] Giải Daily Set: ${task.title} (+${task.pointValue}đ)")
                                        vlogManager?.logPoints(
                                            "[TASK] Bắt đầu thực hiện Daily Set (${index + 1}/${pendingTasks.size}): ${task.title} (+${task.pointValue}đ)",
                                            "Loại: ${task.taskType} | URL: ${task.destinationUrl}",
                                            account.email
                                        )

                                        val solved = RewardsActivitiesEngine.solveDailyTask(targetWebView, task)
                                        if (solved) {
                                            vlogManager?.logPoints(
                                                "[TASK] Hoàn thành Daily Set (${index + 1}/${pendingTasks.size}): ${task.title} (+${task.pointValue}đ)",
                                                "Trạng thái: Đã thực thi thành công",
                                                account.email
                                            )
                                        } else {
                                            allDailyTasksDone = false
                                            vlogManager?.logPoints(
                                                "[TASK] Đã trigger Daily Set (${index + 1}/${pendingTasks.size}): ${task.title}",
                                                "Trạng thái: Hoàn tất thao tác (chờ Microsoft đồng bộ điểm)",
                                                account.email
                                            )
                                        }

                                        // Nghỉ giãn cách ngẫu nhiên 10-18s giữa các task
                                        if (index < pendingTasks.size - 1 && isRunning && isActive) {
                                            val taskDelay = Random.nextInt(10, 19)
                                            onStatusUpdate("Tài khoản: ${account.email}", "Nghỉ giãn cách $taskDelay giây trước Daily Task tiếp...")
                                            vlogManager?.logCooldown("Nghỉ giãn cách giữa các Daily Task: $taskDelay giây", account = account.email)
                                            delay(taskDelay * 1000L)
                                        }
                                    }
                                } else if (dailyTasks.isNotEmpty()) {
                                    vlogManager?.logPoints("Tất cả Daily Tasks đã hoàn thành (${dailyTasks.size}/${dailyTasks.size})", "Không có nhiệm vụ Daily Set nào còn tồn đọng.", account.email)
                                }
                            } catch (e: Exception) {
                                allDailyTasksDone = false
                                vlogManager?.logError("Lỗi trong quá trình xử lý Daily Tasks", e.message, account.email)
                            }

                            if (allDailyTasksDone) {
                                phase0CompletedDateMap[account.id] = todayStr
                            }

                            // 5. Sau khi hoàn thành Phase 0: Đo lại balance và cập nhật AccountProfile
                            delay(2000L)
                            var postPhase0Bal = RewardsBalanceExtractor.extractPoints(targetWebView)
                            if (!postPhase0Bal.isSuccess || postPhase0Bal.availablePoints <= 0) {
                                postPhase0Bal = RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot)
                            }
                            if (postPhase0Bal.isSuccess && postPhase0Bal.availablePoints > 0) {
                                latestBalanceMap[account.id] = postPhase0Bal
                                account.availablePoints = postPhase0Bal.availablePoints
                                if (postPhase0Bal.lifetimePoints > 0) account.lifetimePoints = postPhase0Bal.lifetimePoints
                                if (postPhase0Bal.streakCount > 0) account.streakCount = postPhase0Bal.streakCount
                                val serverEarned = postPhase0Bal.pointsEarnedToday
                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                    account.baselinePointsToday = postPhase0Bal.availablePoints
                                    baselineDateMap[account.id] = todayStr
                                }
                                account.pointsEarnedToday = maxOf(serverEarned, maxOf(0, account.availablePoints - account.baselinePointsToday))
                                account.totalPoints = account.availablePoints

                                // Cập nhật các rổ điểm vào AccountProfile
                                account.pcProgress = postPhase0Bal.pcProgress
                                if (postPhase0Bal.pcMax > 0) account.pcMax = postPhase0Bal.pcMax
                                account.mobileProgress = postPhase0Bal.mobileProgress
                                if (postPhase0Bal.mobileMax > 0) account.mobileMax = postPhase0Bal.mobileMax
                                account.edgeProgress = postPhase0Bal.edgeProgress
                                if (postPhase0Bal.edgeMax > 0) account.edgeMax = postPhase0Bal.edgeMax

                                // Tự động targetSearches Level 1=35, Level 2=55
                                if (postPhase0Bal.mobileMax > 0 || postPhase0Bal.pcMax > 30) {
                                    account.level = 2
                                } else if (postPhase0Bal.pcMax in 1..30 && postPhase0Bal.mobileMax == 0) {
                                    account.level = 1
                                }
                                account.targetSearches = if (account.level == 1) 35 else 55

                                onAccountUpdated(account)

                                vlogManager?.logPoints(
                                    "Hoàn thành Phase 0 (Pre-Search Activities) | Số dư: ${account.availablePoints} pts",
                                    "Chuỗi ngày: ${account.streakCount} | Đã kiếm hôm nay: +${account.pointsEarnedToday} pts (PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax})",
                                    account.email
                                )
                            }
                        } catch (e: Exception) {
                            vlogManager?.logError("Lỗi trong Phase 0 (Pre-Search Activities)", e.message, account.email)
                        }
                    } else if (targetWebView != null && latestBalanceMap[account.id] == null) {
                        // PRE-FLIGHT BASELINE EXTRACTION FALLBACK NẾU CHƯA CÓ LATEST BALANCE
                        try {
                            profileManager?.restoreCookiesForProfile(account.id, account.cookiesSnapshot)
                            var preBalance = RewardsBalanceExtractor.extractPoints(targetWebView)
                            if (!preBalance.isSuccess || preBalance.availablePoints <= 0) {
                                preBalance = RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot)
                            }
                            if (preBalance.isSuccess && preBalance.availablePoints > 0) {
                                latestBalanceMap[account.id] = preBalance
                                account.availablePoints = preBalance.availablePoints
                                if (preBalance.lifetimePoints > 0) {
                                    account.lifetimePoints = preBalance.lifetimePoints
                                }
                                if (preBalance.streakCount > 0) {
                                    account.streakCount = preBalance.streakCount
                                }
                                val serverEarned = preBalance.pointsEarnedToday
                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                    account.baselinePointsToday = preBalance.availablePoints
                                    baselineDateMap[account.id] = todayStr
                                }
                                account.pointsEarnedToday = maxOf(serverEarned, maxOf(0, account.availablePoints - account.baselinePointsToday))
                                account.totalPoints = account.availablePoints

                                // Cập nhật rổ vào AccountProfile
                                account.pcProgress = preBalance.pcProgress
                                if (preBalance.pcMax > 0) account.pcMax = preBalance.pcMax
                                account.mobileProgress = preBalance.mobileProgress
                                if (preBalance.mobileMax > 0) account.mobileMax = preBalance.mobileMax
                                account.edgeProgress = preBalance.edgeProgress
                                if (preBalance.edgeMax > 0) account.edgeMax = preBalance.edgeMax

                                // Tự động targetSearches Level 1=35, Level 2=55
                                if (preBalance.mobileMax > 0 || preBalance.pcMax > 30) {
                                    account.level = 2
                                } else if (preBalance.pcMax in 1..30 && preBalance.mobileMax == 0) {
                                    account.level = 1
                                }
                                account.targetSearches = if (account.level == 1) 35 else 55

                                onAccountUpdated(account)

                                vlogManager?.logPoints(
                                    "Bóc tách điểm xuất phát (Baseline): ${account.availablePoints} pts",
                                    "Chuỗi ngày: ${account.streakCount} | Đã kiếm hôm nay: ${account.pointsEarnedToday} pts (PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax}) [${preBalance.source}]",
                                    account.email
                                )
                            }
                        } catch (e: Exception) {
                            vlogManager?.logError("Lỗi bóc tách điểm trước chu kỳ", e.message, account.email)
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
                    if (activePhase == SearchPhase.DONE) {
                        vlogManager?.logPoints(
                            "Tài khoản ${account.email} đã hoàn tất tất cả các pha tìm kiếm (All Search Done)",
                            "PC: ${account.pcProgress}/${account.pcMax}, Mobile: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax}.",
                            account.email
                        )
                    } else {
                        val effectiveTarget = if (isCatchingUp) maxCatchUpSearches else account.targetSearches
                        val remainingSearches = effectiveTarget - account.completedSearchesToday
                        val searchesInThisBatch = minOf(batchSize, remainingSearches)

                        if (searchesInThisBatch > 0) {
                            for (i in 1..searchesInThisBatch) {
                                if (!isActive || !isRunning) break

                                activePhase = evaluateNextSearchPhase(account, activePhase, loggedSkips)
                                if (activePhase == SearchPhase.DONE) {
                                    vlogManager?.logPoints(
                                        "Tài khoản ${account.email} đã đạt trần tìm kiếm giữa chừng tại lượt $i/$searchesInThisBatch!",
                                        "Dừng batch sớm, chuyển sang bước đối soát điểm.",
                                        account.email
                                    )
                                    break
                                }

                                val keyword = keywordEngine.generateNextKeyword()
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
                                    var pointsUpdated = false
                                    val currentWebView = targetWebView ?: activeWebView ?: webViewProvider?.invoke()
                                    if (currentWebView != null) {
                                        try {
                                            val serpBalance = RewardsBalanceExtractor.extractPointsFromSerp(currentWebView)
                                            if (serpBalance.isSuccess && serpBalance.availablePoints > account.availablePoints) {
                                                val pts = serpBalance.availablePoints
                                                account.availablePoints = pts
                                                account.totalPoints = pts
                                                val serverEarned = serpBalance.pointsEarnedToday
                                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                                if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                                    account.baselinePointsToday = pts
                                                    baselineDateMap[account.id] = todayStr
                                                }
                                                account.pointsEarnedToday = maxOf(serverEarned, maxOf(0, pts - account.baselinePointsToday))
                                                pointsUpdated = true
                                                onAccountUpdated(account)
                                            }
                                        } catch (_: Exception) {
                                            // Không ngắt luồng nếu trích xuất SERP gặp ngoại lệ
                                        }
                                    }
                                    if (!pointsUpdated) {
                                        onAccountUpdated(account)
                                    }
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

                                // Giãn cách ngẫu nhiên (18 đến 45 giây) giữa các lượt trong cụm
                                if (i < searchesInThisBatch && isRunning) {
                                    val nextPhase = evaluateNextSearchPhase(account, activePhase, loggedSkips)
                                    if (nextPhase == SearchPhase.DONE) {
                                        break
                                    }
                                    val jitterSeconds = Random.nextInt(18, 46)
                                    onStatusUpdate("Tài khoản: ${account.email}", "Đang đợi $jitterSeconds giây cho lượt tiếp theo...")
                                    vlogManager?.logCooldown("Nghỉ giãn cách giữa 2 lượt: $jitterSeconds giây", account = account.email)
                                    delay(jitterSeconds * 1000L)
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
                            delay(10000L)

                            var postBalance = if (currentWebView != null) {
                                var bal = RewardsBalanceExtractor.extractPoints(currentWebView)
                                if (!bal.isSuccess || bal.availablePoints <= 0) {
                                    bal = RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot)
                                }
                                bal
                            } else {
                                RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot)
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
                                delay(10000L)

                                val retryBalance = if (currentWebView != null) {
                                    var bal = RewardsBalanceExtractor.extractPoints(currentWebView)
                                    if (!bal.isSuccess || bal.availablePoints <= 0) {
                                        bal = RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot)
                                    }
                                    bal
                                } else {
                                    RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot)
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

                                account.availablePoints = postBalance.availablePoints
                                if (postBalance.lifetimePoints > 0) {
                                    account.lifetimePoints = postBalance.lifetimePoints
                                }
                                if (postBalance.streakCount > 0) {
                                    account.streakCount = postBalance.streakCount
                                }
                                val serverEarned = postBalance.pointsEarnedToday
                                val bDate = baselineDateMap[account.id] ?: account.lastActiveDate
                                if (bDate != todayStr || account.baselinePointsToday <= 0) {
                                    account.baselinePointsToday = if (preTotal > 0) preTotal else postBalance.availablePoints
                                    baselineDateMap[account.id] = todayStr
                                }
                                account.pointsEarnedToday = maxOf(serverEarned, maxOf(0, account.availablePoints - account.baselinePointsToday))
                                account.totalPoints = account.availablePoints

                                // Cập nhật rổ vào AccountProfile
                                account.pcProgress = postBalance.pcProgress
                                if (postBalance.pcMax > 0) account.pcMax = postBalance.pcMax
                                account.mobileProgress = postBalance.mobileProgress
                                if (postBalance.mobileMax > 0) account.mobileMax = postBalance.mobileMax
                                account.edgeProgress = postBalance.edgeProgress
                                if (postBalance.edgeMax > 0) account.edgeMax = postBalance.edgeMax

                                // Tự động targetSearches Level 1=35, Level 2=55
                                if (postBalance.mobileMax > 0 || postBalance.pcMax > 30) {
                                    account.level = 2
                                } else if (postBalance.pcMax in 1..30 && postBalance.mobileMax == 0) {
                                    account.level = 1
                                }
                                account.targetSearches = if (account.level == 1) 35 else 55

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
                                } else if (activePhase == SearchPhase.PHASE_1_PC && zCount >= 2 && account.pcProgress > 0) {
                                    phaseExhaustedMap.getOrPut(account.id) { Collections.synchronizedSet(mutableSetOf()) }.add(SearchPhase.PHASE_1_PC)
                                    vlogManager?.logPoints(
                                        "[TRIAL-PC] Đã hoàn tất thử nghiệm PC Search cho ${account.email}",
                                        "Microsoft không cộng thêm điểm rổ PC (ΔPC=0). Chuyển sang Phase 2 (Mobile).",
                                        account.email
                                    )
                                }

                                if (zeroCount >= 2) {
                                    // 2 batch liên tiếp ΔTotal == 0 => Cắm cờ Cooldown restricted 15 phút
                                    cooldownRestrictedAccounts.add(account.id)
                                    vlogManager?.logCooldown(
                                        "[COOL] Phát hiện Cooldown Restricted: 2 batch liên tiếp ΔTotal == 0 sau batch ($successfulSearchesInBatch lượt)",
                                        "Tài khoản ${account.email} đang bị Microsoft áp cờ kiểm soát 15 phút. Kích hoạt sàn toán học 920s.",
                                        account.email
                                    )
                                } else {
                                    // Mới 1 batch ΔTotal == 0 => Chỉ cảnh báo vlog, chưa cắm cờ Cooldown
                                    vlogManager?.logCooldown(
                                        "[WARN] Cảnh báo ΔTotal == 0 sau batch ($successfulSearchesInBatch lượt) [Lần $zeroCount/2]",
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
                            vlogManager?.logError("Lỗi đo Closed-Loop Basket Breakdown sau batch", e.message, account.email)
                        }
                    }

                    // 4. RÚT GỌN COOLDOWN SAU BATCH:
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
                        // Tự động cày bù nếu chưa đạt SearchPhase.DONE và < maxCatchUpSearches
                        account.completedSearchesToday < maxCatchUpSearches
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

                        val cooldownUntil = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(
                            Date(System.currentTimeMillis() + cooldownSecs * 1000L)
                        )
                        val modeLabel = if (cooldownSecs >= 900) "Cooldown Sàn Toán Học 920s" else if (account.completedSearchesToday >= account.targetSearches) "Micro-break Cày Bù (45-75s)" else "Fast Bursting"

                        onStatusUpdate(
                            "Nghỉ $modeLabel (${cooldownSecs / 60}m${cooldownSecs % 60}s)",
                            "Tài khoản ${account.email} sẽ chạy tiếp lúc $cooldownUntil (ΔPoints: ${if (deltaPoints >= 0) "+$deltaPoints" else "N/A"})"
                        )
                        vlogManager?.logCooldown(
                            "Bắt đầu nghỉ $modeLabel: ${cooldownSecs}s",
                            "Chạy tiếp lúc $cooldownUntil (ΔPoints: ${if (deltaPoints >= 0) "+$deltaPoints" else "N/A"})",
                            account.email
                        )
                        delay(cooldownSecs * 1000L)
                    }
                }

                if (allAccountsFinished) {
                    onStatusUpdate("Hoàn thành tất cả tài khoản!", "Tất cả tài khoản đã đạt chỉ tiêu hôm nay. Nghỉ đến sáng mai.")
                    delay(60 * 60 * 1000L) // Nghỉ 1 giờ trước khi kiểm tra lại
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        currentJob?.cancel()
        currentJob = null
        onStatusUpdate("Đã dừng node", "Dịch vụ đang ở trạng thái nghỉ.")
    }
}
