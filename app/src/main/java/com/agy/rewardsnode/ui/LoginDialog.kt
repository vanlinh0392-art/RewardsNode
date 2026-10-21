package com.agy.rewardsnode.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.agy.rewardsnode.R
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.core.VlogManager
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Dialog tương tác WebView toàn màn hình để đăng nhập tài khoản Microsoft,
 * giải quyết 2FA (Authenticator, SMS OTP) và Captcha dễ dàng.
 * Tự động chuyển hướng đồng bộ về bing.com, bóc tách điểm (>0 pts) & email,
 * lưu vào database, toast và dismiss() tự động với watchdog 12s chống treo.
 */
class LoginDialog(
    context: Context,
    private val existingAccountId: String? = null,
    private val onSessionSaved: () -> Unit
) : Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen) {

    private val profileManager = ProfileManager(context)
    private val database = AppDatabase.getDatabase(context)
    private val vlog = VlogManager.getInstance(context)
    private val scope = CoroutineScope(AndroidMainDispatcher)

    private lateinit var webView: WebView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var btnSave: Button
    private val profileId = existingAccountId ?: UUID.randomUUID().toString()

    private var isAutoSyncing = false
    private var isSaving = false
    private var watchdogJob: Job? = null
    private var cachedBalance: RewardsBalanceExtractor.ExtractedRewardsBalance? = null
    private var cachedEmail: String? = null
    private var cachedName: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_login_bottom_sheet)

        tvTitle = findViewById(R.id.tvSheetTitle)
        val btnReload = findViewById<ImageButton>(R.id.btnReloadWebView)
        val btnCancel = findViewById<Button>(R.id.btnCancelLogin)
        btnSave = findViewById(R.id.btnSaveLoginSession)
        pbLoading = findViewById(R.id.pbWebLoading)
        webView = findViewById(R.id.loginWebView)

        if (existingAccountId != null) {
            tvTitle.text = "Đăng nhập lại tài khoản"
        }

        vlog.logSystem("Khởi tạo LoginDialog WebView", "ProfileId=$profileId, isReAuth=${existingAccountId != null}")
        Log.i("LoginDialog", "Khởi tạo LoginDialog: ProfileId=$profileId, isReAuth=${existingAccountId != null}")

        btnReload.setOnClickListener {
            if (!isAutoSyncing) {
                vlog.logSystem("Người dùng tải lại trang WebView", webView.url)
                webView.reload()
            }
        }
        btnCancel.setOnClickListener {
            vlog.logSystem("Người dùng hủy đăng nhập", "ProfileId=$profileId")
            dismiss()
        }
        btnSave.setOnClickListener {
            saveSession()
        }

        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Kích hoạt chấp nhận cookie của bên thứ ba trong quá trình chuyển tiếp SSO giữa live.com và bing.com
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Dọn sạch session cũ một cách đồng bộ an toàn qua coroutine để tránh Session Bleeding
        scope.launch(AndroidMainDispatcher) {
            profileManager.clearActiveSession(webView)

            // Nạp lại cookie đã lưu nếu là phiên đăng nhập lại
            if (existingAccountId != null) {
                val acc = withContext(Dispatchers.IO) {
                    database.rewardsDao().getProfileById(existingAccountId)
                }
                if (acc != null) {
                    profileManager.restoreCookiesForProfile(existingAccountId, acc.cookiesSnapshot)
                    vlog.logSystem("Đã nạp lại cookie của profile cũ", "Email=${acc.email}")
                }
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 EdgA/128.0.0.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pbLoading.visibility = View.VISIBLE
                Log.d("LoginDialog", "Bắt đầu tải URL: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isAutoSyncing) {
                    pbLoading.visibility = View.GONE
                }
                val currentUrl = url ?: ""
                vlog.logNetwork("WebView tải xong trang", currentUrl)
                Log.i("LoginDialog", "Tải xong URL: $currentUrl")

                // Tự động điều hướng về rewards.bing.com nếu hoàn tất đăng nhập tại live.com mà chưa chuyển trang
                if ((currentUrl.contains("account.microsoft.com") || currentUrl.contains("login.live.com/ppsecure/post.srf")) &&
                    !profileManager.hasValidBingSession()) {
                    vlog.logNetwork("Tự động chuyển tiếp từ live.com sang rewards.bing.com", currentUrl)
                    webView.loadUrl("https://rewards.bing.com/")
                    return
                }

                // Nếu đang trong chế độ AutoSync và trang bing.com đã hoàn tất tải sau SSO Handshake
                if (isAutoSyncing && currentUrl.contains("bing.com") && !currentUrl.contains("signin") && !currentUrl.contains("oauth")) {
                    vlog.logNetwork("Đã tải xong bing.com sau SSO Handshake trong AutoSync, hoàn tất lưu phiên...", currentUrl)
                    view?.postDelayed({
                        completeSessionSave()
                    }, 1000L)
                    return
                }

                // Cập nhật trạng thái nhận diện phiên hợp lệ đa tầng
                CookieManager.getInstance().flush()
                val hasBingSession = profileManager.hasValidBingSession()
                if (hasBingSession) {
                    btnSave.isEnabled = true
                    btnSave.alpha = 1.0f
                    tvTitle.text = if (currentUrl.contains("rewards.bing.com")) "Đã đăng nhập Microsoft Rewards! Nhấn Lưu phiên" else "Đã nhận diện phiên Bing! Nhấn Lưu phiên"
                    vlog.logSystem("Đã nhận diện phiên hợp lệ! Nút Lưu phiên sẵn sàng", currentUrl)
                } else if (currentUrl.contains("bing.com") || currentUrl.contains("rewards")) {
                    view?.postDelayed({
                        CookieManager.getInstance().flush()
                        if (profileManager.hasValidBingSession()) {
                            btnSave.isEnabled = true
                            btnSave.alpha = 1.0f
                            tvTitle.text = "Đã nhận diện phiên Rewards! Nhấn Lưu phiên"
                            vlog.logSystem("Đã nhận diện phiên hợp lệ (sau delay)!", currentUrl)
                        }
                    }, 1200L)
                }
            }
        }

        // Luôn kèm wreply trỏ về rewards.bing.com để kích hoạt giao thức RPS Handshake sinh cookie _U
        val startUrl = "https://login.live.com/login.srf?wa=wsignin1.0&rpsnv=150&ct=1720000000&rver=7.5.2156.0&wp=MBI_SSL&wreply=https%3A%2F%2Frewards.bing.com%2F"
        vlog.logNetwork("Nạp URL bắt đầu đăng nhập Microsoft", startUrl)
        webView.loadUrl(startUrl)
    }

    private fun lockUiForSync() {
        btnSave.isEnabled = false
        btnSave.alpha = 0.5f
        pbLoading.visibility = View.VISIBLE
        tvTitle.text = "Đang đồng bộ phiên Bing & số dư điểm..."
    }

    private fun unlockUiAfterSync() {
        btnSave.isEnabled = true
        btnSave.alpha = 1.0f
        pbLoading.visibility = View.GONE
        tvTitle.text = if (existingAccountId != null) "Đăng nhập lại tài khoản" else "Đăng nhập Microsoft"
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(AndroidMainDispatcher) {
            delay(12000L)
            if ((isAutoSyncing || isSaving) && isShowing) {
                vlog.logError("Quá thời gian đồng bộ phiên (12s Watchdog)", "isAutoSyncing=$isAutoSyncing, isSaving=$isSaving", null, webView)
                isAutoSyncing = false
                isSaving = false
                unlockUiAfterSync()
                Toast.makeText(context, "Quá thời gian đồng bộ (12s). Vui lòng thử lại hoặc hoàn thành xác thực.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveSession() {
        if (isSaving) return
        isSaving = true
        lockUiForSync()
        startWatchdog()

        CookieManager.getInstance().flush()
        val currentUrl = webView.url ?: ""
        vlog.logSystem("Bắt đầu quy trình lưu phiên", "URL=$currentUrl")

        scope.launch(Dispatchers.IO) {
            val dumpedCookies = profileManager.dumpCookiesForProfile(profileId)

            // 1. Trích xuất điểm & thông tin người dùng từ rewards.bing.com/dashboard
            var balance = RewardsBalanceExtractor.extractPointsViaHttp(dumpedCookies)
            if (!balance.isSuccess || balance.availablePoints <= 0) {
                balance = withContext(AndroidMainDispatcher) {
                    RewardsBalanceExtractor.extractPoints(webView)
                }
            }

            cachedBalance = balance
            if (!balance.email.isNullOrBlank()) {
                cachedEmail = balance.email
            }
            if (!balance.profileName.isNullOrBlank()) {
                cachedName = balance.profileName
            }

            vlog.logPoints(
                "Đã trích xuất thông tin tài khoản",
                "Điểm=${balance.availablePoints}, Email=${cachedEmail ?: "N/A"}, Tên=${cachedName ?: "N/A"}",
                cachedEmail
            )

            // 2. Kiểm tra xem đã có token _RwBf trên Bing chưa
            val hasRwBf = profileManager.hasRwBfToken(dumpedCookies)
            val isAtBing = currentUrl.contains("www.bing.com")

            if (!hasRwBf || !isAtBing) {
                // Điều hướng sang Bing SSO Handshake để kích hoạt OAuth2 sinh token _RwBf & KievRPSToken
                withContext(AndroidMainDispatcher) {
                    vlog.logNetwork("Điều hướng WebView sang Bing SSO Handshake để nhận diện Rewards User", ProfileManager.BING_SSO_SIGNIN_URL)
                    isAutoSyncing = true
                    tvTitle.text = "Đang kết nối Bing SSO & đồng bộ..."
                    webView.loadUrl(ProfileManager.BING_SSO_SIGNIN_URL)
                }
            } else {
                // Đã có đầy đủ _RwBf và ở bing.com, hoàn tất lưu ngay
                completeSessionSave()
            }
        }
    }

    private fun completeSessionSave() {
        scope.launch(Dispatchers.IO) {
            withContext(AndroidMainDispatcher) {
                CookieManager.getInstance().flush()
            }
            val finalCookies = profileManager.dumpCookiesForProfile(profileId)

            val existing = if (existingAccountId != null) database.rewardsDao().getProfileById(existingAccountId) else null

            val bal = cachedBalance
            val points = bal?.availablePoints ?: 0
            val lifetime = bal?.lifetimePoints ?: points
            val streak = bal?.streakCount ?: 0
            val level = bal?.level ?: 1

            val emailCandidate = cachedEmail
                ?: existing?.email?.takeIf { it.contains("@") && !it.contains("account_") }
                ?: "account_${profileId.take(6)}@outlook.com"

            val nameCandidate = cachedName
                ?: existing?.profileName
                ?: (if (emailCandidate.contains("@")) "Profile ${emailCandidate.substringBefore("@")}" else "Profile ${profileId.take(6)}")

            val targetAccount = if (existing != null) {
                existing.copy(
                    cookiesSnapshot = finalCookies,
                    email = if (emailCandidate.contains("@") && !emailCandidate.contains("account_")) emailCandidate else existing.email,
                    profileName = nameCandidate,
                    availablePoints = if (points > 0) points else existing.availablePoints,
                    totalPoints = if (points > 0) points else existing.totalPoints,
                    baselinePointsToday = if (points > 0) points else existing.baselinePointsToday,
                    lifetimePoints = if (lifetime > 0) lifetime else existing.lifetimePoints,
                    streakCount = if (streak > 0) streak else existing.streakCount,
                    level = level,
                    targetSearches = if (level == 1) 35 else 55
                )
            } else {
                AccountProfile(
                    id = profileId,
                    email = emailCandidate,
                    profileName = nameCandidate,
                    cookiesSnapshot = finalCookies,
                    availablePoints = points,
                    totalPoints = points,
                    baselinePointsToday = points,
                    lifetimePoints = lifetime,
                    streakCount = streak,
                    level = level,
                    targetSearches = if (level == 1) 35 else 55
                )
            }

            vlog.logSystem("Đang lưu tài khoản vào SQLite Database...", "ID=${targetAccount.id}, Email=${targetAccount.email}, Points=${targetAccount.availablePoints}, Name=${targetAccount.profileName}")
            database.rewardsDao().insertOrUpdateProfile(targetAccount)
            vlog.logSystem("Đã lưu tài khoản vào SQLite Database thành công!", "ID=${targetAccount.id}, Email=${targetAccount.email}")
            Log.i("LoginDialog", "Đã lưu tài khoản vào DB: ${targetAccount.email}, points=${targetAccount.availablePoints}, name=${targetAccount.profileName}")

            withContext(AndroidMainDispatcher) {
                watchdogJob?.cancel()
                watchdogJob = null
                isAutoSyncing = false
                isSaving = false
                val ptsMsg = if (targetAccount.availablePoints > 0) " (${String.format("%,d", targetAccount.availablePoints)} pts)" else ""
                Toast.makeText(context, "Đã lưu phiên thành công: ${targetAccount.profileName}$ptsMsg", Toast.LENGTH_SHORT).show()
                onSessionSaved()
                dismiss()
            }
        }
    }

    override fun dismiss() {
        watchdogJob?.cancel()
        watchdogJob = null
        isAutoSyncing = false
        isSaving = false
        vlog.logSystem("Đóng LoginDialog", "ProfileId=$profileId")
        scope.launch(AndroidMainDispatcher) {
            profileManager.clearActiveSession(webView)
        }
        webView.stopLoading()
        webView.loadUrl("about:blank")
        super.dismiss()
    }
}
