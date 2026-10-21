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
import com.agy.rewardsnode.App
import com.agy.rewardsnode.core.AutomationEngine
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
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import com.agy.rewardsnode.utils.TelegramNotifier
import com.agy.rewardsnode.utils.WakeLockHelper
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

    private var headlessWebView: WebView? = null
    private var activeAccountId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = EncryptedPreferences(this)
        database = AppDatabase.getDatabase(this)
        profileManager = ProfileManager(this)
        keywordEngine = KeywordEngine()
        vlogManager = VlogManager.getInstance(this)
        wakeLockHelper = WakeLockHelper(this)

        vlogManager.logSystem("Khởi tạo RewardsForegroundService", "Chuẩn bị vòng lặp điều phối")

        stateMachine = RewardsStateMachine(
            keywordEngine = keywordEngine,
            profileManager = profileManager,
            vlogManager = vlogManager,
            webViewProvider = { headlessWebView },
            onExecuteSearch = { account, keyword, phase ->
                executeSearchOnWebView(account, keyword, phase)
            },
            onStatusUpdate = { status, details ->
                updateNotification(status, details)
            },
            onAccountUpdated = { updatedAccount ->
                database.rewardsDao().updateProfile(updatedAccount)
            }
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
            ACTION_START -> startNode()
            ACTION_STOP -> stopNode()
            null -> {
                if (prefs.isNodeServiceRunning) {
                    startNode()
                }
            }
        }
        return START_STICKY
    }

    private fun startNode() {
        prefs.isNodeServiceRunning = true
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
            val accounts = allAccounts.filter { it.isEnabled && !it.isAllMaxedOutToday(todayStr) }
            if (accounts.isEmpty()) {
                withContext(AndroidMainDispatcher) {
                    if (allAccounts.isEmpty()) {
                        updateNotification("Chưa có tài khoản nào", "Vui lòng mở ứng dụng để thêm tài khoản.")
                    } else {
                        updateNotification("Hoàn thành tất cả tài khoản!", "Tất cả tài khoản đã đạt tối đa rổ điểm hôm nay ($todayStr).")
                    }
                }
                return@launch
            }

            withContext(AndroidMainDispatcher) {
                initHeadlessWebView()
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
                notifier.sendMessage("🚀 *Rewards Node Đã Khởi Động*\nĐang chạy ${accounts.size} tài khoản ngầm trên Android.")
            }
        }
    }

    private fun stopNode() {
        runningAccountIdFlow.value = null
        prefs.isNodeServiceRunning = false
        stateMachine.stop()
        destroyHeadlessWebView()
        wakeLockHelper.release()
        stopForeground(true)
        stopSelf()
    }

    private fun initHeadlessWebView() {
        if (headlessWebView == null) {
            headlessWebView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(headlessWebView, true)
            }
        }
    }

    private fun destroyHeadlessWebView() {
        activeAccountId = null
        headlessWebView?.let { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        headlessWebView = null
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
            initHeadlessWebView()
            val webView = headlessWebView ?: return@withContext false
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            // Quản lý session boundary: chỉ clear session khi đổi sang tài khoản khác.
            // Khi đổi UA giữa Desktop và Mobile trong cùng 1 account: KHÔNG gọi clearActiveSession,
            // chỉ cập nhật webView.settings.userAgentString và stealth script để duy trì phiên đăng nhập & cookie.
            if (activeAccountId != account.id) {
                profileManager.clearActiveSession(webView)
                activeAccountId = account.id
            }
            // Nạp Cookie của tài khoản hiện tại
            profileManager.restoreCookiesForProfile(account.id, account.cookiesSnapshot)

            // Kiểm tra và tự động kích hoạt Bing SSO Handshake nếu tài khoản chưa có token _RwBf
            if (!profileManager.hasRwBfToken(account.cookiesSnapshot)) {
                vlogManager.logNetwork("Tài khoản chưa có _RwBf -> Kích hoạt SSO Handshake ngầm để nhận diện Rewards User", ProfileManager.BING_SSO_SIGNIN_URL, account.email)
                suspendCancellableCoroutine<Boolean> { ssoCont ->
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val cur = url ?: ""
                            if (cur.contains("bing.com") && !cur.contains("signin") && !cur.contains("oauth")) {
                                CookieManager.getInstance().flush()
                                val updatedCookies = profileManager.dumpCookiesForProfile(account.id)
                                if (updatedCookies.isNotBlank()) {
                                    account.cookiesSnapshot = updatedCookies
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
                    }, 10000L)
                }
            }

            val isDesktop = phase.isDesktop
            val preset = FingerprintEngine.getEdgePresetForAccount(account.id, isDesktop)
            webView.settings.userAgentString = preset.userAgent

            val phaseTag = when (phase) {
                RewardsStateMachine.SearchPhase.PHASE_1_PC -> "[SEARCH-PC]"
                RewardsStateMachine.SearchPhase.PHASE_2_MOBILE -> "[SEARCH-MOB]"
                RewardsStateMachine.SearchPhase.PHASE_3_EDGE -> "[SEARCH-EDGE]"
                else -> "[SEARCH]"
            }
            val phaseLabel = phase.label
            vlogManager.logSearch("$phaseTag Bắt đầu lượt tìm kiếm cho ${account.email}", "[$phaseLabel] Từ khóa: \"$keyword\" (${preset.id})", account.email)

            return@withContext suspendCancellableCoroutine { continuation ->
                var isCompleted = false

                val timeoutRunnable = Runnable {
                    if (!isCompleted && continuation.isActive) {
                        isCompleted = true
                        vlogManager.logError("Hard Deadline Timeout (45s). Hủy lượt tìm kiếm để giải phóng tài nguyên.", "Keyword: $keyword", account.email, webView)
                        profileManager.clearActiveSessionAsync(webView)
                        activeAccountId = null
                        wakeLockHelper.release()
                        continuation.resume(false)
                    }
                }

                continuation.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    wakeLockHelper.release()
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
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
                        vlogManager.logError("Render process gone (Chromium renderer sập/thiếu RAM). Cấp phát lại WebView...", null, account.email)
                        destroyHeadlessWebView()
                        wakeLockHelper.release()
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
                            if (!isCompleted) {
                                isCompleted = true
                                mainHandler.removeCallbacks(timeoutRunnable)
                                vlogManager.logCaptcha("Phát hiện thử thách xác minh/khóa nick: $currentUrl", account = account.email)
                                vlogManager.logError("Dừng và tự động tắt tài khoản do gặp trang thử thách bảo mật", currentUrl, account.email, view)

                                // Tự động tắt tài khoản trong DB để tránh lặp và chống ban lan truyền
                                account.isEnabled = false
                                serviceScope.launch(Dispatchers.IO) {
                                    database.rewardsDao().updateProfile(account)
                                }

                                profileManager.clearActiveSessionAsync(view)
                                activeAccountId = null
                                wakeLockHelper.release()
                                if (continuation.isActive) continuation.resume(false)
                            }
                            return
                        }

                        if (currentUrl.contains("bing.com/search") && !isCompleted) {
                            isCompleted = true
                            mainHandler.removeCallbacks(timeoutRunnable)

                            view?.evaluateJavascript("(function(){ return !!(document.querySelector('#challenge-stage') || document.querySelector('iframe[src*=\"arkoselabs\"]')); })();") { hasChallenge ->
                                if (hasChallenge == "true") {
                                    vlogManager.logCaptcha("Phát hiện Arkose Labs / Cloudflare Challenge trong DOM", account = account.email)
                                    vlogManager.logError("Dừng tài khoản do phát hiện Challenge trong DOM", null, account.email, view)
                                    wakeLockHelper.release()
                                    if (continuation.isActive) continuation.resume(false)
                                    return@evaluateJavascript
                                }

                                val dwellTimeMs = kotlin.random.Random.nextLong(8000L, 12001L)
                                vlogManager.logDom("Tải xong kết quả tìm kiếm. Giữ trang (${dwellTimeMs / 1000}s) và cuộn trang mượt mà (Smooth scroll)", account = account.email)
                                val scrollScript = AutomationEngine.createSmoothScrollScript()

                                view.evaluateJavascript(scrollScript) {
                                    mainHandler.postDelayed({
                                        // Lưu cookie mới nhất
                                        val savedCookies = profileManager.dumpCookiesForProfile(account.id)
                                        if (savedCookies.isNotBlank()) {
                                            account.cookiesSnapshot = savedCookies
                                            serviceScope.launch(Dispatchers.IO) {
                                                database.rewardsDao().updateProfile(account)
                                            }
                                        }

                                        // Trích xuất điểm inline và update vào account trong DB + vlogManager.logPoints
                                        RewardsBalanceExtractor.extractFromWebView(view) { balance ->
                                            if (balance.isSuccess && balance.availablePoints > 0) {
                                                account.availablePoints = balance.availablePoints
                                                if (balance.lifetimePoints > 0) {
                                                    account.lifetimePoints = balance.lifetimePoints
                                                }
                                                if (balance.streakCount > 0) {
                                                    account.streakCount = balance.streakCount
                                                }
                                                val serverEarned = balance.pointsEarnedToday
                                                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                                if (account.baselinePointsToday <= 0 || account.lastActiveDate != todayStr) {
                                                    account.baselinePointsToday = balance.availablePoints
                                                    account.lastActiveDate = todayStr
                                                }
                                                account.pointsEarnedToday = maxOf(serverEarned, maxOf(0, account.availablePoints - account.baselinePointsToday))
                                                account.totalPoints = balance.availablePoints

                                                // Cập nhật rổ điểm vào AccountProfile
                                                account.pcProgress = balance.pcProgress
                                                account.pcMax = balance.pcMax
                                                account.mobileProgress = balance.mobileProgress
                                                account.mobileMax = balance.mobileMax
                                                account.edgeProgress = balance.edgeProgress
                                                account.edgeMax = balance.edgeMax

                                                // Tự động targetSearches Level 1=15, Level 2=55
                                                if (balance.mobileMax > 0 || balance.pcMax > 30) {
                                                    account.level = 2
                                                } else if (balance.pcMax in 1..30 && balance.mobileMax == 0) {
                                                    account.level = 1
                                                }
                                                account.targetSearches = if (account.level == 1) 35 else 55

                                                serviceScope.launch(Dispatchers.IO) {
                                                    database.rewardsDao().updateProfile(account)
                                                }

                                                vlogManager.logPoints(
                                                    "Cập nhật điểm inline: ${account.availablePoints} pts",
                                                    "Đã kiếm hôm nay: +${account.pointsEarnedToday} pts | PC: ${account.pcProgress}/${account.pcMax}, Mob: ${account.mobileProgress}/${account.mobileMax}, Edge: ${account.edgeProgress}/${account.edgeMax} [${balance.source}]",
                                                    account.email
                                                )
                                            } else {
                                                // Fallback: truy vấn Dashboard HTTP nếu DOM SERP chưa kịp render widget
                                                serviceScope.launch(Dispatchers.IO) {
                                                    val httpBal = RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot, preset.userAgent)
                                                    if (httpBal.isSuccess && httpBal.availablePoints > 0) {
                                                        account.availablePoints = httpBal.availablePoints
                                                        if (httpBal.lifetimePoints > 0) account.lifetimePoints = httpBal.lifetimePoints
                                                        if (httpBal.streakCount > 0) account.streakCount = httpBal.streakCount
                                                        val serverEarned = httpBal.pointsEarnedToday
                                                        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                                        if (account.baselinePointsToday <= 0 || account.lastActiveDate != todayStr) {
                                                            account.baselinePointsToday = httpBal.availablePoints
                                                            account.lastActiveDate = todayStr
                                                        }
                                                        account.pointsEarnedToday = maxOf(serverEarned, maxOf(0, account.availablePoints - account.baselinePointsToday))
                                                        account.totalPoints = httpBal.availablePoints

                                                        account.pcProgress = httpBal.pcProgress
                                                        account.pcMax = httpBal.pcMax
                                                        account.mobileProgress = httpBal.mobileProgress
                                                        account.mobileMax = httpBal.mobileMax
                                                        account.edgeProgress = httpBal.edgeProgress
                                                        account.edgeMax = httpBal.edgeMax

                                                        if (httpBal.mobileMax > 0 || httpBal.pcMax > 30) {
                                                            account.level = 2
                                                        } else if (httpBal.pcMax in 1..30 && httpBal.mobileMax == 0) {
                                                            account.level = 1
                                                        }
                                                        account.targetSearches = if (account.level == 1) 35 else 55

                                                        database.rewardsDao().updateProfile(account)
                                                        vlogManager.logPoints(
                                                            "Cập nhật điểm qua Dashboard HTTP: ${account.availablePoints} pts",
                                                            "Đã kiếm hôm nay: +${account.pointsEarnedToday} pts | PC: ${account.pcProgress}/${account.pcMax} [${httpBal.source}]",
                                                            account.email
                                                        )
                                                    }
                                                }
                                            }

                                                wakeLockHelper.release()
                                                vlogManager.logSearch("$phaseTag Lượt tìm kiếm hoàn tất thành công", "Keyword: \"$keyword\" (Dwell: ${dwellTimeMs / 1000}s)", account.email)
                                                if (continuation.isActive) {
                                                    continuation.resume(true)
                                                }
                                            }
                                    }, dwellTimeMs) // Dwell time 8-12s
                                }
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && !isCompleted) {
                            isCompleted = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            vlogManager.logError("Lỗi tải trang chính WebView", "Error code: ${error?.errorCode}", account.email, view)
                            wakeLockHelper.release()
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }
                }

                val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
                val searchUrl = when (phase) {
                    RewardsStateMachine.SearchPhase.PHASE_1_PC -> "https://www.bing.com/search?q=$encodedKeyword&form=QBLH"
                    RewardsStateMachine.SearchPhase.PHASE_2_MOBILE -> "https://www.bing.com/search?q=$encodedKeyword&form=STARTR&PC=EMMX01"
                    RewardsStateMachine.SearchPhase.PHASE_3_EDGE -> "https://www.bing.com/search?q=$encodedKeyword&form=MA13FW&pc=EUPH"
                    else -> "https://www.bing.com/search?q=$encodedKeyword&form=QBLH"
                }
                vlogManager.logNetwork("$phaseTag Mở kết nối tới $searchUrl", "Proxy: ${account.proxyUrl ?: "Direct"}", account.email)
                val extraHeaders = mapOf("Referer" to "https://www.bing.com/")
                webView.loadUrl(searchUrl, extraHeaders)
                mainHandler.postDelayed(timeoutRunnable, 45000L)
            }
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

    override fun onDestroy() {
        super.onDestroy()
        runningAccountIdFlow.value = null
        serviceScope.cancel()
        destroyHeadlessWebView()
        wakeLockHelper.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.agy.rewardsnode.START"
        const val ACTION_STOP = "com.agy.rewardsnode.STOP"
        val runningAccountIdFlow = MutableStateFlow<String?>(null)
    }
}
