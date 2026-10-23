package com.agy.rewardsnode.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agy.rewardsnode.service.RewardsForegroundService
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import com.agy.rewardsnode.R
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

// Typealias SwitchCompat sang android.widget.Switch để chuẩn hóa 100% Native Android SDK không phụ thuộc AndroidX
typealias SwitchCompat = Switch

/**
 * Adapter danh sách tài khoản 100% Native Android SDK (BaseAdapter).
 * - Sử dụng ViewHolder pattern tái sử dụng View mượt mà, không rò rỉ bộ nhớ.
 * - Quản lý Coroutine an toàn (safeScope, cancellation, kiểm tra context Activity tránh rò rỉ bộ nhớ).
 * - Cơ chế cập nhật item cục bộ mượt mà (updateItemLocally) khi bấm nút Cập nhật điểm.
 * - Hỗ trợ switchAccountEnabled bật/tắt cày điểm cho từng account.
 * - Checklist 5 micro-badges: PC, Mob, Edge, Daily Set, Claimable Points.
 * - Nút Cập nhật điểm tức thời (btnRefreshPoints) với debounce 5s, fetch HTTP live.
 * - Hỗ trợ các hành động: Đăng nhập lại, Sửa Proxy, Xóa tài khoản.
 */
class AccountAdapter(
    private val onReAuthClicked: (AccountProfile) -> Unit,
    private val onEditProxyClicked: (AccountProfile) -> Unit,
    private val onDeleteClicked: (AccountProfile) -> Unit,
    private val database: AppDatabase? = null,
    private val onToggleEnabled: ((AccountProfile, Boolean) -> Unit)? = null,
    private val onRefreshCompleted: ((AccountProfile) -> Unit)? = null,
    private val coroutineScope: CoroutineScope? = null,
    private val onRunSingleClicked: ((AccountProfile) -> Unit)? = null
) : BaseAdapter() {

    private var accounts: List<AccountProfile> = emptyList()
    private val lastRefreshTimestamps = ConcurrentHashMap<String, Long>()
    private val refreshingAccounts = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // Quản lý CoroutineScope an toàn tránh rò rỉ bộ nhớ (leak) khi Activity bị destroy
    private val adapterJob = SupervisorJob()
    private val internalScope = CoroutineScope(AndroidMainDispatcher + adapterJob)
    private val safeScope: CoroutineScope
        get() = coroutineScope ?: internalScope

    // WeakReference tới ListView để cập nhật cục bộ mượt mà không gây leak
    private var listViewRef: WeakReference<ListView>? = null

    /**
     * Hủy tất cả coroutines đang chạy dở của adapter khi không còn sử dụng.
     */
    fun cancelScope() {
        adapterJob.cancelChildren()
    }

    /**
     * Kiểm tra context hợp lệ (Activity chưa bị destroy hoặc finishing) trước khi cập nhật UI/Toast.
     */
    private fun isContextValid(context: Context?): Boolean {
        if (context == null) return false
        if (context is Activity) {
            return !context.isFinishing && !context.isDestroyed
        }
        return true
    }

    /**
     * Cập nhật item cục bộ an toàn trên ListView mà không cần re-layout toàn bộ danh sách,
     * tránh hiện tượng giật lag, nhấp nháy hoặc nhảy vị trí cuộn của người dùng.
     */
    fun updateItemLocally(accountId: String) {
        val lv = listViewRef?.get()
        if (lv != null) {
            val first = lv.firstVisiblePosition
            val last = lv.lastVisiblePosition
            for (i in first..last) {
                if (i in accounts.indices && accounts[i].id == accountId) {
                    val childView = lv.getChildAt(i - first)
                    val holder = childView?.tag as? ViewHolder
                    if (holder != null && holder.boundAccountId == accountId) {
                        holder.bind(accounts[i])
                        return
                    }
                }
            }
        }
        // Fallback: Nếu không tìm thấy childView đang hiển thị thì báo adapter cập nhật
        notifyDataSetChanged()
    }

    fun submitList(newAccounts: List<AccountProfile>) {
        this.accounts = newAccounts
        notifyDataSetChanged()
    }

    override fun getCount(): Int = accounts.size

    override fun getItem(position: Int): AccountProfile = accounts[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        if (listViewRef?.get() == null && parent is ListView) {
            listViewRef = WeakReference(parent)
        }

        val view: View
        val holder: ViewHolder

        if (convertView == null) {
            view = LayoutInflater.from(parent.context).inflate(R.layout.item_account, parent, false)
            holder = ViewHolder(view)
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        holder.bind(getItem(position))
        return view
    }

    inner class ViewHolder(val itemView: View) {
        var boundAccountId: String? = null

        private val tvEmail: TextView = itemView.findViewById(R.id.tvAccountEmail)
        private val tvStreak: TextView = itemView.findViewById(R.id.tvAccountStreak)
        private val tvLevel: TextView = itemView.findViewById(R.id.tvAccountLevelBadge)
        private val switchAccountEnabled: SwitchCompat = itemView.findViewById(R.id.switchAccountEnabled)

        // Micro-badges Checklist
        private val tvPcBadge: TextView = itemView.findViewById(R.id.tvPcBadge)
        private val tvMobBadge: TextView = itemView.findViewById(R.id.tvMobBadge)
        private val tvEdgeBadge: TextView = itemView.findViewById(R.id.tvEdgeBadge)
        private val tvDailySetBadge: TextView = itemView.findViewById(R.id.tvDailySetBadge)
        private val tvClaimBadge: TextView = itemView.findViewById(R.id.tvClaimBadge)

        // Progress & Proxy & Delta
        private val pbProgress: ProgressBar = itemView.findViewById(R.id.pbAccountProgress)
        private val tvDeltaToday: TextView = itemView.findViewById(R.id.tvDeltaToday)
        private val tvProxyChip: TextView = itemView.findViewById(R.id.tvProxyChip)

        // Action Buttons
        private val btnRunSingle: Button = itemView.findViewById(R.id.btnRunSingle)
        private val btnRefreshPoints: Button = itemView.findViewById(R.id.btnRefreshPoints)
        private val btnAuth: Button = itemView.findViewById(R.id.btnAuth)
        private val btnProxy: Button = itemView.findViewById(R.id.btnEditProxy)
        private val btnDelete: Button = itemView.findViewById(R.id.btnDeleteAccount)

        // Legacy compatibility views (zero-crash)
        private val tvBasketPc: TextView? = itemView.findViewById(R.id.tvBasketPc)
        private val tvBasketMobile: TextView? = itemView.findViewById(R.id.tvBasketMobile)
        private val tvBasketEdge: TextView? = itemView.findViewById(R.id.tvBasketEdge)
        private val tvProgress: TextView? = itemView.findViewById(R.id.tvAccountProgress)
        private val tvProxy: TextView? = itemView.findViewById(R.id.tvAccountProxy)

        fun bind(account: AccountProfile) {
            boundAccountId = account.id

            // Cookie expired visual indicator
            if (account.cookieExpired) {
                tvEmail.text = "\u26A0\uFE0F ${account.email}"
                tvEmail.setTextColor(Color.parseColor("#EF4444"))
                tvLevel.setTextColor(Color.parseColor("#F87171"))
                tvStreak.text = "\uD83D\uDD11 Cookie het han - Nhan Dang nhap lai"
                tvStreak.setTextColor(Color.parseColor("#EF4444"))
                itemView.alpha = 0.6f
            } else {
                tvEmail.text = account.email
                tvEmail.setTextColor(Color.parseColor("#E2E8F0"))
                tvLevel.setTextColor(Color.parseColor("#94A3B8"))
            }

            val displayPoints = if (account.availablePoints > 0) account.availablePoints else account.totalPoints
            tvLevel.text = "Level ${account.level} • ${String.format("%,d pts", displayPoints)}"
            tvStreak.text = "🔥 Streak ${account.streakCount} ngày"

            // 1. Switch Account Enabled
            switchAccountEnabled.setOnCheckedChangeListener(null)
            switchAccountEnabled.isChecked = account.isEnabled
            itemView.alpha = if (account.isEnabled) 1.0f else 0.45f

            val appContext = itemView.context.applicationContext
            switchAccountEnabled.setOnCheckedChangeListener { _, isChecked ->
                account.isEnabled = isChecked
                itemView.alpha = if (isChecked) 1.0f else 0.45f
                safeScope.launch(Dispatchers.IO) {
                    try {
                        val db = database ?: AppDatabase.getDatabase(appContext)
                        db.rewardsDao().updateProfile(account)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                onToggleEnabled?.invoke(account, isChecked)
            }

            // 2. Lấy dữ liệu telemetry từ RAM cache nếu có (phục vụ Daily Set & Claim point bonus)
            val telemetry = RewardsBalanceExtractor.latestTelemetryMap[account.id]
                ?: RewardsBalanceExtractor.latestTelemetryMap[account.email]

            if (telemetry != null && telemetry.monthlyLevelMax > 0) {
                tvLevel.text = "Level ${account.level} (${telemetry.monthlyLevelProgress}/${telemetry.monthlyLevelMax}) • ${String.format("%,d pts", displayPoints)}"
            }
            if (!account.cookieExpired) {
                if (telemetry?.isAccountSuspended == true) {
                    tvStreak.text = "⛔ Tài khoản bị tạm ngưng Rewards"
                    tvStreak.setTextColor(Color.parseColor("#EF4444"))
                } else {
                    val streakSb = StringBuilder("🔥 Streak ${account.streakCount} ngày")
                    val effReadMax = if (account.readMax > 0) account.readMax else (telemetry?.readNewsMax ?: 30)
                    val effReadProg = maxOf(account.readProgress, telemetry?.readNewsProgress ?: 0)
                    if (effReadMax > 0) {
                        streakSb.append(" • 📰 $effReadProg/$effReadMax")
                    }
                    if (telemetry?.streakProtectionOn == true) {
                        streakSb.append(" • 🛡️")
                    }
                    if (telemetry != null && !telemetry.redeemGoalTitle.isNullOrBlank() && telemetry.redeemGoalMax > 0) {
                        val goalPct = minOf(100, displayPoints * 100 / telemetry.redeemGoalMax)
                        streakSb.append(" • 🎯 $goalPct%")
                    }
                    tvStreak.text = streakSb.toString()
                    tvStreak.setTextColor(Color.parseColor("#94A3B8"))
                }
            }

            // 2.5. Ngày hiện tại đã được xử lý trong telemetry & AccountProfile
            // Đã tách bạch hoàn toàn Daily Set với isAllMaxedOutToday

            // 3. Logic Checklist 5 Micro-Badges đọc trực tiếp từ các trường bền vững của AccountProfile entity
            // 3.1. PC Badge (Clamp chặt chẽ, tách bạch tuyệt đối với pointsEarnedToday)
            val pcMax = if (account.pcMax > 0) account.pcMax else (if (account.level == 1) 30 else 90)
            val pcProgress = minOf(account.pcProgress, pcMax)
            if (pcProgress >= pcMax && pcMax > 0) {
                tvPcBadge.text = "🖥️ $pcProgress/$pcMax ✓"
                tvPcBadge.setTextColor(Color.parseColor("#10B981"))
            } else {
                tvPcBadge.text = "🖥️ $pcProgress/$pcMax"
                tvPcBadge.setTextColor(Color.parseColor("#F59E0B"))
            }
            tvPcBadge.setBackgroundResource(R.drawable.bg_pill_dark)

            // 3.2. Mobile Badge
            val mobileMax = if (account.mobileMax > 0) account.mobileMax else (if (account.level == 1) 0 else 60)
            val mobileProgress = account.mobileProgress
            if (mobileMax == 0) {
                tvMobBadge.text = "📱 0/0"
                tvMobBadge.setTextColor(Color.parseColor("#94A3B8"))
            } else if (mobileProgress >= mobileMax && mobileMax > 0) {
                tvMobBadge.text = "📱 $mobileProgress/$mobileMax ✓"
                tvMobBadge.setTextColor(Color.parseColor("#10B981"))
            } else if (mobileProgress > 0) {
                tvMobBadge.text = "📱 $mobileProgress/$mobileMax"
                tvMobBadge.setTextColor(Color.parseColor("#06B6D4"))
            } else {
                tvMobBadge.text = "📱 $mobileProgress/$mobileMax"
                tvMobBadge.setTextColor(Color.parseColor("#94A3B8"))
            }
            tvMobBadge.setBackgroundResource(R.drawable.bg_pill_dark)

            // 3.3. Edge Badge
            val edgeMax = if (account.edgeMax > 0) account.edgeMax else 0
            val edgeProgress = account.edgeProgress
            if (edgeMax == 0) {
                tvEdgeBadge.text = "🌐 0/0"
                tvEdgeBadge.setTextColor(Color.parseColor("#94A3B8"))
            } else if (edgeProgress >= edgeMax && edgeMax > 0) {
                tvEdgeBadge.text = "🌐 $edgeProgress/$edgeMax ✓"
                tvEdgeBadge.setTextColor(Color.parseColor("#10B981"))
            } else {
                tvEdgeBadge.text = "🌐 $edgeProgress/$edgeMax"
                tvEdgeBadge.setTextColor(Color.parseColor("#F59E0B"))
            }
            tvEdgeBadge.setBackgroundResource(R.drawable.bg_pill_dark)

            // 3.4. Daily Set Badge
            val dailySetDone = (telemetry?.dailySetDone ?: account.dailySetDone) || (account.dailySetTotal > 0 && account.dailySetProgress >= account.dailySetTotal)
            val dynamicTotal = if ((telemetry?.dailySetTotal ?: 0) > 0) {
                telemetry!!.dailySetTotal
            } else if (account.dailySetTotal > 0) {
                account.dailySetTotal
            } else if (account.dailySetProgress > 3) {
                account.dailySetProgress
            } else {
                3
            }
            val dailySetProgress = if (dailySetDone) dynamicTotal else maxOf(account.dailySetProgress, telemetry?.dailySetProgress ?: 0)
            if (dailySetDone || dailySetProgress >= dynamicTotal) {
                tvDailySetBadge.text = "📅 $dynamicTotal/$dynamicTotal ✓"
                tvDailySetBadge.setTextColor(Color.parseColor("#10B981"))
            } else if (dailySetProgress > 0) {
                tvDailySetBadge.text = "📅 $dailySetProgress/$dynamicTotal"
                tvDailySetBadge.setTextColor(Color.parseColor("#818CF8"))
            } else {
                tvDailySetBadge.text = "📅 0/$dynamicTotal"
                tvDailySetBadge.setTextColor(Color.parseColor("#94A3B8"))
            }
            tvDailySetBadge.setBackgroundResource(R.drawable.bg_pill_dark)

            // 3.5. Claim Badge
            val claimable = telemetry?.claimablePoints ?: account.claimablePoints
            if (claimable <= 0) {
                tvClaimBadge.text = "🎁 Đã nhận ✓"
                tvClaimBadge.setTextColor(Color.parseColor("#10B981"))
            } else {
                tvClaimBadge.text = "🎁 +$claimable chờ"
                tvClaimBadge.setTextColor(Color.parseColor("#EC4899"))
            }
            tvClaimBadge.setBackgroundResource(R.drawable.bg_pill_dark)

            // 4. Proxy Chip, Progress Bar, Delta Today
            tvProxyChip.text = if (account.proxyUrl.isNullOrBlank()) {
                "🛡️ Trực tiếp"
            } else {
                "🛡️ ${account.proxyUrl}"
            }

            val effectiveTarget = if (account.targetSearches > 0) account.targetSearches else (if (account.level == 1) 35 else 55)
            pbProgress.max = effectiveTarget
            pbProgress.progress = minOf(maxOf(account.completedSearchesToday, (pcProgress + mobileProgress) / 3), effectiveTarget)

            val deltaToday = when {
                account.pointsEarnedToday > 0 -> account.pointsEarnedToday
                (account.pcProgress + account.mobileProgress + account.edgeProgress) > 0 -> {
                    account.pcProgress + account.mobileProgress + account.edgeProgress
                }
                account.baselinePointsToday > 0 && account.availablePoints > account.baselinePointsToday -> {
                    account.availablePoints - account.baselinePointsToday
                }
                else -> 0
            }
            tvDeltaToday.text = "+$deltaToday hôm nay"

            // Legacy Views
            tvBasketPc?.text = "🖥️ PC: ${account.pcProgress}/$pcMax"
            tvBasketMobile?.text = "📱 Mob: ${account.mobileProgress}/$mobileMax"
            tvBasketEdge?.text = "🌐 Edge: ${account.edgeProgress}/$edgeMax"
            tvProgress?.text = "Hôm nay: ${account.completedSearchesToday}/${account.targetSearches} lượt (+$deltaToday pts)"
            tvProxy?.text = if (account.proxyUrl.isNullOrBlank()) "Proxy: Không dùng (Direct IP)" else "Proxy: ${account.proxyUrl}"

            // 5. Button Refresh Points (Live manual refresh qua HTTP)
            val isRefreshing = refreshingAccounts.contains(account.id)
            if (isRefreshing) {
                btnRefreshPoints.text = "⏳ Đang lấy..."
                btnRefreshPoints.isEnabled = false
            } else {
                btnRefreshPoints.text = "🔄 Cập nhật"
                btnRefreshPoints.isEnabled = true
            }

            btnRefreshPoints.setOnClickListener {
                val now = System.currentTimeMillis()
                val lastClick = lastRefreshTimestamps[account.id] ?: 0L
                if (now - lastClick < 5000L || refreshingAccounts.contains(account.id)) {
                    return@setOnClickListener
                }
                lastRefreshTimestamps[account.id] = now
                refreshingAccounts.add(account.id)
                btnRefreshPoints.text = "⏳ Đang lấy..."
                btnRefreshPoints.isEnabled = false

                val currentAccountId = account.id

                safeScope.launch(Dispatchers.IO) {
                    var successMessage: String? = null
                    var errorMessage: String? = null
                    try {
                        // DEBUG: Log cookie cho từng account để trace bug "chỉ update 1 account"
                        val cookieLen = account.cookiesSnapshot?.length ?: 0
                        val cookiePreview = account.cookiesSnapshot?.take(60) ?: "NULL"
                        Log.w("AcctRefresh", "[${account.email}] id=${currentAccountId} cookieLen=$cookieLen preview=$cookiePreview")
                        // Gọi trích xuất qua HTTP đa tầng (flyoutpageinformation -> getuserinfo -> dashboard) kèm customUserAgent
                        var result = RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot, account.customUserAgent, account.proxyUrl)
                        if ((!result.isSuccess || result.availablePoints <= 0 || account.cookieExpired) && !account.cookiesSnapshot.isNullOrBlank()) {
                            val renewedCookies = ProfileManager.renewCookiesViaPureHttp(
                                account.cookiesSnapshot ?: "",
                                account.customUserAgent,
                                account.proxyUrl
                            )
                            if (!renewedCookies.isNullOrBlank()) {
                                account.cookiesSnapshot = renewedCookies
                                account.cookieExpired = false
                                val dbRenew = database ?: AppDatabase.getDatabase(appContext)
                                dbRenew.rewardsDao().updateProfile(account)
                                result = RewardsBalanceExtractor.extractPointsViaHttp(account.cookiesSnapshot, account.customUserAgent, account.proxyUrl)
                            }
                        }
                        Log.w("AcctRefresh", "[${account.email}] result.isSuccess=${result.isSuccess} pts=${result.availablePoints} source=${result.source}")
                        if (result.isSuccess) {
                            if (result.availablePoints > 0) {
                                account.availablePoints = result.availablePoints
                                account.totalPoints = result.availablePoints
                            }
                            if (result.lifetimePoints > 0) account.lifetimePoints = result.lifetimePoints
                            if (result.streakCount > 0) account.streakCount = result.streakCount
                            if (result.level in 1..2) account.level = result.level

                            val pcM = if (result.pcMax > 0) result.pcMax else (if (account.level == 1) 30 else 90)
                            account.pcMax = pcM
                            account.pcProgress = minOf(result.pcProgress, pcM)

                            val mobM = if (result.mobileMax > 0) result.mobileMax else (if (account.level == 1) 0 else 60)
                            account.mobileMax = mobM
                            account.mobileProgress = if (mobM > 0) minOf(result.mobileProgress, mobM) else 0

                            val edgeM = if (result.edgeMax > 0) result.edgeMax else 0
                            account.edgeMax = edgeM
                            account.edgeProgress = if (edgeM > 0) minOf(result.edgeProgress, edgeM) else 0
                            account.targetSearches = if (account.level == 1) { if (account.targetSearches in 10..45) account.targetSearches else 35 } else { if (account.targetSearches in 40..65) account.targetSearches else 55 }
                            if (result.readNewsMax > 0) {
                                account.readMax = result.readNewsMax
                                account.readProgress = maxOf(account.readProgress, result.readNewsProgress)
                            }
                            account.completedSearchesToday = maxOf(account.completedSearchesToday, (account.pcProgress + account.mobileProgress) / 3)

                            val earnedToday = when {
                                result.pointsEarnedToday > 0 -> result.pointsEarnedToday
                                (account.pcProgress + account.mobileProgress + account.edgeProgress) > 0 -> {
                                    account.pcProgress + account.mobileProgress + account.edgeProgress
                                }
                                account.pointsEarnedToday in 1..200 -> account.pointsEarnedToday
                                account.baselinePointsToday > 0 && account.availablePoints > account.baselinePointsToday -> {
                                    account.availablePoints - account.baselinePointsToday
                                }
                                else -> 0
                            }
                            account.pointsEarnedToday = earnedToday

                            if (result.dailySetDone) {
                                account.dailySetDone = true
                                account.dailySetProgress = maxOf(result.dailySetProgress, result.dailySetTotal)
                            } else {
                                account.dailySetDone = false
                                account.dailySetProgress = result.dailySetProgress
                            }
                            if (result.dailySetTotal > 0) {
                                account.dailySetTotal = result.dailySetTotal
                            }
                            account.claimablePoints = result.claimablePoints

                            RewardsBalanceExtractor.latestTelemetryMap[account.id] = result
                            if (account.email.isNotBlank()) {
                                RewardsBalanceExtractor.latestTelemetryMap[account.email] = result
                            }

                            val db = database ?: AppDatabase.getDatabase(appContext)
                            db.rewardsDao().updateProfile(account)
                            account.cookieExpired = false
                            successMessage = "✅ Đã cập nhật ${account.email}: ${String.format("%,d", account.availablePoints)} pts (+${account.pointsEarnedToday} hôm nay)"
                        } else {
                            account.cookieExpired = true
                            errorMessage = "⚠️ ${account.email}: Phiên đăng nhập hết hạn! Nhấn 'Đăng nhập lại' để gia hạn cookie."
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        errorMessage = "❌ Lỗi mạng: ${e.message}"
                    } finally {
                        refreshingAccounts.remove(currentAccountId)
                        withContext(AndroidMainDispatcher) {
                            // Cập nhật account trong list in-memory của Adapter
                            val index = accounts.indexOfFirst { it.id == currentAccountId }
                            if (index != -1) {
                                val updatedList = accounts.toMutableList()
                                updatedList[index] = account
                                accounts = updatedList
                            }

                            // Cập nhật item cục bộ an toàn
                            updateItemLocally(currentAccountId)

                            // Kiểm tra context trước khi cập nhật Toast và trigger callback
                            val currentContext = itemView.context
                            if (isContextValid(currentContext)) {
                                if (successMessage != null) {
                                    Toast.makeText(currentContext, successMessage, Toast.LENGTH_SHORT).show()
                                } else if (errorMessage != null) {
                                    Toast.makeText(currentContext, errorMessage, Toast.LENGTH_LONG).show()
                                }
                            }
                            onRefreshCompleted?.invoke(account)
                        }
                    }
                }
            }

            // 6. Action Click Listeners
            btnRunSingle.setOnClickListener {
                if (onRunSingleClicked != null) {
                    onRunSingleClicked.invoke(account)
                } else {
                    val ctx = itemView.context
                    val intent = Intent(ctx, RewardsForegroundService::class.java).apply {
                        action = RewardsForegroundService.ACTION_START
                        putExtra(RewardsForegroundService.EXTRA_TARGET_ACCOUNT_ID, account.id)
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ctx.startForegroundService(intent)
                        } else {
                            ctx.startService(intent)
                        }
                        Toast.makeText(ctx, "▶ Đang chạy tài khoản: ${account.email}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Lỗi chạy service: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            btnAuth.setOnClickListener { onReAuthClicked(account) }
            btnProxy.setOnClickListener { onEditProxyClicked(account) }
            btnDelete.setOnClickListener { onDeleteClicked(account) }
        }
    }
}