package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile

/**
 * Động cơ Quản lý Hạn ngạch Cửa sổ Trượt (Sliding Window Token Bucket Engine)
 * Thiết kế né triệt để 100% cơ chế Cooldown 15 phút của Microsoft Rewards.
 *
 * Định mức chuẩn:
 * - Cửa sổ thời gian an toàn: 920.000 ms (15 phút + 20s đệm chống clock-drift).
 * - Số lượt tìm kiếm tối đa trong mỗi cửa sổ: 3 queries (tương ứng 9 điểm).
 */
object SearchTokenBucketEngine {

    /** Cửa sổ Cooldown an toàn: 15 phút + 20 giây đệm chống lệch xung nhịp */
    const val WINDOW_DURATION_MS: Long = 920_000L

    /** Số lượt tìm kiếm tối đa được phép thực hiện trong 1 cửa sổ 15 phút */
    const val MAX_SEARCHES_PER_WINDOW: Int = 3

    /**
     * Kiểm tra tài khoản có đủ điều kiện thực hiện thêm 1 lượt tìm kiếm hay không.
     * Tự động tái tạo cửa sổ mới nếu đã trôi qua quá 920 giây.
     */
    fun canExecuteSearch(account: AccountProfile, nowMs: Long = System.currentTimeMillis()): Boolean {
        // 1. Nếu chưa từng khởi tạo hoặc đã qua 920s -> Khởi tạo/Tái tạo Window mới
        if (account.searchWindowStartMs <= 0L || nowMs - account.searchWindowStartMs >= WINDOW_DURATION_MS) {
            account.searchWindowStartMs = nowMs
            account.searchesInWindow = 0
            if (account.cooldownUntilTimestamp <= nowMs) {
                account.cooldownUntilTimestamp = 0L
            }
            return true
        }

        // 2. Nếu đang trong Window và chưa chạm trần 3 queries -> Cho phép
        if (account.searchesInWindow < MAX_SEARCHES_PER_WINDOW) {
            return true
        }

        // 3. Đã chạm trần 3 queries -> Khóa slot và chốt mốc thời gian mở khóa
        val expectedUnlock = account.searchWindowStartMs + WINDOW_DURATION_MS
        if (account.cooldownUntilTimestamp < expectedUnlock) {
            account.cooldownUntilTimestamp = expectedUnlock
        }
        return false
    }

    /**
     * Ghi nhận một lượt tìm kiếm thành công vào Window hiện tại.
     * Nếu chạm trần 3 queries, tự động chốt cooldownUntilTimestamp.
     * @return số lượt tìm kiếm đã tích lũy trong window hiện tại.
     */
    fun recordSearch(account: AccountProfile, nowMs: Long = System.currentTimeMillis()): Int {
        // Đảm bảo window còn hiệu lực, nếu đã hết hạn thì reset trước
        if (account.searchWindowStartMs <= 0L || nowMs - account.searchWindowStartMs >= WINDOW_DURATION_MS) {
            account.searchWindowStartMs = nowMs
            account.searchesInWindow = 0
            account.cooldownUntilTimestamp = 0L
        }

        account.searchesInWindow++

        if (account.searchesInWindow >= MAX_SEARCHES_PER_WINDOW) {
            account.cooldownUntilTimestamp = account.searchWindowStartMs + WINDOW_DURATION_MS
        }

        return account.searchesInWindow
    }

    /**
     * Tính toán chính xác thời gian còn phải chờ (mili-giây) cho đến khi mở khóa Window mới.
     * @return 0L nếu tài khoản đang sẵn sàng, > 0L nếu đang trong thời gian nghỉ.
     */
    fun getRemainingCooldownMs(account: AccountProfile, nowMs: Long = System.currentTimeMillis()): Long {
        if (canExecuteSearch(account, nowMs)) {
            return 0L
        }
        val unlockTime = account.searchWindowStartMs + WINDOW_DURATION_MS
        return maxOf(0L, unlockTime - nowMs)
    }

    /**
     * Khởi tạo lại cửa sổ tìm kiếm mới ngay lập tức cho tài khoản.
     */
    fun resetWindow(account: AccountProfile, nowMs: Long = System.currentTimeMillis()) {
        account.searchWindowStartMs = nowMs
        account.searchesInWindow = 0
        account.cooldownUntilTimestamp = 0L
    }
}
