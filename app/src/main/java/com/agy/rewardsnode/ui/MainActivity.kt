package com.agy.rewardsnode.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.*
import com.agy.rewardsnode.R
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.core.VlogManager
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.data.model.VlogEntry
import com.agy.rewardsnode.service.RewardsForegroundService
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import com.agy.rewardsnode.utils.TelegramNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình trung tâm Rewards Node được tối ưu chuyên biệt cho Xiaomi Mi 14 (6.36 inch, 120Hz LTPO, HyperOS).
 * Kiến trúc 4 Tabs Native (Dashboard, Accounts, Vlog Terminal, Settings) với tốc độ chuyển tab < 8.3ms (1 frame).
 */
class MainActivity : Activity() {

    private lateinit var database: AppDatabase
    private lateinit var prefs: EncryptedPreferences
    private lateinit var vlogManager: VlogManager
    private val scope = CoroutineScope(AndroidMainDispatcher + SupervisorJob())

    // Bottom Navigation Bar & Container
    private lateinit var rootMain: View
    private lateinit var tabContainer: FrameLayout
    private lateinit var bottomBar: LinearLayout

    private lateinit var btnTabDashboard: LinearLayout
    private lateinit var ivTabDashboard: ImageView
    private lateinit var tvTabDashboard: TextView

    private lateinit var btnTabAccounts: LinearLayout
    private lateinit var ivTabAccounts: ImageView
    private lateinit var tvTabAccounts: TextView

    private lateinit var btnTabVlog: LinearLayout
    private lateinit var ivTabVlog: ImageView
    private lateinit var tvTabVlog: TextView

    private lateinit var btnTabSettings: LinearLayout
    private lateinit var ivTabSettings: ImageView
    private lateinit var tvTabSettings: TextView

    // Tab Views
    private lateinit var viewDashboard: View
    private lateinit var viewAccounts: View
    private lateinit var viewVlog: View
    private lateinit var viewSettings: View

    private var currentTab = 0

    // Tab 1: Dashboard Views
    private var activityRingView: ActivityRingView? = null
    private var tvRingTotalPoints: TextView? = null
    private var tvRingPointsLabel: TextView? = null
    private var tvPcBasket: TextView? = null
    private var tvEdgeBasket: TextView? = null
    private var tvMobileBasket: TextView? = null
    private var tvProfileEmail: TextView? = null
    private var tvProfileName: TextView? = null
    private var tvProfileBadge: TextView? = null
    private var tvAvatarInitials: TextView? = null
    private var tvStreakBadge: TextView? = null
    private var tvLevelBadge: TextView? = null
    private var btnQuickCooldown: Button? = null
    private var btnQuickProxy: Button? = null
    private var tvTelemetryDailySet: TextView? = null
    private var tvTelemetryClaim: TextView? = null

    private lateinit var tvNodeStatus: TextView
    private lateinit var tvNodeDetails: TextView
    private lateinit var chronometerUptime: Chronometer
    private lateinit var tvStatSearches: TextView
    private lateinit var tvStatPoints: TextView
    private lateinit var tvStatProxies: TextView
    private lateinit var tvStatProfiles: TextView
    private lateinit var btnToggleNode: Button
    private var isUptimeRunning = false

    // Tab 2: Accounts Views
    private lateinit var swipeRefreshAccounts: SwipeRefreshLayout
    private lateinit var lvAccounts: ListView
    private lateinit var llEmptyAccounts: LinearLayout
    private lateinit var fabAddAccount: View
    private lateinit var accountAdapter: AccountAdapter

    // Tab 3: Vlog Terminal Views
    private lateinit var lvVlogTerminal: ListView
    private lateinit var vlogAdapter: VlogTerminalAdapter
    private lateinit var etLogSearch: EditText
    private lateinit var btnExportVlog: ImageButton
    private lateinit var btnClearVlog: ImageButton
    private lateinit var fabScrollBottom: FrameLayout
    private lateinit var tvNewLogsBadge: TextView
    private lateinit var btnFilterAll: Button
    private lateinit var btnFilterErr: Button
    private lateinit var btnFilterDom: Button
    private lateinit var btnFilterSrch: Button
    private lateinit var btnFilterNet: Button
    private lateinit var btnFilterCool: Button

    private var currentVlogTagFilter: String? = null
    private var currentSearchQuery: String = ""
    private var allVlogsCache: List<VlogEntry> = emptyList()
    private var isAutoScrollLocked = false
    private var unreadCountSinceScroll = 0
    private var searchDebounceJob: Job? = null

    // Tab 4: Settings Views
    private lateinit var etCooldownMin: EditText
    private lateinit var etCooldownMax: EditText
    private lateinit var etDefaultTargetSearches: EditText
    private lateinit var swApplyTargetToAll: Switch
    private lateinit var etGlobalProxy: EditText
    private lateinit var etUserAgent: EditText
    private lateinit var etTelegramBotToken: EditText
    private lateinit var etTelegramChatId: EditText
    private lateinit var btnTestTelegram: Button
    private lateinit var swDarkMode: Switch
    private lateinit var swWifiOnly: Switch
    private lateinit var swBatteryGuard: Switch
    private lateinit var btnBatteryOptimization: Button
    private lateinit var btnSaveSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(this)
        prefs = EncryptedPreferences(this)
        vlogManager = VlogManager.getInstance(this)

        initViews()
        setupBottomBar()
        setupWindowInsets()
        setupDashboardTab()
        setupAccountsTab()
        setupVlogTab()
        setupSettingsTab()

        checkPermissions()
        selectTab(0, withHaptic = false)
        updateServiceUIState()
    }

    override fun onResume() {
        super.onResume()
        updateServiceUIState()
        syncActiveProfilesBalance()
    }

    private fun syncActiveProfilesBalance(isUserTriggered: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                if (isUserTriggered) {
                    withContext(AndroidMainDispatcher) {
                        if (::swipeRefreshAccounts.isInitialized) {
                            swipeRefreshAccounts.isRefreshing = true
                        }
                    }
                }
                val profiles = database.rewardsDao().getAllActiveProfiles()
                var updatedCount = 0
                for (profile in profiles) {
                    val cookies = profile.cookiesSnapshot
                    if (!cookies.isNullOrBlank()) {
                        val result = RewardsBalanceExtractor.extractPointsViaHttp(cookies, profile.customUserAgent ?: prefs.userAgent, profile.proxyUrl)
                        if (result.isSuccess) {
                            RewardsBalanceExtractor.latestTelemetryMap[profile.id] = result
                            if (profile.email.isNotBlank()) {
                                RewardsBalanceExtractor.latestTelemetryMap[profile.email] = result
                            }
                            val avail = if (result.availablePoints > 0) result.availablePoints else profile.availablePoints
                            val emailToSet = if (!result.email.isNullOrBlank() && (profile.email.startsWith("account_") || !profile.email.contains("@"))) {
                                result.email
                            } else {
                                profile.email
                            }
                            val nameToSet = if (!result.profileName.isNullOrBlank() && (profile.profileName.startsWith("Profile ") || profile.profileName.isBlank())) {
                                result.profileName
                            } else {
                                profile.profileName
                            }
                            val levelToSet = if (result.level in 1..2) result.level else profile.level

                            val pcProg = if (result.pcMax > 0 || result.pcProgress > 0) result.pcProgress else profile.pcProgress
                            val pcM = if (result.pcMax > 0) result.pcMax else (if (levelToSet == 1) 30 else 90)
                            val mobProg = if (result.mobileMax > 0 || result.mobileProgress > 0) result.mobileProgress else profile.mobileProgress
                            val mobM = if (result.mobileMax > 0) result.mobileMax else (if (levelToSet == 1) 0 else 60)
                            val edgeProg = if (result.edgeMax > 0 || result.edgeProgress > 0) result.edgeProgress else profile.edgeProgress
                            val edgeM = if (result.edgeMax > 0) result.edgeMax else 12

                            val pointsEarned = when {
                                result.pointsEarnedToday > 0 -> result.pointsEarnedToday
                                (pcProg + mobProg + edgeProg) > 0 -> pcProg + mobProg + edgeProg
                                profile.pointsEarnedToday > 0 -> profile.pointsEarnedToday
                                profile.baselinePointsToday > 0 && avail > profile.baselinePointsToday -> avail - profile.baselinePointsToday
                                else -> 0
                            }

                            val updated = profile.copy(
                                email = emailToSet,
                                profileName = nameToSet,
                                level = levelToSet,
                                targetSearches = if (profile.targetSearches == 55 && levelToSet == 1) 15 else profile.targetSearches,
                                availablePoints = avail,
                                totalPoints = avail,
                                lifetimePoints = if (result.lifetimePoints > 0) result.lifetimePoints else profile.lifetimePoints,
                                streakCount = if (result.streakCount > 0) result.streakCount else profile.streakCount,
                                baselinePointsToday = if (profile.baselinePointsToday == 0) avail else profile.baselinePointsToday,
                                pointsEarnedToday = pointsEarned,
                                pcProgress = pcProg,
                                pcMax = pcM,
                                mobileProgress = mobProg,
                                mobileMax = mobM,
                                edgeProgress = edgeProg,
                                edgeMax = edgeM
                            )
                            database.rewardsDao().updateProfile(updated)
                            updatedCount++
                        }
                    }
                }
                withContext(AndroidMainDispatcher) {
                    if (isUserTriggered && updatedCount > 0) {
                        Toast.makeText(this@MainActivity, "Đã đồng bộ số dư $updatedCount tài khoản", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                // Tự phục hồi an toàn khi gặp sự cố mạng tạm thời
            } finally {
                withContext(AndroidMainDispatcher) {
                    if (::swipeRefreshAccounts.isInitialized) {
                        swipeRefreshAccounts.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun initViews() {
        rootMain = findViewById(R.id.rootMain)
        tabContainer = findViewById(R.id.tabContainer)
        bottomBar = findViewById(R.id.bottomBar)

        btnTabDashboard = findViewById(R.id.btnTabDashboard)
        ivTabDashboard = findViewById(R.id.ivTabDashboard)
        tvTabDashboard = findViewById(R.id.tvTabDashboard)

        btnTabAccounts = findViewById(R.id.btnTabAccounts)
        ivTabAccounts = findViewById(R.id.ivTabAccounts)
        tvTabAccounts = findViewById(R.id.tvTabAccounts)

        btnTabVlog = findViewById(R.id.btnTabVlog)
        ivTabVlog = findViewById(R.id.ivTabVlog)
        tvTabVlog = findViewById(R.id.tvTabVlog)

        btnTabSettings = findViewById(R.id.btnTabSettings)
        ivTabSettings = findViewById(R.id.ivTabSettings)
        tvTabSettings = findViewById(R.id.tvTabSettings)

        // Inflate 4 Tab Views (giữ nguyên instance để bảo toàn trạng thái)
        viewDashboard = layoutInflater.inflate(R.layout.layout_tab_dashboard, tabContainer, false)
        viewAccounts = layoutInflater.inflate(R.layout.layout_tab_accounts, tabContainer, false)
        viewVlog = layoutInflater.inflate(R.layout.layout_tab_vlog, tabContainer, false)
        viewSettings = layoutInflater.inflate(R.layout.layout_tab_settings, tabContainer, false)

        // Bật Software Layer trên tabContainer để triệt tiêu 100% hiện tượng rách/đè RenderNode GPU tile trên Snapdragon 8 Gen 3 HyperOS
        tabContainer.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    private fun setupBottomBar() {
        btnTabDashboard.setOnClickListener { selectTab(0) }
        btnTabAccounts.setOnClickListener { selectTab(1) }
        btnTabVlog.setOnClickListener { selectTab(2) }
        btnTabSettings.setOnClickListener { selectTab(3) }
    }

    private fun selectTab(index: Int, withHaptic: Boolean = true) {
        if (withHaptic) {
            val target = when (index) {
                0 -> btnTabDashboard
                1 -> btnTabAccounts
                2 -> btnTabVlog
                else -> btnTabSettings
            }
            target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        currentTab = index

        // TRIỆT TIÊU LỖI ĐÈ LAYER HWUI TRÊN XIAOMI HYPEROS:
        // Gỡ bỏ view cũ và chỉ mount DUY NHẤT view của Tab hiện tại vào tabContainer với full LayoutParams.
        val targetView = when (index) {
            0 -> viewDashboard
            1 -> viewAccounts
            2 -> viewVlog
            else -> viewSettings
        }

        if (tabContainer.childCount == 0 || tabContainer.getChildAt(0) != targetView) {
            tabContainer.removeAllViews()
            targetView.visibility = View.VISIBLE
            tabContainer.addView(targetView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            rootMain.postInvalidate()
        }

        // Nếu rời khỏi Tab Vlog (index != 2) thì ẩn ngay lập tức FAB và Badge
        if (index != 2) {
            fabScrollBottom.visibility = View.GONE
            tvNewLogsBadge.visibility = View.GONE
        }

        // Tự động sync số dư khi vào Tab 1 (Tài khoản)
        if (index == 1) {
            syncActiveProfilesBalance(isUserTriggered = false)
        }

        updateTabIconsAndText(index)
    }

    private fun updateTabIconsAndText(selectedIndex: Int) {
        val activeColor = Color.parseColor("#38BDF8")
        val inactiveColor = Color.parseColor("#64748B")

        val tabs = listOf(
            Pair(ivTabDashboard, tvTabDashboard),
            Pair(ivTabAccounts, tvTabAccounts),
            Pair(ivTabVlog, tvTabVlog),
            Pair(ivTabSettings, tvTabSettings)
        )

        tabs.forEachIndexed { i, (iv, tv) ->
            val isActive = (i == selectedIndex)
            val color = if (isActive) activeColor else inactiveColor
            iv.setColorFilter(color)
            tv.setTextColor(color)
            tv.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun setupWindowInsets() {
        // Tối ưu cho Xiaomi Mi 14 (HyperOS, Snapdragon 8 Gen 3)
        // Tắt fitsSystemWindows để triệt tiêu hoàn toàn khoảng đen thừa dưới đáy
        rootMain.fitsSystemWindows = false
        bottomBar.fitsSystemWindows = false
    }

    // ==========================================
    // TAB 1: DASHBOARD BINDING
    // ==========================================
    private fun setupDashboardTab() {
        tvNodeStatus = viewDashboard.findViewById(R.id.tvNodeStatus)
        tvNodeDetails = viewDashboard.findViewById(R.id.tvNodeDetails)
        chronometerUptime = viewDashboard.findViewById(R.id.chronometerUptime)
        tvStatSearches = viewDashboard.findViewById(R.id.tvStatSearches)
        tvStatPoints = viewDashboard.findViewById(R.id.tvStatPoints)
        tvStatProxies = viewDashboard.findViewById(R.id.tvStatProxies)
        tvStatProfiles = viewDashboard.findViewById(R.id.tvStatProfiles)
        btnToggleNode = viewDashboard.findViewById(R.id.btnToggleNode)

        // Ánh xạ ActivityRingView và các View của Ring & Baskets
        activityRingView = viewDashboard.findViewById(R.id.activityRingView) ?: viewDashboard.findViewById(R.id.ringDashboard)
        tvRingTotalPoints = viewDashboard.findViewById(R.id.tvRingTotalPoints)
        tvRingPointsLabel = viewDashboard.findViewById(R.id.tvRingPointsLabel)
        tvPcBasket = viewDashboard.findViewById(R.id.tvLegendPc) ?: viewDashboard.findViewById(R.id.tvPcBasket)
        tvEdgeBasket = viewDashboard.findViewById(R.id.tvLegendEdge) ?: viewDashboard.findViewById(R.id.tvEdgeBasket)
        tvMobileBasket = viewDashboard.findViewById(R.id.tvLegendMob) ?: viewDashboard.findViewById(R.id.tvMobileBasket)

        // Header Profile & Quick Action Buttons
        tvProfileEmail = viewDashboard.findViewById(R.id.tvProfileEmail)
        tvProfileName = viewDashboard.findViewById(R.id.tvProfileName)
        tvProfileBadge = viewDashboard.findViewById(R.id.tvProfileBadge)
        tvAvatarInitials = viewDashboard.findViewById(R.id.tvAvatarInitials)
        tvStreakBadge = viewDashboard.findViewById(R.id.tvStreakBadge)
        tvLevelBadge = viewDashboard.findViewById(R.id.tvLevelBadge)
        btnQuickCooldown = viewDashboard.findViewById(R.id.btnQuickCooldown)
        btnQuickProxy = viewDashboard.findViewById(R.id.btnQuickProxy)

        btnQuickCooldown?.setOnClickListener {
            selectTab(3) // Chuyển sang Tab Cài đặt để điều chỉnh Cooldown
        }

        btnQuickProxy?.setOnClickListener {
            selectTab(1) // Chuyển sang Tab Tài khoản để cấu hình Proxy
        }

        // Ánh xạ & khởi tạo hàng Daily Set & Claim trong thẻ Thông Số Vận Hành 24/7
        val tvTelemetryTitle = findTextViewWithText(viewDashboard, "THÔNG SỐ VẬN HÀNH 24/7")
        val telemetryCard = tvTelemetryTitle?.parent?.parent as? LinearLayout
        if (telemetryCard != null) {
            var rowDailyClaim = telemetryCard.findViewWithTag<LinearLayout>("row_telemetry_daily_claim")
            if (rowDailyClaim == null) {
                rowDailyClaim = LinearLayout(this).apply {
                    tag = "row_telemetry_daily_claim"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    orientation = LinearLayout.HORIZONTAL

                    // Cột trái: DAILY SET
                    val colDailySet = LinearLayout(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        orientation = LinearLayout.VERTICAL

                        val tvLabel = TextView(this@MainActivity).apply {
                            text = "DAILY SET"
                            setTextColor(Color.parseColor("#64748B"))
                            textSize = 9f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        val tvValue = TextView(this@MainActivity).apply {
                            text = "Sẵn sàng"
                            setTextColor(Color.parseColor("#38BDF8"))
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(0, (1 * resources.displayMetrics.density).toInt(), 0, 0)
                        }
                        tvTelemetryDailySet = tvValue
                        addView(tvLabel)
                        addView(tvValue)
                    }

                    // Cột phải: CLAIM
                    val colClaim = LinearLayout(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        orientation = LinearLayout.VERTICAL

                        val tvLabel = TextView(this@MainActivity).apply {
                            text = "CLAIM ĐIỂM"
                            setTextColor(Color.parseColor("#64748B"))
                            textSize = 9f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        val tvValue = TextView(this@MainActivity).apply {
                            text = "Đã nhận hết"
                            setTextColor(Color.parseColor("#10B981"))
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(0, (1 * resources.displayMetrics.density).toInt(), 0, 0)
                        }
                        tvTelemetryClaim = tvValue
                        addView(tvLabel)
                        addView(tvValue)
                    }

                    addView(colDailySet)
                    addView(colClaim)
                }
                telemetryCard.addView(rowDailyClaim)
            }
        }

        btnToggleNode.setOnClickListener {
            toggleNodeService()
        }

        // Quick Manual Sync Trigger trên Ring, Điểm số và Thẻ Profile Header
        val manualSyncAction: (View) -> Unit = { v ->
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            syncActiveProfilesBalance(isUserTriggered = true)
        }
        activityRingView?.setOnClickListener(manualSyncAction)
        tvRingTotalPoints?.setOnClickListener(manualSyncAction)
        viewDashboard.findViewById<View>(R.id.cardProfileInfo)?.setOnClickListener(manualSyncAction)
        (tvProfileName?.parent?.parent as? View)?.setOnClickListener(manualSyncAction)

        // Quan sát dữ liệu profile thời gian thực từ DB kết hợp trạng thái Service đang chạy
        scope.launch {
            combine(
                database.rewardsDao().getAllActiveProfilesFlow(),
                RewardsForegroundService.runningAccountIdFlow
            ) { accounts, runningId ->
                Pair(accounts, runningId)
            }.collectLatest { (accounts, runningId) ->
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val activeAccounts = accounts.filter { it.isEnabled }
                val totalSearches = accounts.sumOf { it.completedSearchesToday }
                val totalAvailablePoints = accounts.sumOf { it.availablePoints }
                val totalPointsEarnedToday = accounts.sumOf { account ->
                    when {
                        account.pointsEarnedToday > 0 -> account.pointsEarnedToday
                        (account.pcProgress + account.mobileProgress + account.edgeProgress) > 0 -> {
                            account.pcProgress + account.mobileProgress + account.edgeProgress
                        }
                        account.baselinePointsToday > 0 && account.availablePoints > account.baselinePointsToday -> {
                            account.availablePoints - account.baselinePointsToday
                        }
                        else -> 0
                    }
                }
                val proxyCount = accounts.count { !it.proxyUrl.isNullOrBlank() }

                tvStatSearches.text = totalSearches.toString()
                tvStatPoints.text = String.format("%,d", totalAvailablePoints)
                tvStatProxies.text = proxyCount.toString()
                tvStatProfiles.text = if (accounts.any { !it.isEnabled }) "${activeAccounts.size}/${accounts.size}" else accounts.size.toString()

                // Cập nhật tvRingTotalPoints = tổng điểm tích lũy của tất cả accounts
                tvRingTotalPoints?.text = String.format("%,d", totalAvailablePoints)

                // Ưu tiên chọn profile hiển thị
                val profile = if (!runningId.isNullOrBlank()) {
                    accounts.find { it.id == runningId } ?: activeAccounts.firstOrNull()
                } else {
                    activeAccounts.firstOrNull { !it.isAllMaxedOutToday(todayStr) }
                        ?: activeAccounts.firstOrNull()
                        ?: accounts.firstOrNull()
                }

                if (profile != null) {
                    val pcMax = if (profile.pcMax > 0) profile.pcMax else (if (profile.level == 1) 30 else 90)
                    val edgeMax = if (profile.edgeMax > 0) profile.edgeMax else 12
                    val mobMax = if (profile.mobileMax > 0) profile.mobileMax else (if (profile.level == 1) 0 else 60)

                    val pcRatio = if (pcMax > 0) (profile.pcProgress.toFloat() / pcMax).coerceIn(0f, 1f) else 0f
                    val edgeRatio = if (edgeMax > 0) (profile.edgeProgress.toFloat() / edgeMax).coerceIn(0f, 1f) else 0f
                    val mobileRatio = if (mobMax > 0) (profile.mobileProgress.toFloat() / mobMax).coerceIn(0f, 1f) else 0f

                    // Cập nhật ActivityRingView với animation mượt mà
                    activityRingView?.setProgress(pcRatio, edgeRatio, mobileRatio, animate = true)

                    // Cập nhật các nhãn rổ điểm (PC, Edge, Mobile)
                    tvPcBasket?.text = "PC ${profile.pcProgress}/$pcMax"
                    tvEdgeBasket?.text = "Edge ${profile.edgeProgress}/$edgeMax"
                    tvMobileBasket?.text = "Mob ${profile.mobileProgress}/$mobMax"

                    // Cập nhật Profile Info & Badges
                    val displayName = if (profile.profileName.isNotBlank() && !profile.profileName.startsWith("Profile ")) {
                        profile.profileName
                    } else if (profile.email.isNotBlank()) {
                        profile.email.substringBefore("@")
                    } else {
                        "Rewards Node"
                    }

                    val isRunningThisProfile = !runningId.isNullOrBlank() && profile.id == runningId
                    if (isRunningThisProfile) {
                        tvProfileName?.text = "$displayName [ĐANG CHẠY]"
                        tvProfileBadge?.visibility = View.VISIBLE
                        tvProfileBadge?.text = "[ĐANG CHẠY]"
                        tvProfileBadge?.setTextColor(Color.parseColor("#10B981"))
                        tvProfileBadge?.setBackgroundResource(R.drawable.bg_pill_dark)
                    } else {
                        tvProfileName?.text = displayName
                        tvProfileBadge?.visibility = View.VISIBLE
                        tvProfileBadge?.text = if (accounts.indexOf(profile) == 0) "CHÍNH" else "PHỤ"
                        tvProfileBadge?.setTextColor(Color.parseColor("#F59E0B"))
                        tvProfileBadge?.setBackgroundResource(R.drawable.bg_badge_gold)
                    }

                    tvAvatarInitials?.text = displayName.take(2).uppercase()
                    tvProfileEmail?.text = profile.email.ifBlank { "Tài khoản tự động 24/7" }
                    tvStreakBadge?.text = "🔥 ${profile.streakCount}"
                    tvLevelBadge?.text = "⭐ Lv.${profile.level}"
                } else {
                    activityRingView?.setProgress(0f, 0f, 0f, animate = false)
                    tvPcBasket?.text = "PC 0/30"
                    tvEdgeBasket?.text = "Edge 0/12"
                    tvMobileBasket?.text = "Mob 0/0"
                    tvProfileName?.text = "Chưa có tài khoản"
                    tvProfileBadge?.visibility = View.GONE
                    tvAvatarInitials?.text = "RN"
                    tvProfileEmail?.text = "Chưa có tài khoản đăng nhập"
                    tvStreakBadge?.text = "🔥 0"
                    tvLevelBadge?.text = "⭐ Lv.1"
                }

                // Cập nhật trạng thái Daily Set và Claim trong thẻ Thông Số Vận Hành 24/7 (nếu có dữ liệu)
                val telemetry = if (profile != null) {
                    RewardsBalanceExtractor.latestTelemetryMap[profile.id]
                        ?: RewardsBalanceExtractor.latestTelemetryMap[profile.email]
                } else null

                if (telemetry != null) {
                    if (telemetry.dailySetDone) {
                        tvTelemetryDailySet?.text = if (telemetry.streakProtectionOn) "Đã xong 3/3 • Bảo vệ" else "Hoàn tất (3/3)"
                        tvTelemetryDailySet?.setTextColor(Color.parseColor("#10B981"))
                    } else if (telemetry.dailySetProgress > 0) {
                        tvTelemetryDailySet?.text = "${telemetry.dailySetProgress}/3 hoạt động"
                        tvTelemetryDailySet?.setTextColor(Color.parseColor("#F59E0B"))
                    } else {
                        tvTelemetryDailySet?.text = if (telemetry.streakProtectionOn) "Chưa làm • Bảo vệ Bật" else "Chưa làm (0/3)"
                        tvTelemetryDailySet?.setTextColor(Color.parseColor("#94A3B8"))
                    }

                    if (telemetry.claimablePoints > 0) {
                        tvTelemetryClaim?.text = "+${telemetry.claimablePoints}đ Sẵn sàng"
                        tvTelemetryClaim?.setTextColor(Color.parseColor("#F59E0B"))
                    } else {
                        tvTelemetryClaim?.text = "Đã nhận hết"
                        tvTelemetryClaim?.setTextColor(Color.parseColor("#10B981"))
                    }
                } else {
                    tvTelemetryDailySet?.text = "Sẵn sàng"
                    tvTelemetryDailySet?.setTextColor(Color.parseColor("#38BDF8"))
                    tvTelemetryClaim?.text = "Đã nhận hết"
                    tvTelemetryClaim?.setTextColor(Color.parseColor("#10B981"))
                }

                if (accounts.isEmpty()) {
                    tvNodeDetails.text = "Chưa có tài khoản. Bấm dấu + để thêm tài khoản."
                } else if (prefs.isNodeServiceRunning) {
                    tvNodeDetails.text = "Đang chạy ${activeAccounts.size} profile | Hôm nay: $totalSearches lượt (+$totalPointsEarnedToday điểm)"
                } else {
                    tvNodeDetails.text = "Có ${activeAccounts.size}/${accounts.size} tài khoản sẵn sàng | Hôm nay: +$totalPointsEarnedToday điểm. Nhấn Bật Chạy Node để tích điểm."
                }

                updateServiceUIState()
            }
        }
    }

    private fun findTextViewWithText(parent: View, targetText: String): TextView? {
        if (parent is TextView && parent.text?.toString()?.contains(targetText, ignoreCase = true) == true) {
            return parent
        }
        if (parent is android.view.ViewGroup) {
            for (i in 0 until parent.childCount) {
                val res = findTextViewWithText(parent.getChildAt(i), targetText)
                if (res != null) return res
            }
        }
        return null
    }

    private fun toggleNodeService() {
        val isRunning = prefs.isNodeServiceRunning
        if (!isRunning) {
            val intent = Intent(this, RewardsForegroundService::class.java).apply {
                action = RewardsForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            prefs.isNodeServiceRunning = true
            isUptimeRunning = false
            Toast.makeText(this, "Đã khởi động Rewards Node ngầm", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(this, RewardsForegroundService::class.java).apply {
                action = RewardsForegroundService.ACTION_STOP
            }
            startService(intent)
            prefs.isNodeServiceRunning = false
            Toast.makeText(this, "Đã dừng Rewards Node", Toast.LENGTH_SHORT).show()
        }
        updateServiceUIState()
    }

    private fun updateServiceUIState() {
        if (prefs.isNodeServiceRunning) {
            tvNodeStatus.text = "Trạng thái: Đang chạy ngầm (24/7)"
            tvNodeStatus.setTextColor(Color.parseColor("#10B981"))
            btnToggleNode.text = getString(R.string.btn_stop_node)
            btnToggleNode.setBackgroundResource(R.drawable.bg_btn_primary)
            if (!isUptimeRunning) {
                chronometerUptime.base = SystemClock.elapsedRealtime()
                chronometerUptime.start()
                isUptimeRunning = true
            }
        } else {
            tvNodeStatus.text = getString(R.string.status_idle)
            tvNodeStatus.setTextColor(Color.parseColor("#38BDF8"))
            btnToggleNode.text = getString(R.string.btn_start_node)
            btnToggleNode.setBackgroundResource(R.drawable.bg_btn_toggle_node)
            if (isUptimeRunning) {
                chronometerUptime.stop()
                isUptimeRunning = false
            }
        }
    }

    // ==========================================
    // TAB 2: ACCOUNTS BINDING
    // ==========================================
    private fun setupAccountsTab() {
        swipeRefreshAccounts = viewAccounts.findViewById(R.id.swipeRefreshAccounts)
        lvAccounts = viewAccounts.findViewById(R.id.lvAccounts)
        llEmptyAccounts = viewAccounts.findViewById(R.id.llEmptyAccounts)
        fabAddAccount = viewAccounts.findViewById(R.id.fabAddAccount)

        swipeRefreshAccounts.setOnRefreshListener {
            syncActiveProfilesBalance(isUserTriggered = true)
        }

        accountAdapter = AccountAdapter(
            onReAuthClicked = { account -> LoginDialog(this, account.id) {}.show() },
            onEditProxyClicked = { account -> showEditAccountDialog(account) },
            onDeleteClicked = { account -> showDeleteDialog(account) },
            database = database,
            onRefreshCompleted = { account ->
                Toast.makeText(this@MainActivity, "Đã cập nhật điểm: ${account.email}", Toast.LENGTH_SHORT).show()
            }
        )

        lvAccounts.adapter = accountAdapter

        fabAddAccount.setOnClickListener {
            LoginDialog(this) {}.show()
        }

        scope.launch {
            database.rewardsDao().getAllActiveProfilesFlow().collectLatest { accounts ->
                if (accounts.isEmpty()) {
                    llEmptyAccounts.visibility = View.VISIBLE
                    lvAccounts.visibility = View.GONE
                } else {
                    llEmptyAccounts.visibility = View.GONE
                    lvAccounts.visibility = View.VISIBLE
                    accountAdapter.submitList(accounts)
                }
            }
        }
    }

    private fun showEditAccountDialog(account: AccountProfile) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val tvTargetLabel = TextView(this).apply {
            text = "Lượt tìm kiếm mục tiêu (10..60 lượt/ngày):"
            setTextColor(Color.parseColor("#334155"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        val etTarget = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(account.targetSearches.toString())
            hint = "35"
            setBackgroundResource(R.drawable.bg_card)
            setPadding(28, 20, 28, 20)
        }

        // Quick Preset Chips in Dialog
        val chipsScroll = HorizontalScrollView(this).apply {
            scrollBarSize = 0
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setPadding(0, 12, 0, 16)
        }
        val chipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val chipValues = listOf(15, 30, 35, 50, 60)
        chipValues.forEach { count ->
            val chip = Button(this).apply {
                text = "$count"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_micro_pill_all)
                setTextColor(Color.parseColor("#0078D4"))
                minWidth = 0
                minHeight = 0
                setPadding(28, 10, 28, 10)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 12, 0)
                }
                layoutParams = params
                stateListAnimator = null
                setOnClickListener {
                    etTarget.setText(count.toString())
                }
            }
            chipsContainer.addView(chip)
        }
        chipsScroll.addView(chipsContainer)

        val tvProxyLabel = TextView(this).apply {
            text = "Địa chỉ Proxy (SOCKS5 / HTTP):"
            setTextColor(Color.parseColor("#334155"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 0)
        }
        val etProxy = EditText(this).apply {
            hint = "socks5://user:pass@192.168.1.100:1080"
            setText(account.proxyUrl ?: "")
            setBackgroundResource(R.drawable.bg_card)
            setPadding(28, 20, 28, 20)
        }

        container.addView(tvTargetLabel)
        container.addView(etTarget)
        container.addView(chipsScroll)
        container.addView(tvProxyLabel)
        container.addView(etProxy)

        AlertDialog.Builder(this)
            .setTitle("Cấu hình tài khoản ${account.email}")
            .setMessage("Tùy chỉnh lượt tìm kiếm mục tiêu và phân lập proxy riêng:")
            .setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                val newTarget = etTarget.text.toString().toIntOrNull()?.coerceIn(10, 60) ?: account.targetSearches
                val newProxy = etProxy.text.toString().trim().takeIf { it.isNotEmpty() }
                scope.launch(Dispatchers.IO) {
                    val updated = account.copy(targetSearches = newTarget, proxyUrl = newProxy)
                    database.rewardsDao().updateProfile(updated)
                    database.rewardsDao().updateTargetSearches(account.id, newTarget)
                }
                Toast.makeText(this, "Đã lưu cấu hình tài khoản", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showDeleteDialog(account: AccountProfile) {
        AlertDialog.Builder(this)
            .setTitle("Xóa tài khoản")
            .setMessage("Bạn có chắc chắn muốn xóa tài khoản ${account.email} khỏi danh sách chạy ngầm?")
            .setPositiveButton("Xóa") { _, _ ->
                scope.launch(Dispatchers.IO) {
                    database.rewardsDao().deleteProfile(account)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ==========================================
    // TAB 3: VLOG TERMINAL BINDING (120Hz LTPO)
    // ==========================================
    private fun setupVlogTab() {
        lvVlogTerminal = viewVlog.findViewById(R.id.lvVlogTerminal)
        etLogSearch = viewVlog.findViewById(R.id.etLogSearch)
        btnExportVlog = viewVlog.findViewById(R.id.btnExportVlog)
        btnClearVlog = viewVlog.findViewById(R.id.btnClearVlog)
        fabScrollBottom = viewVlog.findViewById(R.id.fabScrollBottom)
        tvNewLogsBadge = viewVlog.findViewById(R.id.tvNewLogsBadge)

        btnFilterAll = viewVlog.findViewById(R.id.btnFilterAll)
        btnFilterErr = viewVlog.findViewById(R.id.btnFilterErr)
        btnFilterDom = viewVlog.findViewById(R.id.btnFilterDom)
        btnFilterSrch = viewVlog.findViewById(R.id.btnFilterSrch)
        btnFilterNet = viewVlog.findViewById(R.id.btnFilterNet)
        btnFilterCool = viewVlog.findViewById(R.id.btnFilterCool)

        vlogAdapter = VlogTerminalAdapter { entry ->
            showVlogDetailDialog(entry)
        }

        lvVlogTerminal.adapter = vlogAdapter

        // Smart Auto-scroll Lock Detection via AbsListView.OnScrollListener
        lvVlogTerminal.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}

            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (totalItemCount == 0) return
                val lastVisible = firstVisibleItem + visibleItemCount
                val atBottom = (lastVisible >= totalItemCount - 1)

                if (atBottom) {
                    isAutoScrollLocked = false
                    unreadCountSinceScroll = 0
                    fabScrollBottom.visibility = View.GONE
                    tvNewLogsBadge.visibility = View.GONE
                } else {
                    isAutoScrollLocked = true
                    if (currentTab == 2) {
                        fabScrollBottom.visibility = View.VISIBLE
                    }
                }
            }
        })

        fabScrollBottom.setOnClickListener {
            val total = vlogAdapter.count
            if (total > 0) {
                lvVlogTerminal.smoothScrollToPosition(total - 1)
            }
            isAutoScrollLocked = false
            unreadCountSinceScroll = 0
            fabScrollBottom.visibility = View.GONE
            tvNewLogsBadge.visibility = View.GONE
        }

        btnClearVlog.setOnClickListener {
            vlogManager.clearLogs()
            Toast.makeText(this, "Đã xóa toàn bộ nhật ký Vlog", Toast.LENGTH_SHORT).show()
        }

        btnExportVlog.setOnClickListener {
            val logsText = vlogManager.exportLogsAsString()
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, logsText)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Chia sẻ nhật ký Vlog"))
        }

        // 6 nút filter tag
        btnFilterAll.setOnClickListener { setVlogTagFilter(null) }
        btnFilterErr.setOnClickListener { setVlogTagFilter("ERR") }
        btnFilterDom.setOnClickListener { setVlogTagFilter("DOM") }
        btnFilterSrch.setOnClickListener { setVlogTagFilter("SRCH") }
        btnFilterNet.setOnClickListener { setVlogTagFilter("NET") }
        btnFilterCool.setOnClickListener { setVlogTagFilter("COOL") }

        // Thanh Quick Search debounce 250ms
        etLogSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchDebounceJob?.cancel()
                searchDebounceJob = scope.launch {
                    delay(250L)
                    currentSearchQuery = s?.toString()?.trim() ?: ""
                    filterAndRenderLogs()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Quan sát Vlog Stream
        scope.launch {
            vlogManager.logsStateFlow.collectLatest { logs ->
                allVlogsCache = logs
                val prevCount = vlogAdapter.count
                filterAndRenderLogs()

                if (isAutoScrollLocked && logs.size > prevCount) {
                    unreadCountSinceScroll += (logs.size - prevCount)
                    if (currentTab == 2) {
                        tvNewLogsBadge.text = "+$unreadCountSinceScroll"
                        tvNewLogsBadge.visibility = View.VISIBLE
                        fabScrollBottom.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setVlogTagFilter(tag: String?) {
        currentVlogTagFilter = tag
        filterAndRenderLogs()
    }

    private fun filterAndRenderLogs() {
        var filtered = allVlogsCache

        // Lọc theo tag
        if (currentVlogTagFilter != null) {
            val target = currentVlogTagFilter!!
            filtered = filtered.filter {
                it.tag.equals(target, ignoreCase = true) ||
                (target == "ERR" && it.level.equals("ERROR", ignoreCase = true))
            }
        }

        // Lọc theo từ khóa tìm kiếm
        if (currentSearchQuery.isNotEmpty()) {
            val query = currentSearchQuery
            filtered = filtered.filter {
                it.message.contains(query, ignoreCase = true) ||
                it.tag.contains(query, ignoreCase = true) ||
                (it.details?.contains(query, ignoreCase = true) == true) ||
                (it.accountEmail?.contains(query, ignoreCase = true) == true)
            }
        }

        vlogAdapter.submitList(filtered) {
            if (!isAutoScrollLocked && filtered.isNotEmpty()) {
                lvVlogTerminal.setSelection(filtered.size - 1)
            }
        }
    }

    private fun showVlogDetailDialog(entry: VlogEntry) {
        val details = buildString {
            append("Thời gian: ").append(entry.timeFormatted).append("\n")
            append("Tag: ").append(entry.tag).append(" (").append(entry.level).append(")\n")
            if (!entry.accountEmail.isNullOrBlank()) {
                append("Tài khoản: ").append(entry.accountEmail).append("\n")
            }
            append("Thông điệp: ").append(entry.message).append("\n")
            if (!entry.details.isNullOrBlank()) {
                append("\nChi tiết kỹ thuật:\n").append(entry.details).append("\n")
            }
            if (!entry.snapshotId.isNullOrBlank()) {
                append("\nDOM Snapshot ID: ").append(entry.snapshotId).append("\n")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Chi tiết Vlog Entry")
            .setMessage(details)
            .setPositiveButton("Đóng", null)
            .show()
    }

    // ==========================================
    // TAB 4: SETTINGS BINDING
    // ==========================================
    private fun setupSettingsTab() {
        etCooldownMin = viewSettings.findViewById(R.id.etCooldownMin)
        etCooldownMax = viewSettings.findViewById(R.id.etCooldownMax)
        etDefaultTargetSearches = viewSettings.findViewById(R.id.etDefaultTargetSearches)
        val btnPreset15 = viewSettings.findViewById<Button>(R.id.btnPreset15)
        val btnPreset30 = viewSettings.findViewById<Button>(R.id.btnPreset30)
        val btnPreset35 = viewSettings.findViewById<Button>(R.id.btnPreset35)
        val btnPreset50 = viewSettings.findViewById<Button>(R.id.btnPreset50)
        val btnPreset60 = viewSettings.findViewById<Button>(R.id.btnPreset60)
        swApplyTargetToAll = viewSettings.findViewById(R.id.swApplyTargetToAll)
        etGlobalProxy = viewSettings.findViewById(R.id.etGlobalProxy)
        etUserAgent = viewSettings.findViewById(R.id.etUserAgent)
        etTelegramBotToken = viewSettings.findViewById(R.id.etTelegramBotToken)
        etTelegramChatId = viewSettings.findViewById(R.id.etTelegramChatId)
        btnTestTelegram = viewSettings.findViewById(R.id.btnTestTelegram)
        swDarkMode = viewSettings.findViewById(R.id.swDarkMode)
        swWifiOnly = viewSettings.findViewById(R.id.swWifiOnly)
        swBatteryGuard = viewSettings.findViewById(R.id.swBatteryGuard)
        btnBatteryOptimization = viewSettings.findViewById(R.id.btnBatteryOptimization)
        btnSaveSettings = viewSettings.findViewById(R.id.btnSaveSettings)

        // Load cấu hình từ EncryptedPreferences
        etCooldownMin.setText(prefs.cooldownMin.toString())
        etCooldownMax.setText(prefs.cooldownMax.toString())
        etDefaultTargetSearches.setText(prefs.defaultTargetSearches.toString())
        etGlobalProxy.setText(prefs.globalProxy)
        etUserAgent.setText(prefs.userAgent)
        etTelegramBotToken.setText(prefs.telegramBotToken)
        etTelegramChatId.setText(prefs.telegramChatId)
        swDarkMode.isChecked = prefs.isDarkMode
        swWifiOnly.isChecked = prefs.isWifiOnly
        swBatteryGuard.isChecked = prefs.isBatteryGuardEnabled

        // Preset Chips cho Target Searches
        btnPreset15?.setOnClickListener { etDefaultTargetSearches.setText("15") }
        btnPreset30?.setOnClickListener { etDefaultTargetSearches.setText("30") }
        btnPreset35?.setOnClickListener { etDefaultTargetSearches.setText("35") }
        btnPreset50?.setOnClickListener { etDefaultTargetSearches.setText("50") }
        btnPreset60?.setOnClickListener { etDefaultTargetSearches.setText("60") }

        // Nút Test Telegram
        btnTestTelegram.setOnClickListener {
            val token = etTelegramBotToken.text.toString().trim()
            val chatId = etTelegramChatId.text.toString().trim()
            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập Bot Token và Chat ID trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            scope.launch(Dispatchers.IO) {
                val ok = TelegramNotifier(token, chatId).sendMessage("🔔 Thử nghiệm kết nối Telegram từ Xiaomi Mi 14 thành công!")
                withContext(AndroidMainDispatcher) {
                    if (ok) {
                        Toast.makeText(this@MainActivity, "Gửi tin nhắn Telegram thành công!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Gửi thất bại. Vui lòng kiểm tra lại Token/ID", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Bỏ qua tối ưu pin
        btnBatteryOptimization.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // Nút Lưu Cấu Hình
        btnSaveSettings.setOnClickListener {
            val min = etCooldownMin.text.toString().toIntOrNull() ?: 15
            val max = etCooldownMax.text.toString().toIntOrNull() ?: 22
            val target = etDefaultTargetSearches.text.toString().toIntOrNull()?.coerceIn(10, 60) ?: 35

            prefs.cooldownMin = min
            prefs.cooldownMax = max
            prefs.cooldownMinutes = (min + max) / 2
            prefs.defaultTargetSearches = target
            prefs.globalProxy = etGlobalProxy.text.toString().trim()
            prefs.userAgent = etUserAgent.text.toString().trim()
            prefs.telegramBotToken = etTelegramBotToken.text.toString().trim()
            prefs.telegramChatId = etTelegramChatId.text.toString().trim()
            prefs.isDarkMode = swDarkMode.isChecked
            prefs.isWifiOnly = swWifiOnly.isChecked
            prefs.isBatteryGuardEnabled = swBatteryGuard.isChecked

            if (swApplyTargetToAll.isChecked) {
                scope.launch(Dispatchers.IO) {
                    database.rewardsDao().updateAllTargetSearches(target)
                }
            }

            Toast.makeText(this, "Đã lưu toàn bộ cấu hình hệ thống!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                }
            } else {
                Toast.makeText(this, "Ứng dụng đã được cấp quyền chạy ngầm 24/7!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==========================================
    // NAVIGATION & BACK PRESS HANDLER
    // ==========================================
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Nếu đang ở Tab 2, 3, 4 thì chuyển về Tab 1 trước khi thoát
        if (currentTab != 0) {
            selectTab(0)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchDebounceJob?.cancel()
        scope.cancel()
    }
}
