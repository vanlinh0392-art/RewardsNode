package com.agy.rewardsnode.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.*
import androidx.core.app.NotificationCompat
import com.agy.rewardsnode.App
import com.agy.rewardsnode.core.AutomationEngine
import com.agy.rewardsnode.core.KeywordEngine
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsStateMachine
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.ui.MainActivity
import com.agy.rewardsnode.utils.TelegramNotifier
import com.agy.rewardsnode.utils.WakeLockHelper
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * Android Foreground Service vận hành 24/7 cho Rewards Node.
 * Sử dụng WebView chạy ngầm để thực thi tìm kiếm Bing, tương tác DOM và bảo lưu Cookie.
 */
class RewardsForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: EncryptedPreferences
    private lateinit var database: AppDatabase
    private lateinit var profileManager: ProfileManager
    private lateinit var keywordEngine: KeywordEngine
    private lateinit var stateMachine: RewardsStateMachine
    private lateinit var wakeLockHelper: WakeLockHelper

    private var headlessWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = EncryptedPreferences(this)
        database = AppDatabase.getDatabase(this)
        profileManager = ProfileManager(this)
        keywordEngine = KeywordEngine()
        wakeLockHelper = WakeLockHelper(this)

        stateMachine = RewardsStateMachine(
            keywordEngine = keywordEngine,
            profileManager = profileManager,
            onExecuteSearch = { account, keyword, isDesktop ->
                executeSearchOnWebView(account, keyword, isDesktop)
            },
            onStatusUpdate = { status, details ->
                updateNotification(status, details)
            },
            onAccountUpdated = { updatedAccount ->
                database.rewardsDao().updateProfile(updatedAccount)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNode()
            ACTION_STOP -> stopNode()
            null -> {
                // Tự động khôi phục phiên chạy khi hệ điều hành hồi sinh service qua START_STICKY
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
            val accounts = database.rewardsDao().getAllActiveProfiles()
            if (accounts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    updateNotification("Chưa có tài khoản nào", "Vui lòng mở ứng dụng để thêm tài khoản.")
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                initHeadlessWebView()
                stateMachine.start(
                    accounts = accounts,
                    batchSize = prefs.batchSize,
                    cooldownMinutes = prefs.cooldownMinutes,
                    coroutineScope = serviceScope
                )
            }

            // Gửi thông báo bắt đầu qua Telegram nếu có cấu hình
            if (prefs.telegramBotToken.isNotBlank() && prefs.telegramChatId.isNotBlank()) {
                val notifier = TelegramNotifier(prefs.telegramBotToken, prefs.telegramChatId)
                notifier.sendMessage("🚀 *Rewards Node Đã Khởi Động*\nĐang chạy ${accounts.size} tài khoản ngầm trên Android.")
            }
        }
    }

    private fun stopNode() {
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
        }
    }

    private fun destroyHeadlessWebView() {
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
    private suspend fun executeSearchOnWebView(account: AccountProfile, keyword: String, isDesktop: Boolean): Boolean =
        withContext(Dispatchers.Main) {
            wakeLockHelper.acquire("Search_${account.id}")
            initHeadlessWebView()
            val webView = headlessWebView ?: return@withContext false

            // Áp dụng Proxy nếu tài khoản có cấu hình
            profileManager.applyProxy(account.proxyUrl) { runnable -> runnable.run() }

            // Đặt User-Agent phù hợp
            val ua = if (isDesktop) stateMachine.DESKTOP_USER_AGENT else stateMachine.MOBILE_USER_AGENT
            webView.settings.userAgentString = ua

            return@withContext suspendCancellableCoroutine { continuation ->
                var isCompleted = false

                val timeoutRunnable = Runnable {
                    if (!isCompleted && continuation.isActive) {
                        isCompleted = true
                        wakeLockHelper.release()
                        continuation.resume(false)
                    }
                }

                continuation.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    wakeLockHelper.release()
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url?.contains("bing.com") == true && !isCompleted) {
                            isCompleted = true
                            mainHandler.removeCallbacks(timeoutRunnable)

                            // Tiêm kịch bản gõ phím và cuộn trang
                            val typeScript = AutomationEngine.createHumanTypingScript(keyword)
                            val scrollScript = AutomationEngine.createSmoothScrollScript()

                            view?.evaluateJavascript(typeScript) {
                                mainHandler.postDelayed({
                                    view.evaluateJavascript(scrollScript) {
                                        mainHandler.postDelayed({
                                            // Dọn dẹp cache tạm thời, bảo vệ cookie
                                            profileManager.cleanTransientDataPreservingAuth(view, account.id)
                                            wakeLockHelper.release()
                                            if (continuation.isActive) {
                                                continuation.resume(true)
                                            }
                                        }, 4000L) // Giữ trang 4s để Bing ghi nhận tương tác
                                    }
                                }, 3000L)
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && !isCompleted) {
                            isCompleted = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            wakeLockHelper.release()
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }
                }

                // Tải trang chủ Bing để chuẩn bị gõ từ khóa
                webView.loadUrl("https://www.bing.com/")

                // Timeout an toàn sau 45s phòng trường hợp mạng mất kết nối
                mainHandler.postDelayed(timeoutRunnable, 45000L)
            }
        }

    private fun updateNotification(status: String, details: String) {
        val notification = buildNotification(status, details)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
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

        return NotificationCompat.Builder(this, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Rewards Node: $status")
            .setContentText(details)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Dừng Node", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        destroyHeadlessWebView()
        wakeLockHelper.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.agy.rewardsnode.START"
        const val ACTION_STOP = "com.agy.rewardsnode.STOP"
    }
}
