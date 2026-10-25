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
import com.agy.rewardsnode.core.FingerprintEngine
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.core.VlogManager
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
        ProfileManager.isInteractiveLoginActive = true
        ProfileManager.interactiveLoginProfileId = profileId
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
        // 1. Triệt tiêu cờ X-Requested-With để không làm lộ package name com.agy.rewardsnode lên login.live.com
        FingerprintEngine.stripXRequestedWith(webView)

        // 2. Sử dụng preset chuẩn Mobile Edge 133 Android (EdgA) khớp với phần cứng Xiaomi 14 Android 14
        val loginPreset = FingerprintEngine.getMobileLoginPreset()

        // Kích hoạt chấp nhận cookie của bên thứ ba trong quá trình chuyển tiếp SSO giữa live.com và bing.com
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = loginPreset.userAgent
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pbLoading.visibility = View.VISIBLE
                Log.d("LoginDialog", "Bắt đầu tải URL: $url")

                // Tiêm stealth script ngay khi bắt đầu tải trang: loại bỏ hoàn toàn "Android WebView" trong Client Hints
                // và cloaking [native code] để vượt qua bộ kiểm tra an toàn trình duyệt của Microsoft
                val stealthScript = FingerprintEngine.createStealthScript(loginPreset, isDesktop = false)
                view?.evaluateJavascript(stealthScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isAutoSyncing) {
                    pbLoading.visibility = View.GONE
                }
                val currentUrl = url ?: ""
                vlog.logNetwork("WebView tải xong trang", currentUrl)
                Log.i("LoginDialog", "Tải xong URL: $currentUrl")

                // Tái kích hoạt stealth script sau khi trang tải xong cho dynamic DOM / SPA navigations
                val stealthScript = FingerprintEngine.createStealthScript(loginPreset, isDesktop = false)
                view?.evaluateJavascript(stealthScript, null)

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

                // Cập nhật trạng thái nhận diện phiên hợp lệ đa tầng (chỉ bật nút Lưu khi đã rời about:blank và trang login.srf)
                val isStillAtLoginScreen = currentUrl.isBlank() ||
                    currentUrl.startsWith("about:blank") ||
                    currentUrl.contains("login.live.com/login.srf", ignoreCase = true)
                CookieManager.getInstance().flush()
                val hasBingSession = !isStillAtLoginScreen && profileManager.hasValidBingSession()
                if (hasBingSession) {
                    btnSave.isEnabled = true
                    btnSave.alpha = 1.0f
                    tvTitle.text = if (currentUrl.contains("rewards.bing.com")) "Đã đăng nhập Microsoft Rewards! Nhấn Lưu phiên" else "Đã nhận diện phiên Bing! Nhấn Lưu phiên"
                    vlog.logSystem("Đã nhận diện phiên hợp lệ! Nút Lưu phiên sẵn sàng", currentUrl)
                } else if (!isStillAtLoginScreen && (currentUrl.contains("bing.com") || currentUrl.contains("rewards"))) {
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

        // Dọn sạch session cũ một cách đồng bộ an toàn qua coroutine để tránh Session Bleeding.
        // Chỉ gọi loadUrl() sau khi xóa cookie (hoặc khôi phục cookie cũ nếu re-auth) hoàn tất 100%
        // để chống race condition cookie cũ bị gửi đi trong HTTP Request đầu tiên.
        scope.launch(AndroidMainDispatcher) {
            ProfileManager.isInteractiveLoginActive = true
            ProfileManager.interactiveLoginProfileId = profileId
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

            // Luôn kèm wreply trỏ về rewards.bing.com để kích hoạt giao thức RPS Handshake sinh cookie _U
            val baseStartUrl = "https://login.live.com/login.srf?wa=wsignin1.0&rpsnv=150&ct=1720000000&rver=7.5.2156.0&wp=MBI_SSL&wreply=https%3A%2F%2Frewards.bing.com%2F"
            val startUrl = if (existingAccountId == null) "$baseStartUrl&prompt=login" else baseStartUrl
            vlog.logNetwork("Nạp URL bắt đầu đăng nhập Microsoft", startUrl)
            webView.loadUrl(startUrl)
        }
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
            val finalCookies = ProfileManager.sanitizeCookieMap(profileManager.dumpCookiesForProfile(profileId))

            if (cachedEmail.isNullOrBlank() || (cachedBalance?.availablePoints ?: 0) <= 0) {
                val refreshedBalance = RewardsBalanceExtractor.extractPointsViaHttp(finalCookies)
                if (refreshedBalance.isSuccess) {
                    cachedBalance = refreshedBalance
                    if (!refreshedBalance.email.isNullOrBlank()) {
                        cachedEmail = refreshedBalance.email
                    }
                    if (!refreshedBalance.profileName.isNullOrBlank()) {
                        cachedName = refreshedBalance.profileName
                    }
                }
            }

            val existingById = if (existingAccountId != null) database.rewardsDao().getProfileById(existingAccountId) else null

            val bal = cachedBalance
            val points = bal?.availablePoints ?: 0
            val lifetime = bal?.lifetimePoints ?: points
            val streak = bal?.streakCount ?: 0
            val level = bal?.level ?: 1

            val emailCandidate = cachedEmail
                ?: existingById?.email?.takeIf { it.contains("@") && !it.contains("account_") }
                ?: "account_${profileId.take(6)}@outlook.com"

            val existing = existingById ?: if (existingAccountId == null && emailCandidate.contains("@") && !emailCandidate.startsWith("account_")) {
                database.rewardsDao().getAllActiveProfiles().firstOrNull {
                    it.email.equals(emailCandidate, ignoreCase = true)
                }
            } else {
                null
            }

            val nameCandidate = cachedName
                ?: existing?.profileName
                ?: (if (emailCandidate.contains("@")) "Profile ${emailCandidate.substringBefore("@")}" else "Profile ${profileId.take(6)}")

            val targetAccount = if (existing != null) {
                val mergedCookies = ProfileManager.mergeCookieSnapshots(existing.cookiesSnapshot, finalCookies)
                existing.copy(
                    cookiesSnapshot = mergedCookies,
                    email = if (emailCandidate.contains("@") && !emailCandidate.contains("account_")) emailCandidate else existing.email,
                    profileName = nameCandidate,
                    availablePoints = if (points > 0) points else existing.availablePoints,
                    totalPoints = if (points > 0) points else existing.totalPoints,
                    baselinePointsToday = if (points > 0) points else existing.baselinePointsToday,
                    lifetimePoints = if (lifetime > 0) lifetime else existing.lifetimePoints,
                    streakCount = if (streak > 0) streak else existing.streakCount,
                    level = level,
                    targetSearches = if (level == 1) 35 else 55,
                    dailySetDone = bal?.dailySetDone ?: existing.dailySetDone,
                    dailySetProgress = if (bal?.dailySetDone == true) (bal.dailySetTotal.takeIf { it > 0 } ?: 3) else (bal?.dailySetProgress ?: existing.dailySetProgress),
                    dailySetTotal = if ((bal?.dailySetTotal ?: 0) > 0) bal!!.dailySetTotal else existing.dailySetTotal,
                    claimablePoints = bal?.claimablePoints ?: existing.claimablePoints,
                    cookieExpired = false
                )
            } else {
                val isExtractionVerified = bal != null && bal.isSuccess && bal.availablePoints > 0 &&
                    (bal.email.isNullOrBlank() || emailCandidate.startsWith("account_") || bal.email.equals(emailCandidate, ignoreCase = true))

                if (!isExtractionVerified || (bal?.availablePoints ?: 0) <= 0) {
                    AccountProfile(
                        id = profileId,
                        email = emailCandidate,
                        profileName = nameCandidate,
                        cookiesSnapshot = finalCookies,
                        availablePoints = 0,
                        totalPoints = 0,
                        baselinePointsToday = 0,
                        pointsEarnedToday = 0,
                        lifetimePoints = 0,
                        streakCount = 0,
                        level = 1,
                        targetSearches = 35,
                        pcProgress = 0,
                        pcMax = 30,
                        mobileProgress = 0,
                        mobileMax = 0,
                        edgeProgress = 0,
                        edgeMax = 0,
                        readProgress = 0,
                        readMax = 30,
                        dailySetDone = false,
                        dailySetProgress = 0,
                        dailySetTotal = 3,
                        claimablePoints = 0,
                        cookieExpired = false
                    )
                } else {
                    val verifiedBal = bal!!
                    val safeLevel = if (verifiedBal.level in 1..2) verifiedBal.level else 1
                    AccountProfile(
                        id = profileId,
                        email = emailCandidate,
                        profileName = nameCandidate,
                        cookiesSnapshot = finalCookies,
                        availablePoints = verifiedBal.availablePoints,
                        totalPoints = verifiedBal.availablePoints,
                        baselinePointsToday = verifiedBal.availablePoints,
                        pointsEarnedToday = verifiedBal.pointsEarnedToday,
                        lifetimePoints = if (verifiedBal.lifetimePoints > 0) verifiedBal.lifetimePoints else verifiedBal.availablePoints,
                        streakCount = verifiedBal.streakCount,
                        level = safeLevel,
                        targetSearches = if (safeLevel == 1) 35 else 55,
                        pcProgress = verifiedBal.pcProgress,
                        pcMax = if (verifiedBal.pcMax > 0) verifiedBal.pcMax else (if (safeLevel == 1) 30 else 90),
                        mobileProgress = verifiedBal.mobileProgress,
                        mobileMax = if (verifiedBal.mobileMax > 0) verifiedBal.mobileMax else (if (safeLevel == 1) 0 else 60),
                        edgeProgress = verifiedBal.edgeProgress,
                        edgeMax = verifiedBal.edgeMax,
                        readProgress = verifiedBal.readNewsProgress,
                        readMax = if (verifiedBal.readNewsMax > 0) verifiedBal.readNewsMax else 30,
                        dailySetDone = verifiedBal.dailySetDone,
                        dailySetProgress = if (verifiedBal.dailySetDone) (verifiedBal.dailySetTotal.takeIf { it > 0 } ?: 3) else verifiedBal.dailySetProgress,
                        dailySetTotal = if (verifiedBal.dailySetTotal > 0) verifiedBal.dailySetTotal else 3,
                        claimablePoints = verifiedBal.claimablePoints,
                        cookieExpired = false
                    )
                }
            }

            // Chủ động thu hoạch sớm (Pre-fetch) DAPI Refresh/Access Token ngay khi phiên login.live.com còn mới 100%
            try {
                kotlinx.coroutines.withTimeoutOrNull(8000L) {
                    val dapiToken = com.agy.rewardsnode.core.DapiAuthManager.ensureValidToken(targetAccount, webView, null)
                    if (!dapiToken.isNullOrBlank()) {
                        vlog.logPoints("Đã thu hoạch sẵn DAPI OAuth2 Token (90 ngày) khi lưu phiên", "Email=${targetAccount.email}", targetAccount.email)
                    }
                }
            } catch (_: Throwable) {}

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
        ProfileManager.isInteractiveLoginActive = false
        ProfileManager.interactiveLoginProfileId = null
        watchdogJob?.cancel()
        watchdogJob = null
        isAutoSyncing = false
        isSaving = false
        vlog.logSystem("Đóng LoginDialog", "ProfileId=$profileId")
        scope.launch(AndroidMainDispatcher + kotlinx.coroutines.NonCancellable) {
            try {
                profileManager.clearActiveSession(webView)
            } finally {
                scope.cancel()
            }
        }
        webView.stopLoading()
        webView.loadUrl("about:blank")
        super.dismiss()
    }
}
