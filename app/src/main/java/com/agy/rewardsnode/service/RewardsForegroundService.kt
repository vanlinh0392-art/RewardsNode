package com.agy.rewardsnode.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.*
import android.view.View
import com.agy.rewardsnode.App
import com.agy.rewardsnode.core.AutomationEngine
import com.agy.rewardsnode.core.DataSaverWebViewClient
import com.agy.rewardsnode.core.FingerprintEngine
import com.agy.rewardsnode.core.KeywordEngine
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.core.RewardsStateMachine
import com.agy.rewardsnode.core.VlogManager
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.ui.MainActivity
import android.app.AlarmManager
import android.content.Context
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import com.agy.rewardsnode.utils.TelegramNotifier
import com.agy.rewardsnode.utils.WakeLockHelper
import org.json.JSONArray
import java.util.Calendar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android Foreground Service vận hành 24/7 cho Rewards Node (100% Native Android SDK).
 * Tự động tìm kiếm Bing, tương tác DOM, bảo lưu Cookie Jar, tự phục hồi khi crash,
 * và tích hợp chu kỳ bảo trì tự động dọn dẹp SQLite WAL & Vlog.
 */
class RewardsForegroundService : Service() {

    private val serviceScope = CoroutineScope(AndroidMainDispatcher + SupervisorJob())
    private lateinit var prefs: EncryptedPreferences
    private lateinit var database: AppDatabase
    private lateinit var profileManager: ProfileManager
    private lateinit var keywordEngine: KeywordEngine
    private lateinit var vlogManager: VlogManager
    private lateinit var stateMachine: RewardsStateMachine
    private lateinit var wakeLockHelper: WakeLockHelper
    private var telegramNotifier: TelegramNotifier? = null

    private var headlessWebView: WebView? = null
    private var activeAccountId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = EncryptedPreferences(this)
        database = AppDatabase.getDatabase(this)
        profileManager = ProfileManager(this)
        keywordEngine = KeywordEngine()
        keywordEngine.fetchTrendsAsync("US")
        keywordEngine.fetchTrendsAsync("VN")
        vlogManager = VlogManager.getInstance(this)
        wakeLockHelper = WakeLockHelper(this)
        telegramNotifier = if (prefs.telegramBotToken.isNotBlank() && prefs.telegramChatId.isNotBlank()) {
            TelegramNotifier(prefs.telegramBotToken, prefs.telegramChatId)
        } else null

        vlogManager.logSystem("Khởi tạo RewardsForegroundService", "Chuẩn bị vòng lặp điều phối")

        stateMachine = RewardsStateMachine(
            keywordEngine = keywordEngine,
            profileManager = profileManager,
            vlogManager = vlogManager,
            webViewProvider = { headlessWebView },
            wakeLockHelper = wakeLockHelper,
            onExecuteSearch = { account, keyword, phase ->
                executeSearchOnWebView(account, keyword, phase)
            },
            onStatusUpdate = { status, details ->
                updateNotification(status, details)
            },
            onAccountUpdated = { updatedAccount ->
                database.rewardsDao().updateProfile(updatedAccount)
            },
            onScheduleCooldownAlarm = { secs, account ->
                val alarmAction = Runnable {
                    destroyHeadlessWebView()
                    wakeLockHelper.release()
                    if (account.id == "all") {
                        scheduleDailyAlarm()
                        if (prefs.isAutoStopOnComplete) {
                            stopNode()
                        }
                    } else {
                        scheduleAlarmWakeup(secs, account.id)
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    alarmAction.run()
                } else {
                    mainHandler.post(alarmAction)
                }
            },
            telegramNotifier = telegramNotifier,
            database = database,
            globalProxyProvider = { prefs.globalProxy },
            searchDelayMinSec = prefs.searchDelayMinSec,
            searchDelayMaxSec = prefs.searchDelayMaxSec,
            maxClaimRetries = prefs.maxClaimRetries,
            dapiMaxRetries = prefs.dapiMaxRetries,
            cooldownTriggerBatches = prefs.cooldownTriggerBatches,
            frozenStopBatches = prefs.frozenStopBatches,
            maxReconciliationPasses = prefs.maxReconciliationPasses,
            encryptedPrefs = prefs
        )

        setupMaintenanceLoop()
    }

    private fun setupMaintenanceLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(24 * 60 * 60 * 1000L) // Mỗi 24 giờ thực hiện dọn dẹp bảo trì
                runMaintenance()
            }
        }
    }

    private suspend fun runMaintenance() = withContext(Dispatchers.IO) {
        try {
            vlogManager.logSystem("Bắt đầu chu kỳ bảo trì định kỳ 24h", "Dọn dẹp Vlog & Checkpoint WAL")
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            database.vlogDao().pruneOldVlogs(sevenDaysAgo)

            val snapshotDir = File(cacheDir, "dom_snapshots")
            if (snapshotDir.exists() && snapshotDir.isDirectory) {
                val files = snapshotDir.listFiles()?.sortedByDescending { it.lastModified() }
                if (files != null && files.size > 20) {
                    files.drop(20).forEach { it.delete() }
                }
            }

            database.checkpointWal()
            vlogManager.logSystem("Bảo trì định kỳ hoàn tất thành công")
        } catch (e: Exception) {
            e.printStackTrace()
            vlogManager.logError("Lỗi trong chu kỳ bảo trì", e.message)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val targetAccountId = intent.getStringExtra(EXTRA_TARGET_ACCOUNT_ID)
                val isScheduledCheck = intent.getBooleanExtra("is_scheduled_check", false)
                startNode(targetAccountId, isScheduledCheck)
            }
            ACTION_ALARM_WAKEUP -> {
                val wakeupNotification = buildNotification("Khôi phục tiến trình...", "Đang tiếp tục chu kỳ...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        App.NOTIFICATION_ID,
                        wakeupNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(App.NOTIFICATION_ID, wakeupNotification)
                }
                val targetAccountId = intent.getStringExtra(EXTRA_TARGET_ACCOUNT_ID)
                val isScheduledCheck = intent.getBooleanExtra("is_scheduled_check", false)
                if (stateMachine.isWaitingForAlarm() && !isScheduledCheck) {
                    stateMachine.notifyAlarmWakeup()
                } else {
                    startNode(targetAccountId, isScheduledCheck)
                }
            }
            ACTION_STOP -> stopNode()
            null -> {
                if (prefs.isNodeServiceRunning) {
                    startNode()
                }
            }
        }
        return START_STICKY
    }

    private fun startNode(targetAccountId: String? = null, isScheduledCheck: Boolean = false) {
        if (serviceStartElapsedRealtime == 0L) {
            serviceStartElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        }
        prefs.isNodeServiceRunning = true
        if (isScheduledCheck) {
            scheduleDailyAlarm(this)
        }
        val notification = buildNotification("Đang khởi động Node...", "Đang tải danh sách profile...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                App.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(App.NOTIFICATION_ID, notification)
        }

        serviceScope.launch(Dispatchers.IO) {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val allAccounts = database.rewardsDao().getAllActiveProfiles()
            val accounts = if (!targetAccountId.isNullOrBlank()) {
                allAccounts.filter { it.id == targetAccountId }
            } else if (isScheduledCheck) {
                allAccounts.filter { it.isEnabled }
            } else {
                allAccounts.filter {
                    it.isEnabled && !isAccountFullyCompletedToday(it, todayStr)
                }
            }
            if (accounts.isEmpty()) {
                if (allAccounts.isNotEmpty() && targetAccountId.isNullOrBlank()) {
                    val notifier = telegramNotifier ?: if (prefs.telegramBotToken.isNotBlank() && prefs.telegramChatId.isNotBlank()) {
                        TelegramNotifier(prefs.telegramBotToken, prefs.telegramChatId)
                    } else null
                    if (notifier != null && prefs.lastCompletionSummaryDate != todayStr) {
                        try {
                            wakeLockHelper.acquire("FastPathCompletionSummary", 60000L)
                            notifier.sendDailyCompletionReport(allAccounts, todayStr)
                            prefs.lastCompletionSummaryDate = todayStr
                        } catch (e: Exception) {
                            android.util.Log.e("RewardsForegroundService", "Lỗi gửi báo cáo hoàn thành Telegram fast-path: ${e.message}")
                        }
                    }
                }
                withContext(AndroidMainDispatcher) {
                    if (allAccounts.isEmpty()) {
                        updateNotification("Chưa có tài khoản nào", "Vui lòng mở ứng dụng để thêm tài khoản.")
                    } else if (!targetAccountId.isNullOrBlank()) {
                        updateNotification("Tài khoản đã hoàn tất!", "Tài khoản đơn lẻ đã đạt tối đa rổ điểm hôm nay ($todayStr).")
                        destroyHeadlessWebView()
                        wakeLockHelper.release()
                    } else {
                        updateNotification("Hoàn thành tất cả tài khoản!", "Tất cả tài khoản đã đạt tối đa rổ điểm hôm nay ($todayStr).")
                        scheduleDailyAlarm()
                        if (prefs.isAutoStopOnComplete) {
                            stopNode()
                        }
                    }
                }
                return@launch
            }

            withContext(AndroidMainDispatcher) {
                initHeadlessWebView()
                stateMachine.targetAccountId = targetAccountId
                stateMachine.searchDelayMinSec = prefs.searchDelayMinSec
                stateMachine.searchDelayMaxSec = prefs.searchDelayMaxSec
                stateMachine.maxClaimRetries = prefs.maxClaimRetries
                stateMachine.dapiMaxRetries = prefs.dapiMaxRetries
                stateMachine.cooldownTriggerBatches = prefs.cooldownTriggerBatches
                stateMachine.frozenStopBatches = prefs.frozenStopBatches
                stateMachine.maxReconciliationPasses = prefs.maxReconciliationPasses
                stateMachine.start(
                    accounts = accounts,
                    batchSize = prefs.batchSize,
                    cooldownMinutes = prefs.cooldownMinutes,
                    coroutineScope = serviceScope,
                    webView = headlessWebView
                )
            }

            if (prefs.telegramBotToken.isNotBlank() && prefs.telegramChatId.isNotBlank()) {
                val notifier = TelegramNotifier(prefs.telegramBotToken, prefs.telegramChatId)
                val targetInfo = if (!targetAccountId.isNullOrBlank()) {
                    "Chạy đơn lẻ tài khoản: ${accounts.firstOrNull()?.email ?: targetAccountId}"
                } else {
                    "Đang chạy ${accounts.size} tài khoản ngầm trên Android."
                }
                notifier.sendMessage("🚀 *Rewards Node Đã Khởi Động*\n$targetInfo")
            }
        }
    }

    private fun isAccountFullyCompletedToday(account: AccountProfile, todayStr: String): Boolean {
        if (account.lastActiveDate != todayStr) return false
        if (!account.dailySetDone) return false
        if (account.claimablePoints > 0) return false
        val effPcMax = if (account.pcMax > 0) account.pcMax else (if (account.level == 1) 30 else 90)
        val effMobMax = if (account.mobileMax > 0) account.mobileMax else (if (account.level == 1) 0 else 60)
        val effEdgeMax = if (account.edgeMax > 0) account.edgeMax else 0
        val pcDone = effPcMax == 0 || account.pcProgress >= effPcMax || prefs.isSearchExhaustedToday(account.id, todayStr)
        val mobDone = effMobMax == 0 || account.mobileProgress >= effMobMax
        val edgeDone = effEdgeMax == 0 || account.edgeProgress >= effEdgeMax
        val hasDapiCredentials = !account.dapiRefreshToken.isNullOrBlank() || !account.dapiAccessToken.isNullOrBlank()
        val checkInDone = prefs.isCheckInDoneToday(account.id, todayStr) ||
            (prefs.isPhase05DoneToday(account.id, todayStr) && !hasDapiCredentials) ||
            (prefs.getDapiAttemptsToday(account.id, todayStr) >= maxOf(1, prefs.dapiMaxRetries))
        val readDone = account.readMax == 0 ||
            account.readProgress >= account.readMax ||
            (prefs.isPhase05DoneToday(account.id, todayStr) && !hasDapiCredentials) ||
            (prefs.getDapiAttemptsToday(account.id, todayStr) >= maxOf(1, prefs.dapiMaxRetries))
        return pcDone && mobDone && edgeDone && readDone && checkInDone
    }

    private fun stopNode() {
        serviceStartElapsedRealtime = 0L
        runningAccountIdFlow.value = null
        prefs.isNodeServiceRunning = false
        stateMachine.stop()
        wakeLockHelper.release()
        val stopOnMain = Runnable {
            destroyHeadlessWebView()
            try {
                stopForeground(true)
            } catch (_: Exception) {}
            stopSelf()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopOnMain.run()
        } else {
            mainHandler.post(stopOnMain)
        }
    }

    private fun initHeadlessWebView() {
        if (headlessWebView == null) {
            headlessWebView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                FingerprintEngine.stripXRequestedWith(this)
                measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 1080, 1920)
            }
            headlessWebView?.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.confirm()
                    return true
                }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.confirm()
                    return true
                }
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(headlessWebView, true)
            }
        }
    }

    private fun destroyHeadlessWebView() {
        activeAccountId = null
        val viewToDestroy = headlessWebView
        headlessWebView = null
        if (viewToDestroy != null) {
            val destroyAction = Runnable {
                try {
                    viewToDestroy.stopLoading()
                    viewToDestroy.loadUrl("about:blank")
                    viewToDestroy.clearHistory()
                    viewToDestroy.removeAllViews()
                    viewToDestroy.destroy()
                } catch (_: Exception) {}
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                destroyAction.run()
            } else {
                mainHandler.post(destroyAction)
            }
        }
    }

    /**
     * Thực thi 1 lượt tìm kiếm trên WebView ngầm và đợi tải xong kết quả.
     */
    private suspend fun executeSearchOnWebView(
        account: AccountProfile,
        keyword: String,
        phase: RewardsStateMachine.SearchPhase
    ): Boolean = withContext(AndroidMainDispatcher) {
        runningAccountIdFlow.value = account.id
        wakeLockHelper.acquire("Search_${account.id}")
        try {
            initHeadlessWebView()
            val webView = headlessWebView ?: return@withContext false
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            // Quản lý session boundary: chỉ hoán đổi session khi đổi sang tài khoản khác.
            // Không thực hiện routeWebViewViaProxy + restoreCookiesForProfile lặp lại trên mỗi lượt tìm kiếm.
            if (profileManager.activeAccountId != account.id) {
                profileManager.switchSession(
                    webView = webView,
                    targetAccount = account,
                    globalProxy = prefs.globalProxy,
                    onSaveOutgoingSession = { outgoingId, cookies ->
                        val outgoingAcc = database.rewardsDao().getProfileById(outgoingId)
                        if (outgoingAcc != null) {
                            outgoingAcc.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(outgoingAcc.cookiesSnapshot, cookies)
                            database.rewardsDao().updateProfile(outgoingAcc)
                        }
                    }
                )
            }
            activeAccountId = account.id

            val isDesktop = phase.isDesktop
            val preset = FingerprintEngine.getEdgePresetForAccount(account.id, isDesktop)
            webView.settings.userAgentString = preset.userAgent

            // Đo và layout WebView theo kích thước thực tế
            val w = if (isDesktop) 1920 else preset.screenWidth
            val h = if (isDesktop) 1080 else preset.screenHeight
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, w, h)

            // Tiêm cookie xác thực Edge
            CookieManager.getInstance().setCookie("https://www.bing.com", EDGE_V_COOKIE)

            // Pre-Flight Auth Gate: Đảm bảo tài khoản đã được xác thực Rewards User trên www.bing.com (Issue #36)
            val isBingAuthOk = ensureBingSearchAuth(account, webView, preset)
            if (!isBingAuthOk) {
                stateMachine.markAuthDesync(account.id)
                vlogManager.logError(
                    "[AUTH-DESYNC] Huỷ lượt tìm kiếm cho ${account.email}",
                    "Tài khoản không được www.bing.com nhận diện là Rewards User (isRewardsUser == false). Hủy để chống cày bù mù quáng 0 điểm.",
                    account.email
                )
                return@withContext false
            } else {
                stateMachine.clearAuthDesync(account.id)
            }

            val phaseTag = when (phase) {
                RewardsStateMachine.SearchPhase.PHASE_1_PC -> "[SEARCH-PC]"
                RewardsStateMachine.SearchPhase.PHASE_2_MOBILE -> "[SEARCH-MOB]"
                RewardsStateMachine.SearchPhase.PHASE_3_EDGE -> "[SEARCH-EDGE]"
                else -> "[SEARCH]"
            }
            val phaseLabel = phase.label
            vlogManager.logSearch("$phaseTag Bắt đầu lượt tìm kiếm cho ${account.email}", "[$phaseLabel] Từ khóa: \"$keyword\" (${preset.id})", account.email)

            val searchUrl = buildSearchUrl(keyword, phase)
            val extraHeaders = buildExtraHeaders(preset, isDesktop)

            return@withContext suspendCancellableCoroutine { continuation ->
                val isCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
                var isSerpDwellStarted = false
                var hasInjectedTyping = false
                var hasNavigatedToSerp = false
                val startTimeMs = System.currentTimeMillis()
                val minTypingTimeMs = (keyword.length * 75L) + 350L

                var typingRunnable: Runnable? = null
                var dwellRunnable: Runnable? = null

                val timeoutRunnable = Runnable {
                    if (!isCompleted.get() && continuation.isActive) {
                        isCompleted.set(true)
                        typingRunnable?.let { r -> mainHandler.removeCallbacks(r) }
                        dwellRunnable?.let { r -> mainHandler.removeCallbacks(r) }
                        vlogManager.logError("Hard Deadline Timeout (45s). Hủy lượt tìm kiếm để giải phóng tài nguyên.", "Keyword: $keyword", account.email, webView)
                        profileManager.clearActiveSessionAsync(webView)
                        activeAccountId = null
                        continuation.resume(false)
                    }
                }

                continuation.invokeOnCancellation {
                    isCompleted.set(true)
                    mainHandler.removeCallbacks(timeoutRunnable)
                    typingRunnable?.let { r -> mainHandler.removeCallbacks(r) }
                    dwellRunnable?.let { r -> mainHandler.removeCallbacks(r) }
                }

                fun injectHumanTyping(targetView: WebView) {
                    if (hasInjectedTyping || isCompleted.get()) return
                    hasInjectedTyping = true
                    vlogManager.logDom("Tiêm Human Typing Script gõ từ khóa: \"$keyword\"", account = account.email)
                    val script = AutomationEngine.createHumanTypingScript(keyword, searchUrl)
                    targetView.evaluateJavascript(script, null)
                }

                webView.webViewClient = object : DataSaverWebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val currentUrl = url ?: ""
                        if (currentUrl.contains("bing.com/search")) {
                            hasNavigatedToSerp = true
                        }
                        val stealthScript = FingerprintEngine.createStealthScript(preset, isDesktop)
                        view?.evaluateJavascript(stealthScript, null)

                        // Giả lập hiển thị Foreground (document.visibilityState = 'visible' & document.hidden = false)
                        val visibilityScript = """
                            (function() {
                                try {
                                    Object.defineProperty(document, 'visibilityState', {
                                        get: function() { return 'visible'; },
                                        configurable: true
                                    });
                                    Object.defineProperty(document, 'hidden', {
                                        get: function() { return false; },
                                        configurable: true
                                    });
                                    Object.defineProperty(Document.prototype, 'visibilityState', {
                                        get: function() { return 'visible'; },
                                        configurable: true
                                    });
                                    Object.defineProperty(Document.prototype, 'hidden', {
                                        get: function() { return false; },
                                        configurable: true
                                    });
                                    window.dispatchEvent(new Event('focus'));
                                    document.dispatchEvent(new Event('visibilitychange'));
                                } catch (e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(visibilityScript, null)
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        isCompleted.set(true)
                        vlogManager.logError("Render process gone (Chromium renderer sập/thiếu RAM). Cấp phát lại WebView...", null, account.email)
                        destroyHeadlessWebView()
                        mainHandler.removeCallbacks(timeoutRunnable)
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val currentUrl = url ?: ""

                        if (currentUrl.contains("live.com/Abuse") || currentUrl.contains("challenges.cloudflare") || currentUrl.contains("identity/confirm")) {
                            if (!isCompleted.get()) {
                                isCompleted.set(true)
                                mainHandler.removeCallbacks(timeoutRunnable)
                                vlogManager.logCaptcha("Phát hiện thử thách xác minh/khóa nick: $currentUrl", account = account.email)
                                vlogManager.logError("Dừng và tự động tắt tài khoản do gặp trang thử thách bảo mật", currentUrl, account.email, view)

                                // Day 11: Chụp ảnh màn hình WebView trong RAM và gửi cảnh báo qua Telegram Bot API
                                if (view != null && view.width > 0 && view.height > 0 && prefs.telegramBotToken.isNotBlank() && prefs.telegramChatId.isNotBlank()) {
                                    val screenshot = TelegramNotifier.captureBitmap(view)
                                    if (screenshot != null) {
                                        val alertCaption = "⚠️ *CẢNH BÁO XÁC MINH / BẢO MẬT BING REWARDS*\n" +
                                            "👤 Tài khoản: `${account.email}`\n" +
                                            "🔗 URL: `$currentUrl`\n" +
                                            "🛡️ Hành động: Tự động dừng nick, tắt tài khoản để bảo vệ an toàn."
                                        val notifier = TelegramNotifier(prefs.telegramBotToken, prefs.telegramChatId)
                                        serviceScope.launch(Dispatchers.IO) {
                                            try {
                                                notifier.sendScreenshot(screenshot, alertCaption)
                                            } finally {
                                                screenshot.recycle()
                                            }
                                        }
                                    }
                                }

                                // Tự động tắt tài khoản trong DB để tránh lặp và chống ban lan truyền
                                account.isEnabled = false
                                serviceScope.launch(Dispatchers.IO) {
                                    database.rewardsDao().updateProfile(account)
                                }

                                profileManager.clearActiveSessionAsync(view)
                                activeAccountId = null
                                if (continuation.isActive) continuation.resume(false)
                            }
                            return
                        }

                        // Nếu tải xong trang chủ Bing (hoặc trang non-SERP) mà chưa tiêm gõ từ khóa
                        if (!hasInjectedTyping && !currentUrl.contains("bing.com/search") && currentUrl.contains("bing.com")) {
                            typingRunnable = Runnable {
                                if (!isCompleted.get() && !hasInjectedTyping && view != null) {
                                    injectHumanTyping(view)
                                }
                            }
                            mainHandler.postDelayed(typingRunnable!!, 400L)
                            return
                        }

                        val isSerpFinished = currentUrl.contains("bing.com/search") &&
                                (hasNavigatedToSerp || hasInjectedTyping || System.currentTimeMillis() - startTimeMs >= minTypingTimeMs)

                        if (isSerpFinished && !isCompleted.get() && !isSerpDwellStarted) {
                            isSerpDwellStarted = true
                            dwellRunnable?.let { mainHandler.removeCallbacks(it) }
                            // Gia hạn timeout watchdog thêm 25s cho dwell + balance extract an toàn
                            mainHandler.removeCallbacks(timeoutRunnable)
                            mainHandler.postDelayed(timeoutRunnable, 25000L)

                            view?.evaluateJavascript("(function(){ return !!(document.querySelector('#challenge-stage') || document.querySelector('iframe[src*=\"arkoselabs\"]')); })();") { hasChallenge ->
                                if (isCompleted.get() || !continuation.isActive || activeAccountId != account.id) return@evaluateJavascript
                                if (hasChallenge == "true") {
                                    isCompleted.set(true)
                                    mainHandler.removeCallbacks(timeoutRunnable)
                                    dwellRunnable?.let { mainHandler.removeCallbacks(it) }
                                    vlogManager.logCaptcha("Phát hiện Arkose Labs / Cloudflare Challenge trong DOM", account = account.email)
                                    vlogManager.logError("Dừng tài khoản do phát hiện Challenge trong DOM", null, account.email, view)

                                    if (view != null && view.width > 0 && view.height > 0 && prefs.telegramBotToken.isNotBlank() && prefs.telegramChatId.isNotBlank()) {
                                        val screenshot = TelegramNotifier.captureBitmap(view)
                                        if (screenshot != null) {
                                            val alertCaption = "⚠️ *CẢNH BÁO CAPTCHA ARKOSE LABS BING REWARDS*\n" +
                                                "👤 Tài khoản: `${account.email}`\n" +
                                                "🧩 Phát hiện: Arkose Labs / Cloudflare Challenge trong DOM\n" +
                                                "🛡️ Hành động: Tự động dừng nick để bảo vệ an toàn."
                                            val notifier = TelegramNotifier(prefs.telegramBotToken, prefs.telegramChatId)
                                            serviceScope.launch(Dispatchers.IO) {
                                                try {
                                                    notifier.sendScreenshot(screenshot, alertCaption)
                                                } finally {
                                                    screenshot.recycle()
                                                }
                                            }
                                        }
                                    }

                                    account.isEnabled = false
                                    serviceScope.launch(Dispatchers.IO) {
                                        database.rewardsDao().updateProfile(account)
                                    }

                                    if (continuation.isActive) continuation.resume(false)
                                    return@evaluateJavascript
                                }

                                val isRestricted = stateMachine.isAccountCooldownRestricted(account.id)
                                val zeroCount = stateMachine.getConsecutiveZeroDeltaSearches(account.id)
                                val effectiveDelta = if (zeroCount > 0) 0 else 1
                                val dwellTimeMs = AutomationEngine.computeDynamicDwellTime(deltaPoints = effectiveDelta, isRestricted = isRestricted)

                                vlogManager.logDom("Tải xong kết quả tìm kiếm. Giữ trang (${dwellTimeMs / 1000}s), ${if (isDesktop) "cuộn rời rạc" else "tương tác chạm cảm ứng (DOM Touch)"} & phát MotionEvent native", account = account.email)
                                val scrollScript = if (isDesktop) AutomationEngine.createDiscreteScrollScript() else AutomationEngine.createMobileTouchInteractionScript()
                                AutomationEngine.dispatchNativeKineticScroll(view, 750f, 350f, 300L)

                                // Bóc tách piggyback suggestions từ DOM SERP đưa vào KeywordEngine
                                val piggybackScript = AutomationEngine.createPiggybackExtractorScript()
                                view.evaluateJavascript(piggybackScript) { suggestionsJson ->
                                    if (isCompleted.get() || !continuation.isActive || activeAccountId != account.id) return@evaluateJavascript
                                    try {
                                        if (!suggestionsJson.isNullOrBlank() && suggestionsJson.length <= 16384 && suggestionsJson != "null" && suggestionsJson != "[]") {
                                            val jsonArray = JSONArray(suggestionsJson)
                                            val suggestions = mutableListOf<String>()
                                            for (idx in 0 until jsonArray.length()) {
                                                val item = jsonArray.optString(idx, "").trim()
                                                if (item.isNotBlank()) {
                                                    suggestions.add(item)
                                                }
                                            }
                                            if (suggestions.isNotEmpty()) {
                                                keywordEngine.feedPiggybackSuggestions(suggestions)
                                                vlogManager.logSearch("Thu hoạch ${suggestions.size} từ khóa gợi ý tự nhiên (Piggyback)", suggestions.take(3).joinToString(", "), account.email)
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }

                                view.evaluateJavascript(scrollScript) {
                                    if (isCompleted.get() || !continuation.isActive || activeAccountId != account.id) return@evaluateJavascript
                                    dwellRunnable?.let { mainHandler.removeCallbacks(it) }
                                    dwellRunnable = Runnable {
                                        if (isCompleted.get() || !continuation.isActive || activeAccountId != account.id) return@Runnable
                                        // Lưu cookie mới nhất nếu phiên đăng nhập Bing hợp lệ
                                        val savedCookies = profileManager.dumpCookiesForProfile(account.id)
                                        if (savedCookies.isNotBlank() && profileManager.hasValidBingSession(savedCookies)) {
                                            account.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, savedCookies)
                                            serviceScope.launch(Dispatchers.IO) {
                                                database.rewardsDao().updateProfile(account)
                                            }
                                        }

                                        isCompleted.set(true)
                                        mainHandler.removeCallbacks(timeoutRunnable)
                                        vlogManager.logSearch("$phaseTag Lượt tìm kiếm hoàn tất thành công", "Keyword: \"$keyword\" (Dwell: ${dwellTimeMs / 1000}s)", account.email)
                                        if (continuation.isActive) {
                                            continuation.resume(true)
                                        }
                                    }
                                    dwellRunnable?.let { mainHandler.postDelayed(it, dwellTimeMs) } // Dwell time 8-12s
                                }
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && !isCompleted.get()) {
                            isCompleted.set(true)
                            mainHandler.removeCallbacks(timeoutRunnable)
                            vlogManager.logError("Lỗi tải trang chính WebView", "Error code: ${error?.errorCode}", account.email, view)
                            val effectiveProxy = ProfileManager.resolveEffectiveProxy(account.proxyUrl, prefs.globalProxy)
                            if (!effectiveProxy.isNullOrBlank()) {
                                val tripped = profileManager.recordProxyFailure(effectiveProxy)
                                if (tripped) {
                                    vlogManager.logNetwork("Proxy Circuit Breaker ĐÃ NGẮT cho $effectiveProxy (Lỗi >= 3 lần liên tiếp)", "Tự động cách ly tài khoản", account.email)
                                }
                            }
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }
                }

                mainHandler.postDelayed(timeoutRunnable, 45000L)

                vlogManager.logNetwork("$phaseTag Tải trang chủ Bing để bắt đầu tìm kiếm người thật", "Proxy: ${ProfileManager.resolveEffectiveProxy(account.proxyUrl, prefs.globalProxy) ?: "Direct"}", account.email)
                webView.loadUrl("https://www.bing.com/", extraHeaders)
            }
        } finally {
            // Giữ CPU/WiFi lock xuyên suốt inter-search jitter; chỉ nhả khi Cooldown/Stop/Complete
        }
    }

    /**
     * Pre-Flight Auth Gate: Xác minh tài khoản được www.bing.com nhận diện là Rewards User.
     * Dựa trên chuẩn Issue #36 của MS-Rewards-Farmer.
     * Nếu isRewardsUser == false: tự động kích hoạt Silent SSO Handshake ngầm để lấy _RwBf và cấp quyền tìm kiếm.
     */
    private suspend fun ensureBingSearchAuth(
        account: AccountProfile,
        webView: WebView,
        preset: FingerprintEngine.FingerprintPreset
    ): Boolean {
        val effectiveProxy = ProfileManager.resolveEffectiveProxy(account.proxyUrl, prefs.globalProxy)

        // Lấy cookies hiện tại của bing.com
        CookieManager.getInstance().flush()
        val currentBingCookies = CookieManager.getInstance().getCookie("https://www.bing.com") ?: ""
        val mergedCookies = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, currentBingCookies)

        // 1. Kiểm tra nhanh trạng thái isRewardsUser qua endpoint nhẹ panelflyout/getuserinfo (Pure HTTP ~300ms)
        val isRewardsUser = withContext(Dispatchers.IO) {
            RewardsBalanceExtractor.checkIsRewardsUser(
                bingCookies = mergedCookies,
                userAgent = preset.userAgent,
                proxyUrl = effectiveProxy
            )
        }

        if (isRewardsUser) {
            return true
        }

        vlogManager.logNetwork(
            "[AUTH-DESYNC] Phát hiện www.bing.com chưa đồng bộ trạng thái Rewards (isRewardsUser == false)",
            "Tài khoản: ${account.email}. Kích hoạt Silent SSO Handshake ngầm để lấy _RwBf và cấp quyền tìm kiếm...",
            account.email
        )

        // 2. Thử Pure HTTP SSO Renewal (Không mở UI / Không chiếm WebView)
        val renewedCookies = withContext(Dispatchers.IO) {
            try {
                ProfileManager.renewCookiesViaPureHttp(
                    cookiesSnapshot = mergedCookies,
                    userAgent = preset.userAgent,
                    proxyUrl = effectiveProxy
                )
            } catch (e: Exception) {
                null
            }
        }

        if (!renewedCookies.isNullOrBlank()) {
            profileManager.restoreCookiesForProfile(account.id, renewedCookies)
            CookieManager.getInstance().flush()
            account.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, renewedCookies)
            serviceScope.launch(Dispatchers.IO) {
                database.rewardsDao().updateProfile(account)
            }

            // Kiểm tra lại sau Pure HTTP
            val recheckHttp = withContext(Dispatchers.IO) {
                val updatedBing = CookieManager.getInstance().getCookie("https://www.bing.com") ?: ""
                RewardsBalanceExtractor.checkIsRewardsUser(
                    bingCookies = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, updatedBing),
                    userAgent = preset.userAgent,
                    proxyUrl = effectiveProxy
                )
            }
            if (recheckHttp) {
                vlogManager.logNetwork(
                    "[AUTH-SYNC] Tái xác thực Bing Search Auth thành công qua Pure HTTP",
                    "Đã thu hoạch cookie định danh và cấp quyền tìm kiếm cho ${account.email}",
                    account.email
                )
                return true
            }
        }

        // 3. Fallback: Silent SSO Handshake qua WebView ngầm
        vlogManager.logNetwork(
            "[AUTH-SSO] Kích hoạt Silent SSO Handshake qua WebView cho ${account.email}",
            ProfileManager.BING_SSO_SIGNIN_URL,
            account.email
        )

        suspendCancellableCoroutine<Boolean> { ssoCont ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val cur = url ?: ""
                    if (cur.contains("bing.com") && !cur.contains("signin") && !cur.contains("oauth")) {
                        CookieManager.getInstance().flush()
                        val updatedCookies = profileManager.dumpCookiesForProfile(account.id)
                        if (updatedCookies.isNotBlank() && profileManager.hasValidBingSession(updatedCookies)) {
                            account.cookiesSnapshot = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, updatedCookies)
                            serviceScope.launch(Dispatchers.IO) {
                                database.rewardsDao().updateProfile(account)
                            }
                        }
                        if (ssoCont.isActive) ssoCont.resume(true)
                    }
                }
            }
            webView.loadUrl(ProfileManager.BING_SSO_SIGNIN_URL)
            mainHandler.postDelayed({
                if (ssoCont.isActive) ssoCont.resume(false)
            }, 12000L)
        }

        // 4. Kiểm tra lần cuối sau WebView SSO Handshake
        CookieManager.getInstance().flush()
        val postSsoCookies = CookieManager.getInstance().getCookie("https://www.bing.com") ?: ""
        val finalCheck = withContext(Dispatchers.IO) {
            RewardsBalanceExtractor.checkIsRewardsUser(
                bingCookies = ProfileManager.mergeCookieSnapshots(account.cookiesSnapshot, postSsoCookies),
                userAgent = preset.userAgent,
                proxyUrl = effectiveProxy
            )
        }

        if (finalCheck) {
            vlogManager.logNetwork(
                "[AUTH-SYNC] Tái xác thực Bing Search Auth thành công qua WebView SSO",
                "Tài khoản ${account.email} đã được công nhận là Rewards User.",
                account.email
            )
            return true
        }

        return false
    }

    private fun updateNotification(status: String, details: String) {
        val notification = buildNotification(status, details)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(App.NOTIFICATION_ID, notification)
    }

    private fun buildNotification(status: String, details: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RewardsForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder.setContentTitle("Rewards Node: $status")
            .setContentText(details)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Dừng Node",
                    stopPendingIntent
                ).build()
            )
        }

        return builder.build()
    }

    fun scheduleAlarmWakeup(cooldownSecs: Long, accountId: String? = null, isDailyReset: Boolean = false) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, CooldownAlarmReceiver::class.java).apply {
            action = CooldownAlarmReceiver.ACTION_COOLDOWN_WAKEUP
            if (!accountId.isNullOrBlank()) {
                putExtra(CooldownAlarmReceiver.EXTRA_ACCOUNT_ID, accountId)
            }
            putExtra("is_daily_reset", isDailyReset)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(this, RC_COOLDOWN_ALARM, intent, flags)

        val triggerAtMillis = System.currentTimeMillis() + (cooldownSecs * 1000L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            val wakeupTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(triggerAtMillis))
            vlogManager.logCooldown(
                "[ZERO WAKE-LOCK] Đã lên lịch AlarmManager (${cooldownSecs}s) thức giấc lúc $wakeupTimeStr",
                "Giải phóng WebView & nhả CPU WakeLock để thiết bị vào Deep Sleep (Doze Mode)."
            )
        } catch (e: Exception) {
            vlogManager.logError("Lỗi lên lịch AlarmManager thức giấc", e.message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runningAccountIdFlow.value = null
        serviceScope.cancel()
        destroyHeadlessWebView()
        wakeLockHelper.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun scheduleDailyAlarm() {
        destroyHeadlessWebView()
        wakeLockHelper.release()
        Companion.scheduleDailyAlarm(this)
    }

    companion object {
        const val ACTION_START = "com.agy.rewardsnode.START"
        const val ACTION_STOP = "com.agy.rewardsnode.STOP"
        const val ACTION_ALARM_WAKEUP = "com.agy.rewardsnode.ALARM_WAKEUP"
        const val EXTRA_TARGET_ACCOUNT_ID = "extra_target_account_id"
        const val RC_COOLDOWN_ALARM = 1001
        const val RC_DAILY_SCHEDULE_ALARM = 1002
        const val EDGE_V_COOKIE = "_EDGE_V=1; path=/; domain=.bing.com"
        val runningAccountIdFlow = MutableStateFlow<String?>(null)
        @Volatile var serviceStartElapsedRealtime: Long = 0L

        fun buildSearchUrl(keyword: String, phase: RewardsStateMachine.SearchPhase): String {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            return when (phase) {
                RewardsStateMachine.SearchPhase.PHASE_1_PC -> "https://www.bing.com/search?q=$encodedKeyword&form=QBLH"
                RewardsStateMachine.SearchPhase.PHASE_2_MOBILE -> "https://www.bing.com/search?q=$encodedKeyword&form=STARTR&PC=EMMX01"
                RewardsStateMachine.SearchPhase.PHASE_3_EDGE -> "https://www.bing.com/search?q=$encodedKeyword&form=MA13FW&pc=EUPH"
                else -> "https://www.bing.com/search?q=$encodedKeyword&form=QBLH"
            }
        }

        fun buildExtraHeaders(preset: FingerprintEngine.FingerprintPreset, isDesktop: Boolean): Map<String, String> {
            val extraHeaders = mutableMapOf(
                "Referer" to "https://www.bing.com/",
                "sec-ch-ua" to preset.secChUa,
                "sec-ch-ua-mobile" to if (isDesktop) "?0" else "?1",
                "sec-ch-ua-platform" to preset.secChUaPlatform
            )
            if (!isDesktop && preset.secChUaModel.isNotBlank()) {
                extraHeaders["sec-ch-ua-model"] = preset.secChUaModel
            }
            return extraHeaders
        }

        /**
         * Tính toán mốc thời gian kích hoạt gần nhất trong tương lai từ danh sách 1-3 khung giờ hẹn hàng ngày.
         * Trả về Pair(triggerAtMillis, Pair(hour, minute)).
         */
        fun computeNextScheduleTriggerMillis(
            slots: List<Pair<Int, Int>>,
            nowMillis: Long = System.currentTimeMillis()
        ): Pair<Long, Pair<Int, Int>>? {
            if (slots.isEmpty()) return null
            val candidates = slots.map { (hour, minute) ->
                val cal = Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis <= nowMillis + 5000L) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                Pair(cal.timeInMillis, Pair(hour, minute))
            }
            return candidates.minByOrNull { it.first }
        }

        fun cancelDailyAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, CooldownAlarmReceiver::class.java).apply {
                action = CooldownAlarmReceiver.ACTION_COOLDOWN_WAKEUP
                putExtra("is_daily_reset", true)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, RC_DAILY_SCHEDULE_ALARM, intent, flags)
            alarmManager.cancel(pendingIntent)
        }

        fun scheduleDailyAlarm(context: Context) {
            val prefs = EncryptedPreferences(context)
            if (!prefs.isAutoDailySchedule) {
                cancelDailyAlarm(context)
                return
            }

            val enabledSlots = prefs.getEnabledScheduleSlots()
            if (enabledSlots.isEmpty()) {
                cancelDailyAlarm(context)
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val nextSlot = computeNextScheduleTriggerMillis(enabledSlots) ?: return
            val triggerAtMillis = nextSlot.first
            val targetHour = nextSlot.second.first
            val targetMinute = nextSlot.second.second

            val intent = Intent(context, CooldownAlarmReceiver::class.java).apply {
                action = CooldownAlarmReceiver.ACTION_COOLDOWN_WAKEUP
                putExtra("is_daily_reset", true)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, RC_DAILY_SCHEDULE_ALARM, intent, flags)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(triggerAtMillis))
                val slotsSummary = enabledSlots.joinToString(", ") { String.format(Locale.getDefault(), "%02d:%02d", it.first, it.second) }
                val vlogManager = VlogManager.getInstance(context)
                vlogManager.logSystem(
                    "🌅 [DAILY SCHEDULE] Đã lập lịch AlarmManager kiểm tra tiếp theo lúc $timeStr",
                    "Khung giờ kế tiếp: ${String.format(Locale.getDefault(), "%02d:%02d", targetHour, targetMinute)} | Các khung giờ hẹn trong ngày: [$slotsSummary] (100% Zero-WakeLock Deep Sleep)."
                )
            } catch (e: Exception) {
                val vlogManager = VlogManager.getInstance(context)
                vlogManager.logError("Lỗi lên lịch AlarmManager hàng ngày", e.message)
            }
        }
    }
}
