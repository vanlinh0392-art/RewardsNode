package com.agy.rewardsnode.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity đại diện cho một Profile tài khoản Microsoft Rewards.
 * Mỗi tài khoản được phân lập hoàn toàn về Cookie, Storage và Proxy.
 */
@Entity(tableName = "account_profiles")
data class AccountProfile(
    @PrimaryKey
    val id: String, // UUID hoặc email hash làm định danh thư mục profile
    val email: String,
    val profileName: String,
    val level: Int = 2, // Level 1 (30 pts PC) hoặc Level 2 (90 pts PC + 60 pts Mobile)
    val targetSearches: Int = 35, // Số lượt tìm kiếm mục tiêu trong ngày
    var completedSearchesToday: Int = 0,
    var totalPoints: Int = 0,
    var proxyUrl: String? = null, // vd: "socks5://192.168.1.100:1080" hoặc "http://..."
    var customUserAgent: String? = null,
    var cookiesSnapshot: String? = null, // Chuỗi Cookie bảo lưu phòng trường hợp khôi phục
    var lastActiveDate: String = "", // Định dạng YYYY-MM-DD
    var isEnabled: Boolean = true
)
