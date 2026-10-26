package com.agy.rewardsnode.data.model

/**
 * Entity đại diện cho một Profile tài khoản Microsoft Rewards.
 * Mỗi tài khoản được phân lập hoàn toàn về Cookie, Storage và Proxy.
 */
data class AccountProfile(
    val id: String, // UUID hoặc email hash làm định danh thư mục profile
    var email: String,
    var profileName: String,
    var level: Int = 1, // Level 1 (30 pts PC) hoặc Level 2 (90 pts PC + 60 pts Mobile)
    var targetSearches: Int = 35, // Tự động: Level 1 = 35 (PC + Mobile trial + Edge), Level 2 = 55 lượt
    var completedSearchesToday: Int = 0,
    var totalPoints: Int = 0,
    var availablePoints: Int = 0,
    var lifetimePoints: Int = 0,
    var streakCount: Int = 0,
    var baselinePointsToday: Int = 0,
    var pointsEarnedToday: Int = 0,
    var pcProgress: Int = 0,
    var pcMax: Int = 30,
    var mobileProgress: Int = 0,
    var mobileMax: Int = 0,
    var edgeProgress: Int = 0,
    var edgeMax: Int = 0,
    var proxyUrl: String? = null, // vd: "socks5://192.168.1.100:1080" hoặc "http://..."
    var customUserAgent: String? = null,
    var cookiesSnapshot: String? = null, // Chuỗi Cookie bảo lưu phòng trường hợp khôi phục
    var lastActiveDate: String = "", // Định dạng YYYY-MM-DD
    var isEnabled: Boolean = true,
    var baselineDate: String = "",
    var dailySetProgress: Int = 0,
    var dailySetTotal: Int = 3,
    var dailySetDone: Boolean = false,
    var claimablePoints: Int = 0,
    var readProgress: Int = 0,
    var readMax: Int = 0,
    var puzzleProgress: Int = 0,
    var cooldownUntilTimestamp: Long = 0L,
    var searchWindowStartMs: Long = 0L,
    var searchesInWindow: Int = 0,
    var dapiAccessToken: String? = null,
    var dapiRefreshToken: String? = null,
    var dapiTokenExpiryTimestampMs: Long = 0L,
    @Transient var cookieExpired: Boolean = false, // Runtime flag, not persisted to DB
    @Transient var pointsBreakdownJson: String? = null // Sanitized breakdown JSON
) {
    fun isAllMaxedOutToday(todayStr: String): Boolean {
        if (lastActiveDate != todayStr) {
            android.util.Log.w("MaxedOut", "[$email] lastActiveDate=$lastActiveDate != today=$todayStr -> NOT maxed (new day)")
            return false
        }
        val effPcMax = if (pcMax > 0) pcMax else (if (level == 1) 30 else 90)
        val effMobMax = if (mobileMax > 0) mobileMax else (if (level == 1) 0 else 60)
        val effEdgeMax = if (edgeMax > 0) edgeMax else 0

        val pcDone = effPcMax == 0 || pcProgress >= effPcMax
        val mobDone = effMobMax == 0 || mobileProgress >= effMobMax
        val edgeDone = effEdgeMax == 0 || edgeProgress >= effEdgeMax
        val readDone = readMax == 0 || readProgress >= readMax

        val result = pcDone && mobDone && edgeDone && readDone
        android.util.Log.w("MaxedOut", "[$email] pc=$pcProgress/$effPcMax($pcDone) mob=$mobileProgress/$effMobMax($mobDone) edge=$edgeProgress/$effEdgeMax($edgeDone) read=$readProgress/$readMax($readDone) daily=$dailySetDone -> maxed=$result")
        return result
    }

    fun applyBalance(
        balance: com.agy.rewardsnode.core.RewardsBalanceExtractor.ExtractedRewardsBalance,
        todayStr: String? = null,
        fallbackBaseline: Int? = null
    ) {
        if (!balance.isSuccess || balance.availablePoints <= 0) return
        val wasSameDay = todayStr != null && lastActiveDate == todayStr
        val resolvedEmail = balance.email?.trim()
        if (!resolvedEmail.isNullOrBlank() &&
            !resolvedEmail.startsWith("account_") &&
            resolvedEmail.contains("@") &&
            (email.startsWith("account_") || !email.contains("@"))
        ) {
            email = resolvedEmail
        }
        val resolvedName = balance.profileName?.trim()
        if (!resolvedName.isNullOrBlank() && (profileName.startsWith("Profile ") || profileName.isBlank())) {
            profileName = resolvedName
        }
        availablePoints = balance.availablePoints
        totalPoints = balance.availablePoints
        if (balance.lifetimePoints > 0) lifetimePoints = balance.lifetimePoints
        if (balance.streakCount > 0) streakCount = balance.streakCount
        val effectiveBaseDate = baselineDate.takeIf { it.isNotBlank() } ?: lastActiveDate
        if (fallbackBaseline != null && fallbackBaseline > 0) {
            baselinePointsToday = fallbackBaseline
        } else if (todayStr != null && (effectiveBaseDate != todayStr || baselinePointsToday <= 0)) {
            baselinePointsToday = balance.availablePoints
            baselineDate = todayStr
        }
        val calcEarned = maxOf(0, availablePoints - baselinePointsToday)
        this.pointsEarnedToday = if (balance.pointsEarnedToday > 0) {
            balance.pointsEarnedToday
        } else {
            if (wasSameDay) maxOf(this.pointsEarnedToday, calcEarned) else calcEarned
        }
        if (balance.pcMax > 0) {
            val isAuthoritativeBreakdown = balance.source.contains("breakdown") || balance.hasExactCounters
            if (isAuthoritativeBreakdown) {
                pcProgress = balance.pcProgress
            } else if (wasSameDay) {
                if (this.mobileProgress > 0 && balance.mobileProgress == 0 && !balance.source.contains("breakdown")) {
                    // Nguồn không có breakdown có thể đã gộp điểm mobile vào pcProgress
                    // Nếu this.pcProgress == 0: trừ đi mobile đã biết (ví dụ 90 - 60 = 30, hoặc 60 - 60 = 0)
                    // Nếu this.pcProgress > 0: bảo toàn tiến độ PC đã có bằng maxOf(this.pcProgress, balance.pcProgress - this.mobileProgress)
                    val adjustedPc = maxOf(0, balance.pcProgress - this.mobileProgress)
                    pcProgress = if (this.pcProgress > 0) maxOf(this.pcProgress, adjustedPc) else adjustedPc
                } else if (balance.source.startsWith("flyout_http")) {
                    // Authoritative flyout khi không bị gộp mobile (ví dụ level 1 hoặc có mobile)
                    pcProgress = balance.pcProgress
                } else if (balance.pcProgress > 0) {
                    pcProgress = maxOf(this.pcProgress, balance.pcProgress)
                }
            } else {
                pcProgress = balance.pcProgress
            }
            pcMax = balance.pcMax
        }
        if (balance.mobileMax > 0) {
            mobileMax = balance.mobileMax
            val isAuthoritativeBreakdown = balance.source.contains("breakdown") || balance.hasExactCounters
            if (isAuthoritativeBreakdown) {
                mobileProgress = balance.mobileProgress
            } else if (balance.mobileProgress > 0) {
                mobileProgress = if (wasSameDay) maxOf(this.mobileProgress, balance.mobileProgress) else balance.mobileProgress
            } else if (!wasSameDay) {
                mobileProgress = 0
            }
            // Nếu wasSameDay và balance.mobileProgress == 0: duy trì this.mobileProgress (monotonic preservation)
        } else if (balance.source.startsWith("flyout_http") && level < 2) {
            mobileMax = balance.mobileMax
            mobileProgress = balance.mobileProgress
        }
        if (!balance.pointsBreakdownJson.isNullOrBlank()) {
            this.pointsBreakdownJson = balance.pointsBreakdownJson
        }
        if (balance.edgeMax > 0) {
            edgeMax = balance.edgeMax
            edgeProgress = if (wasSameDay) maxOf(edgeProgress, balance.edgeProgress) else balance.edgeProgress
        }
        if (balance.source != "serp_id_rc" || balance.claimablePoints > 0) {
            claimablePoints = balance.claimablePoints
        }
        if (balance.readNewsMax > 0) {
            this.readMax = balance.readNewsMax
            this.readProgress = if (wasSameDay) maxOf(this.readProgress, balance.readNewsProgress) else balance.readNewsProgress
        }
        this.dailySetTotal = if (balance.dailySetTotal > 0) balance.dailySetTotal else (if (this.dailySetTotal > 0) this.dailySetTotal else 3)
        this.dailySetDone = balance.dailySetDone || (this.dailySetDone && wasSameDay)
        this.dailySetProgress = if (wasSameDay) maxOf(this.dailySetProgress, balance.dailySetProgress) else balance.dailySetProgress

        if (balance.puzzlePiecesCurrent > 0) this.puzzleProgress = balance.puzzlePiecesCurrent
        if (balance.cooldownEndTimestampMs > System.currentTimeMillis()) this.cooldownUntilTimestamp = balance.cooldownEndTimestampMs
        if (balance.level in 1..2) {
            level = balance.level
        } else if (balance.mobileMax > 0 || balance.pcMax > 30) {
            level = 2
        } else if (balance.pcMax in 1..30 && balance.mobileMax == 0 && mobileMax == 0) {
            level = 1
        }
        val estimatedSearchesFromPoints = (pcProgress + mobileProgress) / 3
        if (estimatedSearchesFromPoints > completedSearchesToday) {
            completedSearchesToday = estimatedSearchesFromPoints
        }
        targetSearches = if (level < 2) {
            if (targetSearches in 10..45) targetSearches else 35
        } else {
            if (targetSearches in 40..65) targetSearches else 55
        }
        if (todayStr != null && balance.isSuccess) {
            this.lastActiveDate = todayStr
        }
    }

    companion object {
        fun createDefault(
            id: String,
            email: String,
            profileName: String = ""
        ): AccountProfile {
            val name = if (profileName.isNotBlank()) {
                profileName
            } else if (email.contains("@")) {
                "Profile ${email.substringBefore("@")}"
            } else {
                "Profile ${id.take(6)}"
            }
            return AccountProfile(
                id = id,
                email = email,
                profileName = name,
                level = 1,
                targetSearches = 35,
                availablePoints = 0,
                totalPoints = 0,
                streakCount = 0,
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
                pointsEarnedToday = 0,
                baselinePointsToday = 0
            )
        }
    }
}
