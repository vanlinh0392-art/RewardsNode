package com.agy.rewardsnode.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import com.agy.rewardsnode.R
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// Typealias SwitchCompat sang android.widget.Switch để chuẩn hóa 100% Native Android SDK không phụ thuộc AndroidX
typealias SwitchCompat = Switch

/**
 * Adapter danh sách tài khoản 100% Native Android SDK (BaseAdapter).
 * - Sử dụng ViewHolder pattern tái sử dụng View mượt mà, không rò rỉ bộ nhớ.
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
    private val onRefreshCompleted: ((AccountProfile) -> Unit)? = null
) : BaseAdapter() {

    private var accounts: List<AccountProfile> = emptyList()
    private val lastRefreshTimestamps = ConcurrentHashMap<String, Long>()
    private val refreshingAccounts = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun submitList(newAccounts: List<AccountProfile>) {
        this.accounts = newAccounts
        notifyDataSetChanged()
    }

    override fun getCount(): Int = accounts.size

    override fun getItem(position: Int): AccountProfile = accounts[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
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
            tvEmail.text = account.email

            val displayPoints = if (account.availablePoints > 0) account.availablePoints else account.totalPoints
            tvLevel.text = "Level ${account.level} • ${String.format("%,d pts", displayPoints)}"
            tvStreak.text = "🔥 Streak ${account.streakCount} ngày"

            // 1. Switch Account Enabled
            switchAccountEnabled.setOnCheckedChangeListener(null)
            switchAccountEnabled.isChecked = account.isEnabled
            itemView.alpha = if (account.isEnabled) 1.0f else 0.45f

            switchAccountEnabled.setOnCheckedChangeListener { _, isChecked ->
                account.isEnabled = isChecked
                itemView.alpha = if (isChecked) 1.0f else 0.45f
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = database ?: AppDatabase.getDatabase(itemView.context.applicationContext)
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

            val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val isMaxedToday = account.isAllMaxedOutToday(todayStr)

            // 3. Logic Checklist 5 Micro-Badges đọc trực tiếp từ các trường bền vững của AccountProfile entity
            // 3.1. PC Badge
            val pcMax = if (account.pcMax > 0) account.pcMax else (if (account.level == 1) 30 else 90)
            val pcProgress = account.pcProgress
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
            val edgeMax = if (account.edgeMax > 0) account.edgeMax else 12
            val edgeProgress = account.edgeProgress
            if (edgeProgress >= edgeMax && edgeMax > 0) {
                tvEdgeBadge.text = "🌐 $edgeProgress/$edgeMax ✓"
                tvEdgeBadge.setTextColor(Color.parseColor("#10B981"))
            } else {
                tvEdgeBadge.text = "🌐 $edgeProgress/$edgeMax"
                tvEdgeBadge.setTextColor(Color.parseColor("#F59E0B"))
            }
            tvEdgeBadge.setBackgroundResource(R.drawable.bg_pill_dark)

            // 3.4. Daily Set Badge
            val dailySetDone = telemetry?.dailySetDone == true || isMaxedToday
            val dailySetProgress = if (dailySetDone) 3 else (telemetry?.dailySetProgress ?: 0)
            if (dailySetDone || dailySetProgress >= 3) {
                tvDailySetBadge.text = "📅 3/3 ✓"
                tvDailySetBadge.setTextColor(Color.parseColor("#10B981"))
            } else if (dailySetProgress > 0) {
                tvDailySetBadge.text = "📅 $dailySetProgress/3"
                tvDailySetBadge.setTextColor(Color.parseColor("#818CF8"))
            } else {
                tvDailySetBadge.text = "📅 0/3"
                tvDailySetBadge.setTextColor(Color.parseColor("#94A3B8"))
            }
            tvDailySetBadge.setBackgroundResource(R.drawable.bg_pill_dark)

            // 3.5. Claim Badge
            val claimable = telemetry?.claimablePoints ?: 0
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

            pbProgress.max = account.targetSearches
            pbProgress.progress = account.completedSearchesToday

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

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = RewardsBalanceExtractor.extractBalanceFromDashboardHttp(account.cookiesSnapshot, account.proxyUrl)
                        if (result.isSuccess) {
                            if (result.availablePoints > 0) {
                                account.availablePoints = result.availablePoints
                                account.totalPoints = result.availablePoints
                            }
                            if (result.lifetimePoints > 0) account.lifetimePoints = result.lifetimePoints
                            if (result.streakCount > 0) account.streakCount = result.streakCount
                            if (result.level > 0) account.level = result.level
                            if (result.pcMax > 0) account.pcMax = result.pcMax
                            if (result.pcMax > 0 || result.pcProgress > 0) account.pcProgress = result.pcProgress
                            if (result.mobileMax > 0) account.mobileMax = result.mobileMax
                            if (result.mobileMax > 0 || result.mobileProgress > 0) account.mobileProgress = result.mobileProgress
                            if (result.edgeMax > 0) account.edgeMax = result.edgeMax
                            if (result.edgeMax > 0 || result.edgeProgress > 0) account.edgeProgress = result.edgeProgress

                            val earnedToday = when {
                                result.pointsEarnedToday > 0 -> result.pointsEarnedToday
                                (account.pcProgress + account.mobileProgress + account.edgeProgress) > 0 -> {
                                    account.pcProgress + account.mobileProgress + account.edgeProgress
                                }
                                account.pointsEarnedToday > 0 -> account.pointsEarnedToday
                                account.baselinePointsToday > 0 && account.availablePoints > account.baselinePointsToday -> {
                                    account.availablePoints - account.baselinePointsToday
                                }
                                else -> 0
                            }
                            account.pointsEarnedToday = earnedToday

                            RewardsBalanceExtractor.latestTelemetryMap[account.id] = result
                            if (account.email.isNotBlank()) {
                                RewardsBalanceExtractor.latestTelemetryMap[account.email] = result
                            }

                            val db = database ?: AppDatabase.getDatabase(itemView.context.applicationContext)
                            db.rewardsDao().updateProfile(account)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        refreshingAccounts.remove(account.id)
                        withContext(AndroidMainDispatcher) {
                            btnRefreshPoints.text = "🔄 Cập nhật"
                            btnRefreshPoints.isEnabled = true
                            this@AccountAdapter.notifyDataSetChanged()
                            onRefreshCompleted?.invoke(account)
                        }
                    }
                }
            }

            // 6. Action Click Listeners
            btnAuth.setOnClickListener { onReAuthClicked(account) }
            btnProxy.setOnClickListener { onEditProxyClicked(account) }
            btnDelete.setOnClickListener { onDeleteClicked(account) }
        }
    }
}
