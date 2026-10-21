package com.agy.rewardsnode.data.model

/**
 * Entity đại diện cho một Profile tài khoản Microsoft Rewards.
 * Mỗi tài khoản được phân lập hoàn toàn về Cookie, Storage và Proxy.
 */
data class AccountProfile(
    val id: String, // UUID hoặc email hash làm định danh thư mục profile
    val email: String,
    val profileName: String,
    var level: Int = 2, // Level 1 (30 pts PC) hoặc Level 2 (90 pts PC + 60 pts Mobile)
    var targetSearches: Int = 55, // Tự động: Level 1 = 35 (PC + Mobile trial + Edge), Level 2 = 55 lượt
    var completedSearchesToday: Int = 0,
    var totalPoints: Int = 0,
    var availablePoints: Int = 0,
    var lifetimePoints: Int = 0,
    var streakCount: Int = 0,
    var baselinePointsToday: Int = 0,
    var pointsEarnedToday: Int = 0,
    var pcProgress: Int = 0,
    var pcMax: Int = 0,
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
    var dailySetDone: Boolean = false,
    var claimablePoints: Int = 0
) {
    fun isAllMaxedOutToday(todayStr: String): Boolean {
        if (lastActiveDate != todayStr) return false
        val effPcMax = if (pcMax > 0) pcMax else (if (level == 1) 30 else 90)
        val effMobMax = if (mobileMax > 0) mobileMax else 60
        val effEdgeMax = if (edgeMax > 0) edgeMax else 12

        val pcDone = pcProgress >= effPcMax
        val edgeDone = edgeProgress >= effEdgeMax
        val mobDone = mobileProgress >= effMobMax || (mobileMax == 0 && completedSearchesToday >= targetSearches)

        return pcDone && mobDone && edgeDone
    }
}
