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
import android.view.Gravity
import android.text.TextUtils
import android.view.WindowInsets
import android.widget.*
import com.agy.rewardsnode.R
import com.agy.rewardsnode.core.DailyTask
import com.agy.rewardsnode.core.ForensicShredder
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.core.VaultBackupManager
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private var tvBasketNews: TextView? = null
    private var tvBasketDailyPromo: TextView? = null
    private var tvProfileEmail: TextView? = null
    private var tvProfileName: TextView? = null
    private var tvProfileBadge: TextView? = null
    private var tvAvatarInitials: TextView? = null
    private var tvStreakBadge: TextView? = null
    private var tvLevelBadge: TextView? = null
    private var tvTelemetryDailySet: TextView? = null
    private var tvTelemetryClaim: TextView? = null
    private var tvTelemetryRouting: TextView? = null
    private var tvTelemetrySafety: TextView? = null
    private var tvTelemetryBingSso: TextView? = null
    private var tvTimerReset: TextView? = null
    private var countdownTickerJob: Job? = null
    private var latestDashboardAccounts: List<AccountProfile> = emptyList()
    private var activeDashboardProfile: AccountProfile? = null

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
    private lateinit var btnVlogPointHistory: Button
    private lateinit var btnExportVlog: ImageButton
    private lateinit var btnClearVlog: ImageButton
    private lateinit var fabScrollTop: FrameLayout
    private lateinit var fabScrollBottom: FrameLayout
    private lateinit var tvNewLogsBadge: TextView
    private lateinit var btnFilterAll: Button
    private lateinit var btnFilterPoint: Button
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
    private lateinit var etBatchSize: EditText
    private lateinit var etSearchDelayMin: EditText
    private lateinit var etSearchDelayMax: EditText
    private lateinit var etMaxClaimRetries: EditText
    private lateinit var etDapiMaxRetries: EditText
    private lateinit var etMaxReconciliationPasses: EditText
    private lateinit var etCooldownTriggerBatches: EditText
    private lateinit var etFrozenStopBatches: EditText
    private lateinit var etProxyCircuitThreshold: EditText
    private lateinit var etGlobalProxy: EditText
    private lateinit var etUserAgent: EditText
    private lateinit var etTelegramBotToken: EditText
    private lateinit var etTelegramChatId: EditText
    private lateinit var btnTestTelegram: Button
    private lateinit var btnResetTelegramDefault: Button
    private lateinit var swDarkMode: Switch
    private lateinit var swWifiOnly: Switch
    private lateinit var swBatteryGuard: Switch
    private lateinit var btnBatteryOptimization: Button
    private lateinit var swAutoDailySchedule: Switch
    private lateinit var etScheduleSlot1: EditText
    private lateinit var etScheduleSlot2: EditText
    private lateinit var etScheduleSlot3: EditText
    private lateinit var swScheduleSlot1: Switch
    private lateinit var swScheduleSlot2: Switch
    private lateinit var swScheduleSlot3: Switch
    private lateinit var swAutoStopCompleted: Switch
    private lateinit var btnEmergencyReset: Button
    private lateinit var btnHealthCheck: Button
    private lateinit var btnVaultBackup: Button
    private lateinit var btnVaultRestore: Button
    private lateinit var btnForensicShred: Button
    private lateinit var btnSaveSettings: Button

    private var pendingBackupFile: File? = null

    companion object {
        private const val REQ_CREATE_BACKUP_DOC = 2001
        private const val REQ_OPEN_BACKUP_DOC = 2002

        internal data class PointHistoryRecord(
            val timeFormatted: String,
            val accountEmail: String,
            val category: String,
            val title: String,
            val pointsDelta: Int
        )

        internal fun classifyPointVlogEntry(entry: VlogEntry, knownEmails: List<String>): PointHistoryRecord? {
            val msg = entry.message
            if (msg.contains("Bắt đầu", ignoreCase = true) ||
                msg.contains("Phát hiện", ignoreCase = true) ||
                msg.contains("Hoàn thành Daily Task qua HTTP", ignoreCase = true) ||
                msg.contains("Đang thử", ignoreCase = true) ||
                msg.contains("Thất bại", ignoreCase = true)
            ) {
                return null
            }

            val resolvedEmail = when {
                !entry.accountEmail.isNullOrBlank() -> entry.accountEmail
                else -> knownEmails.firstOrNull { msg.contains(it, ignoreCase = true) }
                    ?: Regex("""([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""").find(msg)?.groupValues?.getOrNull(1)
                    ?: "Hệ thống"
            }

            val ptsMatch = Regex("""\+(\d+)\s*(?:pts|đ|điểm)""", RegexOption.IGNORE_CASE).find(msg)
            val pts = ptsMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            return when {
                msg.contains("[POINT]", ignoreCase = true) || entry.tag.equals("POINT", ignoreCase = true) -> {
                    if (pts <= 0) return null
                    PointHistoryRecord(
                        timeFormatted = entry.timeFormatted,
                        accountEmail = resolvedEmail,
                        category = "🔍 Search",
                        title = msg.replace("[POINT]", "").trim(),
                        pointsDelta = pts
                    )
                }
                msg.contains("[DAPI]", ignoreCase = true) && msg.contains("thành công", ignoreCase = true) -> {
                    PointHistoryRecord(
                        timeFormatted = entry.timeFormatted,
                        accountEmail = resolvedEmail,
                        category = "📱 DAPI",
                        title = msg.replace("[DAPI]", "").trim(),
                        pointsDelta = pts
                    )
                }
                (msg.contains("[TASK]", ignoreCase = true) || msg.contains("[FAST-PATH]", ignoreCase = true)) &&
                    (msg.contains("Hoàn thành", ignoreCase = true) || msg.contains("cộng điểm", ignoreCase = true)) -> {
                    PointHistoryRecord(
                        timeFormatted = entry.timeFormatted,
                        accountEmail = resolvedEmail,
                        category = "📅 Daily Set",
                        title = msg.replace("[TASK]", "").replace("[FAST-PATH]", "").trim(),
                        pointsDelta = pts
                    )
                }
                msg.contains("[KEEP-EARNING]", ignoreCase = true) && msg.contains("Hoàn thành", ignoreCase = true) -> {
                    PointHistoryRecord(
                        timeFormatted = entry.timeFormatted,
                        accountEmail = resolvedEmail,
                        category = "⚡ Keep Earning",
                        title = msg.replace("[KEEP-EARNING]", "").trim(),
                        pointsDelta = pts
                    )
                }
                msg.contains("[READY-TO-CLAIM]", ignoreCase = true) && msg.contains("Đã nhận", ignoreCase = true) -> {
                    PointHistoryRecord(
                        timeFormatted = entry.timeFormatted,
                        accountEmail = resolvedEmail,
                        category = "🎁 Ready to Claim",
                        title = msg.replace("[READY-TO-CLAIM]", "").trim(),
                        pointsDelta = pts
                    )
                }
                else -> null
            }
        }
    }

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

    override fun onStart() {
        super.onStart()
        startDualCountdownTicker()
        updateServiceUIState()
    }

    override fun onResume() {
        super.onResume()
        updateServiceUIState()
        syncActiveProfilesBalance()
    }

    override fun onStop() {
        super.onStop()
        countdownTickerJob?.cancel()
        if (::chronometerUptime.isInitialized) chronometerUptime.stop()
        isUptimeRunning = false
    }

    private fun startDualCountdownTicker() {
        countdownTickerJob?.cancel()
        countdownTickerJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val diffResetSec = ((cal.timeInMillis - now) / 1000L).coerceAtLeast(0L)
                val rH = diffResetSec / 3600L
                val rM = (diffResetSec % 3600L) / 60L
                val rS = diffResetSec % 60L
                tvTimerReset?.text = String.format(Locale.US, "%02d:%02d:%02d", rH, rM, rS)
                delay(1000L)
            }
        }
    }

    private fun syncActiveProfilesBalance(isUserTriggered: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                if (ProfileManager.isInteractiveLoginActive) {
                    android.util.Log.d("MainActivity", "Bỏ qua syncActiveProfilesBalance: Phiên đăng nhập tương tác đang hoạt động")
                    return@launch
                }
                if (isUserTriggered) {
                    withContext(AndroidMainDispatcher) {
                        if (::swipeRefreshAccounts.isInitialized) {
                            swipeRefreshAccounts.isRefreshing = true
                        }
                    }
                }
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val profiles = database.rewardsDao().getAllActiveProfiles()
                var updatedCount = 0
                for (profile in profiles) {
                    if (ProfileManager.isInteractiveLoginActive) {
                        break
                    }
                    val preserveDailyDoneToday = (profile.lastActiveDate == todayStr && profile.dailySetDone)
                    var activeCookies = profile.cookiesSnapshot
                    if (!activeCookies.isNullOrBlank()) {
                        val effectiveUa = profile.customUserAgent ?: prefs.userAgent
                        var result = RewardsBalanceExtractor.extractPointsViaHttp(activeCookies, effectiveUa, profile.proxyUrl)
                        if (!result.isSuccess || result.availablePoints <= 0 || profile.cookieExpired) {
                            val renewedCookies = ProfileManager.renewCookiesViaPureHttp(
                                activeCookies,
                                effectiveUa,
                                profile.proxyUrl
                            )
                            if (!renewedCookies.isNullOrBlank()) {
                                activeCookies = renewedCookies
                                database.rewardsDao().updateProfile(
                                    profile.copy(cookiesSnapshot = renewedCookies, cookieExpired = false)
                                )
                                result = RewardsBalanceExtractor.extractPointsViaHttp(activeCookies, effectiveUa, profile.proxyUrl)
                            }
                        }
                        if (result.isSuccess) {
                            RewardsBalanceExtractor.latestTelemetryMap[profile.id] = result
                            if (profile.email.isNotBlank()) {
                                RewardsBalanceExtractor.latestTelemetryMap[profile.email] = result
                            }
                            val avail = if (result.availablePoints > 0) result.availablePoints else profile.availablePoints
                            val emailToSet = if (!result.email.isNullOrBlank() && (profile.email.startsWith("account_") || !profile.email.contains("@"))) {
                                result.email ?: profile.email
                            } else {
                                profile.email
                            }
                            val nameToSet = if (!result.profileName.isNullOrBlank() && (profile.profileName.startsWith("Profile ") || profile.profileName.isBlank())) {
                                result.profileName ?: profile.profileName
                            } else {
                                profile.profileName
                            }
                            val levelToSet = if (result.level in 1..2) result.level else profile.level

                            val pcM = if (result.pcMax > 0) result.pcMax else (if (levelToSet == 1) 30 else 90)
                            val pcProg = minOf(result.pcProgress, pcM)
                            val mobM = if (result.mobileMax > 0) result.mobileMax else (if (levelToSet == 1) 0 else 60)
                            val mobProg = if (mobM > 0) minOf(result.mobileProgress, mobM) else 0
                            val edgeM = if (result.edgeMax > 0) result.edgeMax else 0
                            val edgeProg = if (edgeM > 0) minOf(result.edgeProgress, edgeM) else 0
                            val readM = if (result.readNewsMax > 0) result.readNewsMax else profile.readMax
                            val readProg = maxOf(profile.readProgress, result.readNewsProgress)
                            val estSearches = maxOf(profile.completedSearchesToday, (pcProg + mobProg) / 3)

                            val pointsEarned = when {
                                result.pointsEarnedToday > 0 -> result.pointsEarnedToday
                                (pcProg + mobProg + edgeProg) > 0 -> pcProg + mobProg + edgeProg
                                profile.pointsEarnedToday in 1..200 -> profile.pointsEarnedToday
                                profile.baselinePointsToday > 0 && avail > profile.baselinePointsToday -> avail - profile.baselinePointsToday
                                else -> 0
                            }

                            val updated = profile.copy(
                                email = emailToSet,
                                profileName = nameToSet,
                                cookiesSnapshot = activeCookies,
                                cookieExpired = false,
                                level = levelToSet,
                                targetSearches = if (levelToSet == 1) {
                                    if (profile.targetSearches in 10..45) profile.targetSearches else 35
                                } else {
                                    if (profile.targetSearches in 40..65) profile.targetSearches else 55
                                },
                                completedSearchesToday = estSearches,
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
                                edgeMax = edgeM,
                                readProgress = readProg,
                                readMax = readM,
                                dailySetDone = preserveDailyDoneToday || (if (result.dailySetTotal > 0) result.dailySetDone else profile.dailySetDone),
                                dailySetProgress = if (result.dailySetTotal > 0) {
                                    if (result.dailySetDone || preserveDailyDoneToday) maxOf(result.dailySetProgress, result.dailySetTotal) else result.dailySetProgress
                                } else {
                                    profile.dailySetProgress
                                },
                                dailySetTotal = if (result.dailySetTotal > 0) result.dailySetTotal else profile.dailySetTotal,
                                claimablePoints = result.claimablePoints
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
            if (::fabScrollTop.isInitialized) fabScrollTop.visibility = View.GONE
            if (::fabScrollBottom.isInitialized) fabScrollBottom.visibility = View.GONE
            if (::tvNewLogsBadge.isInitialized) tvNewLogsBadge.visibility = View.GONE
        } else {
            if (::fabScrollTop.isInitialized) fabScrollTop.visibility = View.VISIBLE
            if (::fabScrollBottom.isInitialized) fabScrollBottom.visibility = View.VISIBLE
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
        tvBasketNews = viewDashboard.findViewById(R.id.tvBasketNews)
        tvBasketDailyPromo = viewDashboard.findViewById(R.id.tvBasketDailyPromo)

        // Đồng hồ đếm ngược Reset 00:00
        tvTimerReset = viewDashboard.findViewById(R.id.tvTimerReset)

        // Header Profile
        tvProfileEmail = viewDashboard.findViewById(R.id.tvProfileEmail)
        tvProfileName = viewDashboard.findViewById(R.id.tvProfileName)
        tvProfileBadge = viewDashboard.findViewById(R.id.tvProfileBadge)
        tvAvatarInitials = viewDashboard.findViewById(R.id.tvAvatarInitials)
        tvStreakBadge = viewDashboard.findViewById(R.id.tvStreakBadge)
        tvLevelBadge = viewDashboard.findViewById(R.id.tvLevelBadge)

        // Ánh xạ các nhãn động trong thẻ Thông Số Vận Hành 24/7
        tvTelemetryRouting = findTextViewWithText(viewDashboard, "PC 90đ • Mob 60đ")
        tvTelemetrySafety = findTextViewWithText(viewDashboard, "Sàn Toán 920s")
        tvTelemetryBingSso = findTextViewWithText(viewDashboard, "Token Đã Khớp")
        tvTelemetrySafety?.text = "Cooldown ${prefs.cooldownMin}-${prefs.cooldownMax}p"

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

                latestDashboardAccounts = accounts

                // Ưu tiên chọn profile hiển thị
                val profile = if (!runningId.isNullOrBlank()) {
                    accounts.find { it.id == runningId } ?: activeAccounts.firstOrNull()
                } else {
                    activeAccounts.firstOrNull { !it.isAllMaxedOutToday(todayStr) }
                        ?: activeAccounts.firstOrNull()
                        ?: accounts.firstOrNull()
                }
                activeDashboardProfile = profile

                if (profile != null) {
                    val pcMax = if (profile.pcMax > 0) profile.pcMax else (if (profile.level == 1) 30 else 90)
                    val edgeMax = if (profile.edgeMax > 0) profile.edgeMax else 0
                    val mobMax = if (profile.mobileMax > 0) profile.mobileMax else (if (profile.level == 1) 0 else 60)
                    val readMax = if (profile.readMax > 0) profile.readMax else 30

                    val pcRatio = if (pcMax > 0) (profile.pcProgress.toFloat() / pcMax).coerceIn(0f, 1f) else 0f
                    val edgeRatio = if (edgeMax > 0) (profile.edgeProgress.toFloat() / edgeMax).coerceIn(0f, 1f) else 0f
                    val mobileRatio = if (mobMax > 0) (profile.mobileProgress.toFloat() / mobMax).coerceIn(0f, 1f) else 0f

                    // Cập nhật ActivityRingView với animation mượt mà
                    activityRingView?.setProgress(pcRatio, edgeRatio, mobileRatio, animate = true)

                    // Cập nhật các nhãn rổ điểm (PC, Edge, Mobile, News)
                    tvPcBasket?.text = "PC ${profile.pcProgress}/$pcMax"
                    tvEdgeBasket?.text = "Edge ${profile.edgeProgress}/$edgeMax"
                    tvMobileBasket?.text = "Mob ${profile.mobileProgress}/$mobMax"
                    tvBasketNews?.text = "News ${profile.readProgress}/$readMax"

                    val profileEarnedToday = when {
                        profile.pointsEarnedToday > 0 -> profile.pointsEarnedToday
                        (profile.pcProgress + profile.mobileProgress + profile.edgeProgress + profile.readProgress) > 0 -> {
                            profile.pcProgress + profile.mobileProgress + profile.edgeProgress + profile.readProgress
                        }
                        else -> 0
                    }
                    tvRingTotalPoints?.text = String.format("%,d", profile.availablePoints)
                    tvRingPointsLabel?.text = "+${profileEarnedToday}Đ HÔM NAY"

                    // Cập nhật các nhãn động trong cardActiveNodeTelemetry
                    tvTelemetryRouting?.text = if (mobMax > 0) "PC ${pcMax}đ • Mob ${mobMax}đ" else "PC ${pcMax}đ • Read ${readMax}đ"
                    tvTelemetrySafety?.text = "Cooldown ${prefs.cooldownMin}-${prefs.cooldownMax}p"
                    if (profile.cookieExpired) {
                        tvTelemetryBingSso?.text = "Cần Đăng Nhập Lại"
                        tvTelemetryBingSso?.setTextColor(Color.parseColor("#EF4444"))
                    } else {
                        tvTelemetryBingSso?.text = "Token Đã Khớp"
                        tvTelemetryBingSso?.setTextColor(Color.parseColor("#E2E8F0"))
                    }

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
                    tvEdgeBasket?.text = "Edge 0/0"
                    tvMobileBasket?.text = "Mob 0/0"
                    tvBasketNews?.text = "News 0/30"
                    tvBasketDailyPromo?.text = "Daily 0/3"
                    tvRingTotalPoints?.text = "0"
                    tvRingPointsLabel?.text = "ĐIỂM HIỆN TẠI"
                    tvTelemetryRouting?.text = "PC 90đ • Mob 60đ"
                    tvTelemetrySafety?.text = "Cooldown ${prefs.cooldownMin}-${prefs.cooldownMax}p"
                    tvTelemetryBingSso?.text = "Token Đã Khớp"
                    tvTelemetryBingSso?.setTextColor(Color.parseColor("#E2E8F0"))
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

                val totalDaily = telemetry?.dailySetTotal ?: (if ((profile?.dailySetTotal ?: 0) > 0) profile!!.dailySetTotal else 3)
                val isDailySetFinished = (profile?.dailySetDone == true) || (telemetry?.dailySetDone == true)
                val dailySetCompletedCount = if (isDailySetFinished) totalDaily else maxOf(profile?.dailySetProgress ?: 0, telemetry?.dailySetProgress ?: 0)
                if (profile != null) {
                    tvBasketDailyPromo?.text = "Daily $dailySetCompletedCount/$totalDaily"
                } else {
                    tvBasketDailyPromo?.text = "Daily 0/3"
                }

                if (isDailySetFinished) {
                    tvTelemetryDailySet?.text = if (telemetry?.streakProtectionOn == true) "Hoàn tất ($dailySetCompletedCount/$totalDaily) • Bảo vệ" else "Hoàn tất ($dailySetCompletedCount/$totalDaily)"
                    tvTelemetryDailySet?.setTextColor(Color.parseColor("#10B981"))
                } else if (dailySetCompletedCount > 0) {
                    tvTelemetryDailySet?.text = "$dailySetCompletedCount/$totalDaily hoạt động"
                    tvTelemetryDailySet?.setTextColor(Color.parseColor("#F59E0B"))
                } else if (profile != null) {
                    tvTelemetryDailySet?.text = if (telemetry?.streakProtectionOn == true) "Chưa làm • Bảo vệ Bật" else "Chưa làm (0/$totalDaily)"
                    tvTelemetryDailySet?.setTextColor(Color.parseColor("#94A3B8"))
                } else {
                    tvTelemetryDailySet?.text = "Sẵn sàng"
                    tvTelemetryDailySet?.setTextColor(Color.parseColor("#38BDF8"))
                }

                val dashClaimable = telemetry?.claimablePoints ?: (profile?.claimablePoints ?: 0)
                if (dashClaimable > 0) {
                    tvTelemetryClaim?.text = "+${dashClaimable}đ Sẵn sàng"
                    tvTelemetryClaim?.setTextColor(Color.parseColor("#F59E0B"))
                } else {
                    tvTelemetryClaim?.text = "Đã nhận hết"
                    tvTelemetryClaim?.setTextColor(Color.parseColor("#10B981"))
                }

                // Cập nhật Danh Sách Nhiệm Vụ Động (Daily Set Tasks)
                val llTasksContainer = viewDashboard.findViewById<LinearLayout>(R.id.llDynamicTasksContainer)
                val tvEmptyTasks = viewDashboard.findViewById<TextView>(R.id.tvEmptyDynamicTasks)
                val tvTasksBadge = viewDashboard.findViewById<TextView>(R.id.tvTasksBadge)

                val dailyTasksList = telemetry?.dailyTasks ?: emptyList()
                if (llTasksContainer != null) {
                    if (dailyTasksList.isNotEmpty()) {
                        tvEmptyTasks?.visibility = View.GONE
                        while (llTasksContainer.childCount > 1) {
                            llTasksContainer.removeViewAt(1)
                        }
                        val isDailySetItem: (DailyTask) -> Boolean = { t ->
                            t.id.contains("daily_set", ignoreCase = true) ||
                                t.id.contains("dailyset", ignoreCase = true) ||
                                t.id.startsWith("Global_DailySet")
                        }
                        val matchedDailySet = dailyTasksList.filter(isDailySetItem)
                        val dailySetTasks = if (matchedDailySet.isNotEmpty()) matchedDailySet else dailyTasksList.take(3)
                        val morePromoTasks = dailyTasksList.filter { t -> !dailySetTasks.any { it.id == t.id } }
                        val dsDone = dailySetTasks.count { it.isCompleted }
                        val dsTotal = dailySetTasks.size
                        val promoDone = morePromoTasks.count { it.isCompleted }
                        val promoTotal = morePromoTasks.size

                        if (morePromoTasks.isNotEmpty()) {
                            tvTasksBadge?.text = "$dsDone/$dsTotal Daily • $promoDone/$promoTotal Khám phá"
                        } else {
                            tvTasksBadge?.text = "$dsDone/$dsTotal DAILY SET"
                        }

                        val renderTaskRow = { task: DailyTask ->
                            val taskRow = LinearLayout(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
                                }
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(
                                    (10 * resources.displayMetrics.density).toInt(),
                                    (8 * resources.displayMetrics.density).toInt(),
                                    (10 * resources.displayMetrics.density).toInt(),
                                    (8 * resources.displayMetrics.density).toInt()
                                )
                                setBackgroundResource(R.drawable.bg_pill_dark)

                                val icon = when (task.taskType.lowercase(Locale.ROOT)) {
                                    "poll" -> "📊 "
                                    "quiz" -> "❓ "
                                    "news" -> "📰 "
                                    else -> "🔗 "
                                }

                                val tvTitle = TextView(this@MainActivity).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                    text = "$icon${task.title}"
                                    setTextColor(if (task.isCompleted) Color.parseColor("#94A3B8") else Color.parseColor("#F8FAFC"))
                                    textSize = 11f
                                    typeface = Typeface.DEFAULT_BOLD
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                }

                                val tvPoints = TextView(this@MainActivity).apply {
                                    text = "+${task.pointValue}đ"
                                    setTextColor(Color.parseColor("#F59E0B"))
                                    textSize = 10f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setPadding(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                                }

                                val tvStatus = TextView(this@MainActivity).apply {
                                    text = if (task.isCompleted) "✓ Xong" else "Chờ làm"
                                    setTextColor(if (task.isCompleted) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
                                    textSize = 10f
                                    typeface = Typeface.DEFAULT_BOLD
                                }

                                addView(tvTitle)
                                addView(tvPoints)
                                addView(tvStatus)
                            }
                            llTasksContainer.addView(taskRow)
                        }

                        for (task in dailySetTasks) {
                            renderTaskRow(task)
                        }

                        if (morePromoTasks.isNotEmpty()) {
                            val tvHeader = TextView(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
                                }
                                text = "⚡ KHÁM PHÁ THÊM / KEEP EARNING"
                                setTextColor(Color.parseColor("#F59E0B"))
                                textSize = 10f
                                typeface = Typeface.DEFAULT_BOLD
                            }
                            llTasksContainer.addView(tvHeader)

                            for (task in morePromoTasks) {
                                renderTaskRow(task)
                            }
                        }
                    } else {
                        tvEmptyTasks?.visibility = View.VISIBLE
                        while (llTasksContainer.childCount > 1) {
                            llTasksContainer.removeViewAt(1)
                        }
                        if (isDailySetFinished) {
                            tvEmptyTasks?.text = "Tất cả nhiệm vụ Daily Set đã hoàn tất ✓"
                            tvEmptyTasks?.setTextColor(Color.parseColor("#10B981"))
                            tvTasksBadge?.text = "HOÀN TẤT"
                        } else {
                            tvEmptyTasks?.text = "Đang đồng bộ nhiệm vụ từ Bing Rewards..."
                            tvEmptyTasks?.setTextColor(Color.parseColor("#94A3B8"))
                            tvTasksBadge?.text = "DAILY SET"
                        }
                    }
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
                val baseTime = if (RewardsForegroundService.serviceStartElapsedRealtime > 0L) {
                    RewardsForegroundService.serviceStartElapsedRealtime
                } else {
                    SystemClock.elapsedRealtime()
                }
                chronometerUptime.base = baseTime
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
        btnVlogPointHistory = viewVlog.findViewById(R.id.btnVlogPointHistory)
        btnExportVlog = viewVlog.findViewById(R.id.btnExportVlog)
        btnClearVlog = viewVlog.findViewById(R.id.btnClearVlog)
        fabScrollTop = viewVlog.findViewById(R.id.fabScrollTop)
        fabScrollBottom = viewVlog.findViewById(R.id.fabScrollBottom)
        tvNewLogsBadge = viewVlog.findViewById(R.id.tvNewLogsBadge)

        btnFilterAll = viewVlog.findViewById(R.id.btnFilterAll)
        btnFilterPoint = viewVlog.findViewById(R.id.btnFilterPoint)
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
                val atTop = (firstVisibleItem == 0)
                if (atTop) {
                    isAutoScrollLocked = false
                    unreadCountSinceScroll = 0
                    tvNewLogsBadge.visibility = View.GONE
                } else {
                    isAutoScrollLocked = true
                }
            }
        })

        val scrollTopAction = View.OnClickListener {
            if (vlogAdapter.count > 0) {
                lvVlogTerminal.setSelection(0)
            }
            isAutoScrollLocked = false
            unreadCountSinceScroll = 0
            tvNewLogsBadge.visibility = View.GONE
        }
        fabScrollTop.setOnClickListener(scrollTopAction)
        viewVlog.findViewById<View>(R.id.btnScrollTopInner)?.setOnClickListener(scrollTopAction)

        val scrollBottomAction = View.OnClickListener {
            val total = vlogAdapter.count
            if (total > 0) {
                lvVlogTerminal.setSelection(total - 1)
            }
            unreadCountSinceScroll = 0
            tvNewLogsBadge.visibility = View.GONE
        }
        fabScrollBottom.setOnClickListener(scrollBottomAction)
        viewVlog.findViewById<View>(R.id.btnScrollBottomInner)?.setOnClickListener(scrollBottomAction)

        btnVlogPointHistory.setOnClickListener {
            showPointHistoryDialog()
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

        // 7 nút filter tag
        btnFilterAll.setOnClickListener { setVlogTagFilter(null) }
        btnFilterPoint.setOnClickListener { setVlogTagFilter("POINT") }
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

        vlogAdapter.submitList(filtered)
    }


    private fun showPointHistoryDialog() {
        scope.launch(Dispatchers.IO) {
            try {
                vlogManager.flushNow()
                val profiles = database.rewardsDao().getAllProfiles()
                val dbPointLogs = database.vlogDao().getPointHistoryVlogs(600)
                val combinedLogs = (allVlogsCache + dbPointLogs)
                    .distinctBy { "${it.timestamp}_${it.message}" }
                    .sortedByDescending { it.timestamp }
                val knownEmails = profiles.map { it.email }.filter { it.isNotBlank() }
                val records = combinedLogs.mapNotNull { classifyPointVlogEntry(it, knownEmails) }

                withContext(AndroidMainDispatcher) {
                    val density = resources.displayMetrics.density
                    val root = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.parseColor("#0F172A"))
                        setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                    }

                    val accountOptions = mutableListOf("Tất cả tài khoản (${profiles.size})")
                    profiles.forEach { p ->
                        if (p.email.isNotBlank()) accountOptions.add(p.email)
                    }
                    val categoryOptions = listOf(
                        "Tất cả nhiệm vụ",
                        "🔍 Search",
                        "📅 Daily Set",
                        "⚡ Keep Earning",
                        "📱 DAPI",
                        "🎁 Ready to Claim"
                    )

                    fun createDarkSpinnerAdapter(items: List<String>) = object : ArrayAdapter<String>(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        items
                    ) {
                        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            return super.getView(position, convertView, parent).apply {
                                (this as? TextView)?.setTextColor(Color.parseColor("#F8FAFC"))
                            }
                        }

                        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            return super.getDropDownView(position, convertView, parent).apply {
                                setBackgroundColor(Color.parseColor("#1E293B"))
                                (this as? TextView)?.setTextColor(Color.parseColor("#F8FAFC"))
                            }
                        }
                    }

                    val spAccount = Spinner(this@MainActivity).apply {
                        setBackgroundResource(R.drawable.bg_pill_dark)
                        adapter = createDarkSpinnerAdapter(accountOptions)
                    }
                    val spCategory = Spinner(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (6 * density).toInt()
                            bottomMargin = (8 * density).toInt()
                        }
                        setBackgroundResource(R.drawable.bg_pill_dark)
                        adapter = createDarkSpinnerAdapter(categoryOptions)
                    }

                    val tvSummary = TextView(this@MainActivity).apply {
                        setTextColor(Color.parseColor("#F8FAFC"))
                        textSize = 12f
                        setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                        setBackgroundResource(R.drawable.bg_profile_card_dark)
                    }

                    val scrollHistory = ScrollView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (300 * density).toInt()
                        ).apply {
                            topMargin = (8 * density).toInt()
                        }
                        setBackgroundResource(R.drawable.bg_profile_card_dark)
                    }
                    val tvHistoryList = TextView(this@MainActivity).apply {
                        setTextColor(Color.parseColor("#E2E8F0"))
                        textSize = 11.5f
                        typeface = Typeface.MONOSPACE
                        setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                    }
                    scrollHistory.addView(tvHistoryList)

                    val renderFilteredView = {
                        val accIdx = spAccount.selectedItemPosition
                        val selectedEmail: String? = if (accIdx <= 0) null else accountOptions.getOrNull(accIdx)
                        val catIdx = spCategory.selectedItemPosition
                        val selectedCat: String? = if (catIdx <= 0) null else categoryOptions.getOrNull(catIdx)

                        val targetProfiles = if (selectedEmail == null) profiles else profiles.filter { it.email.equals(selectedEmail, ignoreCase = true) }

                        val pcPts = targetProfiles.sumOf { it.pcProgress }
                        val mobPts = targetProfiles.sumOf { it.mobileProgress }
                        val edgePts = targetProfiles.sumOf { it.edgeProgress }
                        val readPts = targetProfiles.sumOf { it.readProgress }
                        val earnedToday = targetProfiles.sumOf { it.pointsEarnedToday }

                        var dailyDonePts = 0
                        var promoDonePts = 0
                        targetProfiles.forEach { p ->
                            val tele = RewardsBalanceExtractor.latestTelemetryMap[p.id]
                                ?: RewardsBalanceExtractor.latestTelemetryMap[p.email]
                            if (tele != null) {
                                val dsLimit = tele.dailySetTotal.coerceAtLeast(3)
                                dailyDonePts += tele.dailyTasks.take(dsLimit).filter { it.isCompleted }.sumOf { it.pointValue }
                                promoDonePts += tele.dailyTasks.drop(dsLimit).filter { it.isCompleted && it.pointValue > 0 }.sumOf { it.pointValue }
                            }
                        }

                        val filteredRecords = records.filter { rec ->
                            (selectedEmail == null || rec.accountEmail.equals(selectedEmail, ignoreCase = true)) &&
                            (selectedCat == null || rec.category == selectedCat)
                        }
                        val totalLoggedPts = filteredRecords.sumOf { it.pointsDelta }

                        tvSummary.text = buildString {
                            append("📊 TỔNG HỢP ĐIỂM HÔM NAY (${if (selectedEmail == null) "Tất cả TK" else selectedEmail}):\n")
                            append("• 🔍 Search: +${pcPts + mobPts + edgePts}đ (PC: $pcPts | Mob: $mobPts | Edge: $edgePts)\n")
                            append("• 📱 MSN Read: +${readPts}đ | 📅 Daily Set: +${dailyDonePts}đ | ⚡ Keep Earning: +${promoDonePts}đ\n")
                            append("• 🏆 Tổng điểm hôm nay: +${earnedToday}đ | Nhật ký lọc (${filteredRecords.size} mục): +${totalLoggedPts}đ")
                        }

                        if (filteredRecords.isEmpty()) {
                            tvHistoryList.text = buildString {
                                append("Chưa có bản ghi nhật ký cộng điểm thời gian thực khớp bộ lọc.\n\n")
                                append("📌 Trạng thái nhiệm vụ từng tài khoản hiện tại:\n")
                                targetProfiles.forEach { p ->
                                    val tele = RewardsBalanceExtractor.latestTelemetryMap[p.id]
                                        ?: RewardsBalanceExtractor.latestTelemetryMap[p.email]
                                    append("━━━━━━━━━━━━━━━━━━━━\n")
                                    append("👤 ${p.email} (Lv.${p.level} • Tổng: ${p.availablePoints}đ)\n")
                                    append("  • Search PC: ${p.pcProgress}/${p.pcMax}đ | Mobile: ${p.mobileProgress}/${p.mobileMax}đ\n")
                                    append("  • Đọc báo MSN: ${p.readProgress}/${p.readMax}đ | Daily Set: ${p.dailySetProgress}/${p.dailySetTotal}\n")
                                    if (tele != null) {
                                        val dsLimit = tele.dailySetTotal.coerceAtLeast(3)
                                        val completedDaily = tele.dailyTasks.take(dsLimit).filter { it.isCompleted }
                                        if (completedDaily.isNotEmpty()) {
                                            completedDaily.forEach { t ->
                                                append("    ✓ [Daily Set] ${t.title} (+${t.pointValue}đ)\n")
                                            }
                                        }
                                        val completedPromos = tele.dailyTasks.drop(dsLimit).filter { it.isCompleted && it.pointValue > 0 }
                                        if (completedPromos.isNotEmpty()) {
                                            completedPromos.take(8).forEach { pr ->
                                                append("    ✓ [Keep Earning] ${pr.title} (+${pr.pointValue}đ)\n")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            tvHistoryList.text = filteredRecords.joinToString("\n────────────────────\n") { rec ->
                                val ptsBadge = if (rec.pointsDelta > 0) "+${rec.pointsDelta}đ" else "✓"
                                "[${rec.timeFormatted}] ${rec.category} ($ptsBadge) • ${rec.accountEmail}\n↳ ${rec.title}"
                            }
                        }
                    }

                    val itemListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            renderFilteredView()
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                    spAccount.onItemSelectedListener = itemListener
                    spCategory.onItemSelectedListener = itemListener

                    root.addView(spAccount)
                    root.addView(spCategory)
                    root.addView(tvSummary)
                    root.addView(scrollHistory)

                    renderFilteredView()

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🏆 Lịch Sử Cộng Điểm Từng Nhiệm Vụ & Tài Khoản")
                        .setView(root)
                        .setPositiveButton("Đóng", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(AndroidMainDispatcher) {
                    Toast.makeText(this@MainActivity, "Lỗi mở lịch sử điểm: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
        etBatchSize = viewSettings.findViewById(R.id.etBatchSize)
        etSearchDelayMin = viewSettings.findViewById(R.id.etSearchDelayMin)
        etSearchDelayMax = viewSettings.findViewById(R.id.etSearchDelayMax)
        etMaxClaimRetries = viewSettings.findViewById(R.id.etMaxClaimRetries)
        etDapiMaxRetries = viewSettings.findViewById(R.id.etDapiMaxRetries)
        etMaxReconciliationPasses = viewSettings.findViewById(R.id.etMaxReconciliationPasses)
        etCooldownTriggerBatches = viewSettings.findViewById(R.id.etCooldownTriggerBatches)
        etFrozenStopBatches = viewSettings.findViewById(R.id.etFrozenStopBatches)
        etProxyCircuitThreshold = viewSettings.findViewById(R.id.etProxyCircuitThreshold)
        etGlobalProxy = viewSettings.findViewById(R.id.etGlobalProxy)
        etUserAgent = viewSettings.findViewById(R.id.etUserAgent)
        etTelegramBotToken = viewSettings.findViewById(R.id.etTelegramBotToken)
        etTelegramChatId = viewSettings.findViewById(R.id.etTelegramChatId)
        btnTestTelegram = viewSettings.findViewById(R.id.btnTestTelegram)
        btnResetTelegramDefault = viewSettings.findViewById(R.id.btnResetTelegramDefault)
        swDarkMode = viewSettings.findViewById(R.id.swDarkMode)
        swWifiOnly = viewSettings.findViewById(R.id.swWifiOnly)
        swBatteryGuard = viewSettings.findViewById(R.id.swBatteryGuard)
        btnBatteryOptimization = viewSettings.findViewById(R.id.btnBatteryOptimization)
        swAutoDailySchedule = viewSettings.findViewById(R.id.swAutoDailySchedule)
        etScheduleSlot1 = viewSettings.findViewById(R.id.etScheduleSlot1)
        etScheduleSlot2 = viewSettings.findViewById(R.id.etScheduleSlot2)
        etScheduleSlot3 = viewSettings.findViewById(R.id.etScheduleSlot3)
        swScheduleSlot1 = viewSettings.findViewById(R.id.swScheduleSlot1)
        swScheduleSlot2 = viewSettings.findViewById(R.id.swScheduleSlot2)
        swScheduleSlot3 = viewSettings.findViewById(R.id.swScheduleSlot3)
        swAutoStopCompleted = viewSettings.findViewById(R.id.swAutoStopCompleted)
        btnEmergencyReset = viewSettings.findViewById(R.id.btnEmergencyReset)
        btnHealthCheck = viewSettings.findViewById(R.id.btnHealthCheck)
        btnVaultBackup = viewSettings.findViewById(R.id.btnVaultBackup)
        btnVaultRestore = viewSettings.findViewById(R.id.btnVaultRestore)
        btnForensicShred = viewSettings.findViewById(R.id.btnForensicShred)
        btnSaveSettings = viewSettings.findViewById(R.id.btnSaveSettings)

        loadSettingsIntoViews()

        swAutoDailySchedule.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoDailySchedule = isChecked
            updateScheduleSlotsUiState()
            if (isChecked) {
                RewardsForegroundService.scheduleDailyAlarm(this)
            } else {
                RewardsForegroundService.cancelDailyAlarm(this)
            }
        }
        swScheduleSlot1.setOnCheckedChangeListener { _, isChecked ->
            prefs.isScheduleSlot1Enabled = isChecked
            val s1 = etScheduleSlot1.text.toString().trim()
            if (EncryptedPreferences.parseTimeSlot(s1) != null) prefs.dailyScheduleSlot1 = s1
            updateScheduleSlotsUiState()
            if (prefs.isAutoDailySchedule) {
                RewardsForegroundService.scheduleDailyAlarm(this)
            }
        }
        swScheduleSlot2.setOnCheckedChangeListener { _, isChecked ->
            prefs.isScheduleSlot2Enabled = isChecked
            val s2 = etScheduleSlot2.text.toString().trim()
            if (EncryptedPreferences.parseTimeSlot(s2) != null) prefs.dailyScheduleSlot2 = s2
            updateScheduleSlotsUiState()
            if (prefs.isAutoDailySchedule) {
                RewardsForegroundService.scheduleDailyAlarm(this)
            }
        }
        swScheduleSlot3.setOnCheckedChangeListener { _, isChecked ->
            prefs.isScheduleSlot3Enabled = isChecked
            val s3 = etScheduleSlot3.text.toString().trim()
            if (EncryptedPreferences.parseTimeSlot(s3) != null) prefs.dailyScheduleSlot3 = s3
            updateScheduleSlotsUiState()
            if (prefs.isAutoDailySchedule) {
                RewardsForegroundService.scheduleDailyAlarm(this)
            }
        }
        swAutoStopCompleted.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoStopOnComplete = isChecked
        }

        // Preset Chips cho Target Searches
        btnPreset15?.setOnClickListener { etDefaultTargetSearches.setText("15") }
        btnPreset30?.setOnClickListener { etDefaultTargetSearches.setText("30") }
        btnPreset35?.setOnClickListener { etDefaultTargetSearches.setText("35") }
        btnPreset50?.setOnClickListener { etDefaultTargetSearches.setText("50") }
        btnPreset60?.setOnClickListener { etDefaultTargetSearches.setText("60") }

        // Nút Điền Mẫu Mặc Định Telegram
        btnResetTelegramDefault.setOnClickListener {
            etTelegramBotToken.setText(EncryptedPreferences.DEFAULT_TELEGRAM_BOT_TOKEN)
            etTelegramChatId.setText(EncryptedPreferences.DEFAULT_TELEGRAM_CHAT_ID)
            Toast.makeText(this, "Đã điền cấu hình Telegram mặc định!", Toast.LENGTH_SHORT).show()
        }

        // Nút Test Telegram
        btnTestTelegram.setOnClickListener {
            val token = etTelegramBotToken.text.toString().trim().ifBlank { EncryptedPreferences.DEFAULT_TELEGRAM_BOT_TOKEN }
            val chatId = etTelegramChatId.text.toString().trim().ifBlank { EncryptedPreferences.DEFAULT_TELEGRAM_CHAT_ID }
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

        // 1. Health Check (5 hạng mục)
        btnHealthCheck.setOnClickListener {
            HealthCheckDialog(this).show()
        }

        // 2. Khôi phục khẩn cấp (1-Tap Emergency Reset)
        btnEmergencyReset.setOnClickListener {
            showEmergencyResetDialog()
        }

        // 3. Dọn dẹp & Zero-Fill Shred Logs > 7 ngày
        btnForensicShred.setOnClickListener {
            showForensicShredDialog()
        }

        // 4. Sao lưu Vault (.rbkp)
        btnVaultBackup.setOnClickListener {
            showVaultBackupDialog()
        }

        // 5. Phục hồi Vault (.rbkp)
        btnVaultRestore.setOnClickListener {
            showVaultRestoreEntryDialog()
        }

        // Nút Lưu Cấu Hình
        btnSaveSettings.setOnClickListener {
            val min = (etCooldownMin.text.toString().toIntOrNull() ?: 15).coerceIn(1, 240)
            val max = maxOf(min, (etCooldownMax.text.toString().toIntOrNull() ?: 22).coerceIn(1, 360))
            val target = etDefaultTargetSearches.text.toString().toIntOrNull()?.coerceIn(10, 60) ?: 35

            prefs.cooldownMin = min
            prefs.cooldownMax = max
            prefs.cooldownMinutes = (min + max) / 2
            prefs.defaultTargetSearches = target
            prefs.batchSize = etBatchSize.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3
            val sDelayMin = etSearchDelayMin.text.toString().toIntOrNull()?.coerceIn(5, 180) ?: 18
            val sDelayMax = maxOf(sDelayMin, etSearchDelayMax.text.toString().toIntOrNull()?.coerceIn(10, 300) ?: 45)
            prefs.searchDelayMinSec = sDelayMin
            prefs.searchDelayMaxSec = sDelayMax
            prefs.maxClaimRetries = etMaxClaimRetries.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 2
            prefs.dapiMaxRetries = etDapiMaxRetries.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 2
            prefs.maxReconciliationPasses = etMaxReconciliationPasses.text.toString().toIntOrNull()?.coerceIn(0, 5) ?: 2
            prefs.cooldownTriggerBatches = etCooldownTriggerBatches.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 2
            prefs.frozenStopBatches = etFrozenStopBatches.text.toString().toIntOrNull()?.coerceIn(2, 8) ?: 3
            prefs.proxyCircuitThreshold = etProxyCircuitThreshold.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3
            prefs.dailyScheduleSlot1 = etScheduleSlot1.text.toString().trim()
            prefs.dailyScheduleSlot2 = etScheduleSlot2.text.toString().trim()
            prefs.dailyScheduleSlot3 = etScheduleSlot3.text.toString().trim()
            prefs.isScheduleSlot1Enabled = swScheduleSlot1.isChecked
            prefs.isScheduleSlot2Enabled = swScheduleSlot2.isChecked
            prefs.isScheduleSlot3Enabled = swScheduleSlot3.isChecked
            prefs.globalProxy = etGlobalProxy.text.toString().trim()
            prefs.userAgent = etUserAgent.text.toString().trim()
            prefs.telegramBotToken = etTelegramBotToken.text.toString().trim().ifBlank { EncryptedPreferences.DEFAULT_TELEGRAM_BOT_TOKEN }
            prefs.telegramChatId = etTelegramChatId.text.toString().trim().ifBlank { EncryptedPreferences.DEFAULT_TELEGRAM_CHAT_ID }
            prefs.isDarkMode = swDarkMode.isChecked
            prefs.isWifiOnly = swWifiOnly.isChecked
            prefs.isBatteryGuardEnabled = swBatteryGuard.isChecked
            prefs.isAutoDailySchedule = swAutoDailySchedule.isChecked
            prefs.isAutoStopOnComplete = swAutoStopCompleted.isChecked

            if (prefs.isAutoDailySchedule) {
                RewardsForegroundService.scheduleDailyAlarm(this)
            } else {
                RewardsForegroundService.cancelDailyAlarm(this)
            }

            if (swApplyTargetToAll.isChecked) {
                scope.launch(Dispatchers.IO) {
                    database.rewardsDao().updateAllTargetSearches(target)
                }
            }

            loadSettingsIntoViews()
            Toast.makeText(this, "Đã lưu toàn bộ cấu hình hệ thống!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateScheduleSlotsUiState() {
        val masterEnabled = swAutoDailySchedule.isChecked
        swScheduleSlot1.isEnabled = masterEnabled
        swScheduleSlot2.isEnabled = masterEnabled
        swScheduleSlot3.isEnabled = masterEnabled
        swScheduleSlot1.alpha = if (masterEnabled) 1.0f else 0.45f
        swScheduleSlot2.alpha = if (masterEnabled) 1.0f else 0.45f
        swScheduleSlot3.alpha = if (masterEnabled) 1.0f else 0.45f

        val s1Active = masterEnabled && swScheduleSlot1.isChecked
        val s2Active = masterEnabled && swScheduleSlot2.isChecked
        val s3Active = masterEnabled && swScheduleSlot3.isChecked

        etScheduleSlot1.isEnabled = s1Active
        etScheduleSlot1.alpha = if (s1Active) 1.0f else 0.35f
        etScheduleSlot2.isEnabled = s2Active
        etScheduleSlot2.alpha = if (s2Active) 1.0f else 0.35f
        etScheduleSlot3.isEnabled = s3Active
        etScheduleSlot3.alpha = if (s3Active) 1.0f else 0.35f
    }

    private fun loadSettingsIntoViews() {
        etCooldownMin.setText(prefs.cooldownMin.toString())
        etCooldownMax.setText(prefs.cooldownMax.toString())
        etDefaultTargetSearches.setText(prefs.defaultTargetSearches.toString())
        etBatchSize.setText(prefs.batchSize.toString())
        etSearchDelayMin.setText(prefs.searchDelayMinSec.toString())
        etSearchDelayMax.setText(prefs.searchDelayMaxSec.toString())
        etMaxClaimRetries.setText(prefs.maxClaimRetries.toString())
        etDapiMaxRetries.setText(prefs.dapiMaxRetries.toString())
        etMaxReconciliationPasses.setText(prefs.maxReconciliationPasses.toString())
        etCooldownTriggerBatches.setText(prefs.cooldownTriggerBatches.toString())
        etFrozenStopBatches.setText(prefs.frozenStopBatches.toString())
        etProxyCircuitThreshold.setText(prefs.proxyCircuitThreshold.toString())
        etScheduleSlot1.setText(prefs.dailyScheduleSlot1)
        etScheduleSlot2.setText(prefs.dailyScheduleSlot2)
        etScheduleSlot3.setText(prefs.dailyScheduleSlot3)
        swScheduleSlot1.isChecked = prefs.isScheduleSlot1Enabled
        swScheduleSlot2.isChecked = prefs.isScheduleSlot2Enabled
        swScheduleSlot3.isChecked = prefs.isScheduleSlot3Enabled
        etGlobalProxy.setText(prefs.globalProxy)
        etUserAgent.setText(prefs.userAgent)
        etTelegramBotToken.setText(prefs.telegramBotToken.ifBlank { EncryptedPreferences.DEFAULT_TELEGRAM_BOT_TOKEN })
        etTelegramChatId.setText(prefs.telegramChatId.ifBlank { EncryptedPreferences.DEFAULT_TELEGRAM_CHAT_ID })
        swDarkMode.isChecked = prefs.isDarkMode
        swWifiOnly.isChecked = prefs.isWifiOnly
        swBatteryGuard.isChecked = prefs.isBatteryGuardEnabled
        swAutoDailySchedule.isChecked = prefs.isAutoDailySchedule
        swAutoStopCompleted.isChecked = prefs.isAutoStopOnComplete
        tvTelemetrySafety?.text = "Cooldown ${prefs.cooldownMin}-${prefs.cooldownMax}p"
        updateScheduleSlotsUiState()
    }

    private fun stopNodeServiceIfRunning() {
        if (prefs.isNodeServiceRunning) {
            val intent = Intent(this, RewardsForegroundService::class.java).apply {
                action = RewardsForegroundService.ACTION_STOP
            }
            startService(intent)
            prefs.isNodeServiceRunning = false
            updateServiceUIState()
        }
    }

    private fun showEmergencyResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚡ Khôi Phục Khẩn Cấp (1-Tap Reset)")
            .setMessage("Thao tác này sẽ:\n• Dừng ngay lập tức dịch vụ chạy ngầm\n• Xóa sạch Cookie/Cache/Proxy phiên WebView hiện thời\n• Đặt lại thời gian chờ Cooldown của tất cả tài khoản về 0\n\nDữ liệu tài khoản và Cookie lưu trong DB vẫn được giữ nguyên an toàn.")
            .setPositiveButton("Thực Hiện Reset") { _, _ ->
                stopNodeServiceIfRunning()
                scope.launch(Dispatchers.IO) {
                    try {
                        val pm = ProfileManager(this@MainActivity)
                        pm.clearActiveSession()
                        pm.clearWebViewProxy()
                        val allProfiles = database.rewardsDao().getAllProfiles()
                        if (allProfiles.isNotEmpty()) {
                            val resetProfiles = allProfiles.map { it.copy(cooldownUntilTimestamp = 0L) }
                            database.rewardsDao().insertOrUpdateProfilesBatch(resetProfiles)
                        }
                        vlogManager.postLog("SYS", "INFO", "Đã thực thi 1-Tap Emergency Reset (xóa phiên WebView & reset cooldown)")
                        withContext(AndroidMainDispatcher) {
                            Toast.makeText(this@MainActivity, "✅ Đã khôi phục khẩn cấp & xóa trạng thái treo thành công!", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(AndroidMainDispatcher) {
                            Toast.makeText(this@MainActivity, "Lỗi khi reset: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showForensicShredDialog() {
        AlertDialog.Builder(this)
            .setTitle("🧹 Dọn Dẹp & Zero-Fill Shred")
            .setMessage("Bạn có muốn ghi đè byte 0x00 (Zero-Fill Shred) toàn bộ DOM Snapshot và cắt tỉa nhật ký Vlog cũ hơn 7 ngày kèm nén VACUUM cơ sở dữ liệu không?")
            .setPositiveButton("Dọn Dẹp Ngay") { _, _ ->
                scope.launch(Dispatchers.IO) {
                    val report = ForensicShredder(this@MainActivity).shredSnapshotsAndOldLogs(7)
                    vlogManager.postLog(
                        "SYS",
                        "INFO",
                        "Forensic Shred hoàn tất trong ${report.durationMs}ms: ${report.filesShredded} files, ${report.logsPruned} logs, VACUUM=${report.vacuumCompleted}"
                    )
                    withContext(AndroidMainDispatcher) {
                        Toast.makeText(
                            this@MainActivity,
                            "🧹 Đã hủy ${report.filesShredded} snapshot, xóa ${report.logsPruned} log cũ (${report.durationMs}ms)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showVaultBackupDialog() {
        val etPassword = EditText(this).apply {
            hint = "Nhập mật khẩu khóa sao lưu (.rbkp)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(36, 28, 36, 28)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(etPassword)
        }

        AlertDialog.Builder(this)
            .setTitle("🛡️ Sao Lưu Hồ Sơ Mã Hóa (.rbkp)")
            .setMessage("Toàn bộ tài khoản (kể cả tài khoản đang tắt), Cookie, Token và cấu hình sẽ được mã hóa chuẩn quân sự PBKDF2-HMAC-SHA256 + AES-256-GCM:")
            .setView(container)
            .setPositiveButton("Tạo Bản Sao Lưu") { _, _ ->
                val editable = etPassword.text
                val passwordChars = CharArray(editable.length) { editable[it] }
                editable.clear()
                if (passwordChars.isEmpty()) {
                    Toast.makeText(this, "Mật khẩu sao lưu không được để trống!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val backupDir = getExternalFilesDir("backups") ?: File(filesDir, "backups")
                val backupFile = File(backupDir, "RewardsNode_Backup_${timestamp}.rbkp")

                scope.launch(Dispatchers.IO) {
                    val result = try {
                        VaultBackupManager(this@MainActivity).exportVaultBackup(passwordChars, backupFile)
                    } finally {
                        passwordChars.fill('\u0000')
                    }

                    withContext(AndroidMainDispatcher) {
                        result.onSuccess { meta ->
                            pendingBackupFile = backupFile
                            vlogManager.postLog("SYS", "INFO", "Đã xuất bản sao lưu Vault: ${meta.fileName} (${meta.totalProfiles} tài khoản)")
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("✅ Sao Lưu Thành Công")
                                .setMessage("Đã đóng gói ${meta.totalProfiles} tài khoản vào:\n${backupFile.name}\n\nBạn muốn xuất file sao lưu này đi đâu?")
                                .setPositiveButton("💾 Lưu Ra Máy (SAF)") { _, _ ->
                                    val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_TITLE, backupFile.name)
                                    }
                                    try {
                                        @Suppress("DEPRECATION")
                                        startActivityForResult(createIntent, REQ_CREATE_BACKUP_DOC)
                                    } catch (e: Exception) {
                                        Toast.makeText(this@MainActivity, "Không thể mở trình chọn file: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .setNeutralButton("✈️ Gửi Qua Telegram") { _, _ ->
                                    val token = prefs.telegramBotToken
                                    val chatId = prefs.telegramChatId
                                    if (token.isBlank() || chatId.isBlank()) {
                                        Toast.makeText(this@MainActivity, "Chưa cấu hình Telegram Bot Token / Chat ID!", Toast.LENGTH_SHORT).show()
                                        return@setNeutralButton
                                    }
                                    Toast.makeText(this@MainActivity, "Đang tải file sao lưu lên Telegram...", Toast.LENGTH_SHORT).show()
                                    scope.launch(Dispatchers.IO) {
                                        val caption = "🛡️ Bản sao lưu mã hóa RewardsNode\n• Số tài khoản: ${meta.totalProfiles}\n• Tệp: ${meta.fileName}"
                                        val sent = TelegramNotifier(token, chatId).sendDocument(backupFile, caption)
                                        withContext(AndroidMainDispatcher) {
                                            if (sent) {
                                                Toast.makeText(this@MainActivity, "✅ Đã gửi file .rbkp về Telegram thành công!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "❌ Gửi file qua Telegram thất bại!", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                                .setNegativeButton("Đóng", null)
                                .show()
                        }.onFailure { err ->
                            Toast.makeText(this@MainActivity, "❌ Lỗi sao lưu: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showVaultRestoreEntryDialog() {
        val backupDir = getExternalFilesDir("backups") ?: File(filesDir, "backups")
        val latestBackup = backupDir.listFiles { f -> f.isFile && f.name.endsWith(".rbkp", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

        val openSafPicker = {
            val openIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(openIntent, REQ_OPEN_BACKUP_DOC)
            } catch (e: Exception) {
                Toast.makeText(this, "Không thể mở trình chọn file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        if (latestBackup != null) {
            AlertDialog.Builder(this)
                .setTitle("📥 Phục Hồi Hồ Sơ (.rbkp)")
                .setMessage("Tìm thấy bản sao lưu nội bộ gần nhất:\n${latestBackup.name}\n\nBạn muốn khôi phục từ bản này hay chọn file .rbkp từ bộ nhớ máy?")
                .setPositiveButton("📂 Chọn File Từ Máy") { _, _ -> openSafPicker() }
                .setNeutralButton("⚡ Dùng Bản Gần Nhất") { _, _ ->
                    showRestorePasswordDialog(latestBackup, isTempFile = false)
                }
                .setNegativeButton("Hủy", null)
                .show()
        } else {
            openSafPicker()
        }
    }

    private fun showRestorePasswordDialog(targetFile: File, isTempFile: Boolean) {
        val etPassword = EditText(this).apply {
            hint = "Nhập mật khẩu giải mã file .rbkp"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(36, 28, 36, 28)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(etPassword)
        }

        AlertDialog.Builder(this)
            .setTitle("🔓 Giải Mã & Khôi Phục (.rbkp)")
            .setMessage("Tệp: ${targetFile.name}\nNhập mật khẩu để giải mã và khôi phục toàn bộ tài khoản cùng cài đặt:")
            .setView(container)
            .setPositiveButton("Khôi Phục Ngay") { _, _ ->
                val editable = etPassword.text
                val passwordChars = CharArray(editable.length) { editable[it] }
                editable.clear()
                if (passwordChars.isEmpty()) {
                    if (isTempFile) targetFile.delete()
                    Toast.makeText(this, "Vui lòng nhập mật khẩu giải mã!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                stopNodeServiceIfRunning()

                scope.launch(Dispatchers.IO) {
                    val result = try {
                        VaultBackupManager(this@MainActivity).importVaultBackup(
                            password = passwordChars,
                            inputFile = targetFile,
                            restorePreferences = true
                        )
                    } finally {
                        passwordChars.fill('\u0000')
                        if (isTempFile) {
                            targetFile.delete()
                        }
                    }

                    withContext(AndroidMainDispatcher) {
                        result.onSuccess { count ->
                            loadSettingsIntoViews()
                            updateServiceUIState()
                            syncActiveProfilesBalance(isUserTriggered = false)
                            vlogManager.postLog("SYS", "INFO", "Đã khôi phục thành công $count tài khoản từ bản sao lưu Vault")
                            Toast.makeText(
                                this@MainActivity,
                                "✅ Đã khôi phục thành công $count tài khoản & cấu hình!",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { err ->
                            Toast.makeText(
                                this@MainActivity,
                                "❌ Khôi phục thất bại: ${err.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy") { _, _ ->
                if (isTempFile) targetFile.delete()
            }
            .setOnCancelListener {
                if (isTempFile) targetFile.delete()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data ?: return

        when (requestCode) {
            REQ_CREATE_BACKUP_DOC -> {
                val srcFile = pendingBackupFile
                if (srcFile == null || !srcFile.exists()) {
                    Toast.makeText(this, "Không tìm thấy file sao lưu nguồn!", Toast.LENGTH_SHORT).show()
                    return
                }
                scope.launch(Dispatchers.IO) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { outStream ->
                            FileInputStream(srcFile).use { inStream ->
                                inStream.copyTo(outStream, bufferSize = 8192)
                            }
                            outStream.flush()
                        }
                        withContext(AndroidMainDispatcher) {
                            Toast.makeText(this@MainActivity, "✅ Đã lưu file .rbkp ra bộ nhớ máy thành công!", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(AndroidMainDispatcher) {
                            Toast.makeText(this@MainActivity, "❌ Lỗi ghi file: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            REQ_OPEN_BACKUP_DOC -> {
                scope.launch(Dispatchers.IO) {
                    val tempFile = File(cacheDir, "vault_restore_import_${System.currentTimeMillis()}.rbkp")
                    try {
                        contentResolver.openInputStream(uri)?.use { inStream ->
                            FileOutputStream(tempFile).use { outStream ->
                                inStream.copyTo(outStream, bufferSize = 8192)
                                outStream.flush()
                            }
                        }
                        withContext(AndroidMainDispatcher) {
                            showRestorePasswordDialog(tempFile, isTempFile = true)
                        }
                    } catch (e: Exception) {
                        tempFile.delete()
                        withContext(AndroidMainDispatcher) {
                            Toast.makeText(this@MainActivity, "❌ Không thể đọc file đã chọn: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
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
        countdownTickerJob?.cancel()
        searchDebounceJob?.cancel()
        scope.cancel()
    }
}
